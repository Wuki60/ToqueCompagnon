package app.toque.companion.flows

import app.toque.companion.alerts.AlertKind
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Route
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.EnvoyeeState
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.JournalFilter
import app.toque.companion.ui.screens.JournalKind
import app.toque.companion.ui.screens.JournalOutcome
import app.toque.companion.ui.screens.JournalState
import app.toque.companion.ui.screens.PairMethod
import app.toque.companion.ui.screens.PairingError
import app.toque.companion.ui.screens.RecettesState
import app.toque.companion.ui.screens.RobotState
import app.toque.companion.ui.screens.SendStatus
import app.toque.core.companion.CookSession
import app.toque.core.companion.WaitingEnd
import app.toque.core.companion.WaitingFor
import app.toque.core.machine.Programs
import app.toque.core.protocol.Direction
import app.toque.core.companion.ApiError
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import app.toque.core.json.Json
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.SearchIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import app.toque.companion.ui.screens.AlertKind as ScreenAlert

/**
 * Les six écrans branchés, de bout en bout sur la JVM : un vrai hub sur base simulée (60 recettes),
 * la vraie liaison, et les flux appelés comme les écrans les appellent (interfaces d'actions).
 */
class FlowsEndToEndTest {
    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val robots = ArrayList<SimRobot>()
    private val links = ArrayList<RobotLink>()
    private val schedule: Scheduler = { ms, r -> val f = main.schedule(r, ms, TimeUnit.MILLISECONDS); { f.cancel(false) } }

    @After fun tearDown() {
        links.forEach { it.close() }
        robots.forEach { it.shutdown() }
        main.shutdownNow()
    }

    private fun <T> onMain(block: () -> T): T = main.submit(Callable { block() }).get(10, TimeUnit.SECONDS)

    private fun eventually(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(40)
        }
        throw AssertionError("délai dépassé : $what")
    }

    private fun robot(port: Int = 0, bind: String = "127.0.0.1") = SimRobot().start(port, bind).also { robots += it }

    private fun link(ports: List<Int>, hosts: List<String> = listOf("127.0.0.1")) =
        RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { hosts }, ports = ports,
            waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500).also { links += it }

    private fun paired(robot: SimRobot): RobotLink {
        val l = link(listOf(robot.port))
        val code = robot.hub.pairing.open().code
        val done = CountDownLatch(1)
        var r: PairResult? = null
        l.pairWithCode(code) { r = it; done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue("$r", r is PairResult.Paired)
        return l
    }

    private fun canBind(host: String): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName(host), 0)) }
        true
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------ appairage

    @Test fun pairingByCodeAndByQr() {
        assumeTrue("127.0.0.2 indisponible", canBind("127.0.0.2"))
        val one = robot()
        val two = robot(one.port, "127.0.0.2")
        val link = link(listOf(one.port), listOf("127.0.0.1", "127.0.0.2"))
        val flow = onMain { PairingFlow(link, {}) }
        fun settle(what: String) = eventually(what) { !flow.state.busy }

        // Pas un QR code Toque : refusé sur place, sans réseau.
        onMain { flow.qrRead("https://example.org/") }
        assertEquals(PairingError.NOT_A_TOQUE_CODE, onMain { flow.state.error })

        // Aucun robot n'a l'appairage ouvert : l'écran demande l'adresse.
        onMain { flow.codeSubmitted("123456", null) }
        settle("aucun robot")
        assertEquals(PairingError.ROBOT_NOT_FOUND, onMain { flow.state.error })
        assertTrue(onMain { flow.state.askAddress })

        // Deux robots ouverts : signalé, le code n'est essayé sur aucun.
        val c1 = one.hub.pairing.open().code
        val c2 = two.hub.pairing.open().code
        onMain { flow.codeSubmitted(c2, null) }
        settle("plusieurs robots")
        assertEquals(PairingError.SEVERAL_ROBOTS, onMain { flow.state.error })
        assertTrue(onMain { flow.state.askAddress })
        assertTrue(one.hub.pairing.isOpen && two.hub.pairing.isOpen)
        assertNull(link.paired)

        // Mauvais code, à l'adresse affichée par le robot (avec son port, comme il l'écrit).
        val wrong = if (c2 == "000000") "111111" else "000000"
        onMain { flow.codeSubmitted(wrong, "127.0.0.2:${two.port}") }
        settle("mauvais code")
        assertEquals(PairingError.CODE_REFUSED, onMain { flow.state.error })

        // Code périmé (appairage refermé sur le robot).
        two.hub.pairing.close()
        onMain { flow.codeSubmitted(c2, "127.0.0.2:${two.port}") }
        settle("code périmé")
        assertEquals(PairingError.CODE_EXPIRED, onMain { flow.state.error })

        // Un seul robot ouvert : le code trouve le robot tout seul.
        onMain { flow.codeSubmitted(c1, null) }
        settle("appairé par code")
        assertNull(onMain { flow.state.error })
        assertEquals("127.0.0.1", link.paired!!.host)
        assertEquals("code", link.paired!!.method)
        assertEquals("Monsieur Cuisine Connect", link.paired!!.robotName)

        // Par QR code, avec un autre téléphone : un QR code périmé d'abord, puis le bon.
        val phone2 = link(listOf(two.port), listOf("127.0.0.2"))
        val qrFlow = onMain { PairingFlow(phone2, {}) }
        two.hub.pairing.open()
        val stale = two.hub.pairingPayload("127.0.0.2")!!
        two.hub.pairing.close()
        onMain { qrFlow.qrRead(stale) }
        eventually("QR périmé") { !qrFlow.state.busy }
        assertEquals(PairingError.CODE_EXPIRED, onMain { qrFlow.state.error })
        two.hub.pairing.open()
        val qr = two.hub.pairingPayload("127.0.0.2")!!
        // Le QR code ne porte plus le nom du robot : il vient de la réponse d'appairage.
        assertFalse(qr.contains("&n="))
        onMain { qrFlow.qrRead(qr) }
        eventually("appairé par QR") { !qrFlow.state.busy && phone2.paired != null }
        assertEquals("qr", phone2.paired!!.method)
        assertEquals("127.0.0.2", phone2.paired!!.host)
        assertEquals("Monsieur Cuisine Connect", phone2.paired!!.robotName)
    }

    @Test fun addressesAsTheRobotShowsThem() {
        assertEquals("192.168.0.12" to 8088, PairingFlow.parseAddress(" 192.168.0.12 "))
        assertEquals("192.168.0.12" to 8089, PairingFlow.parseAddress("192.168.0.12:8089"))
        assertNull(PairingFlow.parseAddress("robot.local"))
        assertNull(PairingFlow.parseAddress("192.168.0.12:99999"))
        assertEquals(PairingError.TOO_MANY_TRIES, PairingFlow.refusal("code_dead", false))
        assertEquals(PairingError.TOO_MANY_PHONES, PairingFlow.refusal("too_many_phones", true))
        assertEquals(PairingError.CODE_EXPIRED, PairingFlow.refusal("wrong_code", qr = true))
    }

    // ------------------------------------------------------------------ recettes

    @Test fun catalogueFicheAndSentRecipe() {
        val robot = robot()
        val link = paired(robot)
        val nav = FakeNav()
        var list = RecettesState()
        var fiche: FicheState? = null
        var sent: EnvoyeeState? = null
        val notices = ArrayList<String>()
        val flow = onMain {
            CatalogFlow(link, nav, { list = it }, { fiche = it }, { sent = it }, schedule, notice = { notices += it }, pageSize = 20)
        }
        onMain { link.follow("test") }
        eventually("connecté") { link.status.phase == LinkPhase.CONNECTED }

        // Pages à la demande (de 20 ici, 48 dans l'appli) : 20, 40, 60 recettes, puis plus rien.
        onMain { nav.select(Tab.RECIPES); flow.ensureLoaded() }
        eventually("première page") { list.items.size == 20 && !list.loading }
        assertTrue(list.hasMore)
        onMain { flow.loadMore(); flow.loadMore() }
        eventually("deuxième page") { list.items.size == 40 && !list.loading }
        onMain { flow.loadMore() }
        eventually("troisième page") { list.items.size == 60 && !list.loading }
        assertFalse(onMain { list.hasMore })
        assertEquals(60, onMain { list.items.map { it.id }.distinct().size })
        assertTrue(onMain { list.items.any { it.picture } })

        // Recherche temporisée : trois frappes rapides, une seule recherche, celle du dernier texte.
        onMain { flow.search("so"); flow.search("sou"); flow.search("soupe") }
        assertEquals(60, onMain { list.items.size })
        eventually("recherche") { list.query == "soupe" && !list.loading && list.items.size in 1..59 }
        assertTrue(onMain { list.items.all { it.title.startsWith("Soupe") } })
        onMain { flow.search(""); flow.filter(RecipeChip.FAVORITES) }
        eventually("favoris") { list.filter == RecipeChip.FAVORITES && !list.loading && list.items.isNotEmpty() }
        assertTrue(onMain { list.items.all { it.favorite } })
        onMain { flow.filter(RecipeChip.ALL) }
        eventually("toutes") { !list.loading && list.items.size == 20 }

        // Une fiche inconnue : erreur affichée.
        onMain { flow.openRecipe("absente") }
        eventually("fiche absente") { fiche?.error != null }
        onMain { nav.back() }

        // Fiche, envoi, reçue sur le robot.
        onMain { flow.openRecipe("essai-3") }
        assertEquals(Route.FICHE, onMain { nav.current })
        eventually("fiche") { fiche?.loading == false && fiche?.stepCount == 2 }
        assertEquals(SendStatus.READY, onMain { fiche!!.send })
        assertEquals("À la main", onMain { fiche!!.steps[0].detail })
        assertEquals("Mélanger.", onMain { fiche!!.steps[1].text })
        onMain { flow.sendToRobot("essai-3") }
        eventually("envoyée") { nav.current == Route.ENVOYEE && sent?.receivedAt != null }
        // Envoyée a pris la place de la fiche : le retour mène au catalogue.
        assertEquals(listOf(Route.RECETTES, Route.ENVOYEE), onMain { nav.stack(Tab.RECIPES) })
        assertEquals("essai-3", robot.hub.waitingRecipe!!.recipeId)

        // Retirée depuis le téléphone.
        onMain { flow.withdraw() }
        eventually("retirée") { robot.hub.waitingRecipe == null && nav.current == Route.RECETTES }
        assertTrue(onMain { sent!!.removed })

        // Envoyée de nouveau, puis lancée sur le robot : « En direct » prend la main.
        onMain { flow.openRecipe("essai-3") }
        eventually("fiche 2") { fiche?.loading == false }
        onMain { flow.sendToRobot("essai-3") }
        eventually("envoyée 2") { nav.current == Route.ENVOYEE && sent?.receivedAt != null && !sent!!.removed }
        val r = robot.recipes.first { it.id == "essai-3" }
        robot.hub.clearWaiting(WaitingEnd.LAUNCHED)
        robot.hub.updateSession(CookSession.of(r, 0, waitingFor = WaitingFor.HAND))
        eventually("lancée") { nav.current.tab == Tab.LIVE }
        assertEquals(listOf(Route.RECETTES), onMain { nav.stack(Tab.RECIPES) })

        // Une autre recette envoyée pendant la cuisson : la fiche dit qu'elle remplacera la précédente.
        onMain { flow.openRecipe("essai-4") }
        eventually("fiche 3") { fiche?.loading == false }
        onMain { flow.sendToRobot("essai-4") }
        eventually("envoyée 3") { robot.hub.waitingRecipe?.recipeId == "essai-4" }
        onMain { flow.openRecipe("essai-5") }
        eventually("note de remplacement") { fiche?.loading == false && fiche?.sendNote?.contains("n° 4") == true }
        assertTrue(notices.isEmpty())
        onMain { flow.close() }
    }

    // ------------------------------------------------------------------ journal

    /** Les pastilles du robot : sa liste, chacune donne ce que montrerait le robot, pages comprises ; catégorie disparue. */
    @Test fun catalogueChipsLikeTheRobot() {
        val robot = robot()
        val link = paired(robot)
        val nav = FakeNav()
        var list = RecettesState()
        val flow = onMain { CatalogFlow(link, nav, { list = it }, {}, {}, schedule, pageSize = 5) }
        // Avant la réponse du robot : les quatre pastilles fixes.
        assertEquals(RecipeChip.FIXED, onMain { flow.list.filters })
        onMain { link.follow("test"); nav.select(Tab.RECIPES); flow.ensureLoaded() }
        eventually("pastilles du robot") { !list.loading && list.filters.size > 4 }
        val index = SearchIndex(robot.recipes)
        assertEquals(RecipeChip.list(index), onMain { list.filters })
        assertEquals(listOf("Toutes", "Récentes", "Fréquentes", "Favoris", "30 min max", "Desserts", "Pâtes", "Plats", "Soupes"),
            onMain { list.filters.map { it.label } })

        // Une catégorie, page après page : le filtre suit, la liste est celle du robot.
        val desserts = RecipeChip.category("Desserts")
        onMain { flow.filter(desserts) }
        eventually("desserts") { list.filter == desserts && !list.loading && list.items.size == 5 }
        while (onMain { list.hasMore }) {
            val n = onMain { list.items.size }
            onMain { flow.loadMore() }
            eventually("page suivante") { !list.loading && list.items.size > n }
        }
        assertEquals(RecipeChip.select(index, desserts, "").map { it.entry.id }, onMain { list.items.map { it.id } })
        assertTrue(onMain { list.items.all { it.category == "Desserts" } })
        // Texte et pastille ensemble ; puis « 30 min max ».
        // (La recherche est temporisée : la liste des desserts reste affichée jusqu'au rechargement.)
        val expected = RecipeChip.select(index, desserts, "crème 3").map { it.entry.id }.take(5)
        assertTrue(expected.isNotEmpty())
        onMain { flow.search("crème 3") }
        eventually("desserts et texte") { !list.loading && list.error == null && list.items.map { it.id } == expected }
        onMain { flow.search(""); flow.filter(RecipeChip.QUICK) }
        eventually("30 min max") { list.filter == RecipeChip.QUICK && list.query.isEmpty() && !list.loading && list.items.isNotEmpty() }
        assertEquals(RecipeChip.select(index, RecipeChip.QUICK, "").map { it.entry.id }.take(5), onMain { list.items.map { it.id } })

        // La catégorie choisie disparaît du catalogue : retour à « Toutes » au rechargement.
        onMain { flow.filter(desserts) }
        eventually("desserts encore") { list.filter == desserts && !list.loading }
        robot.index = SearchIndex(robot.recipes.filter { it.category != "Desserts" })
        onMain { flow.reload() }
        eventually("retour à Toutes") { list.filter == RecipeChip.ALL && !list.loading && list.items.isNotEmpty() }
        assertFalse(onMain { desserts in list.filters })
        assertTrue(onMain { RecipeChip.category("Soupes") in list.filters })
    }

    /** Robot plus ancien : ni liste de pastilles, ni filtres nouveaux (400). Le téléphone garde les quatre fixes. */
    @Test fun olderRobotKeepsTheFourFixedChips() {
        val robot = robot()
        val api = CompanionApi(robot.hub)
        val old = HttpServer(object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse {
                if (request.path == "/api/recipes") {
                    val f = request.query["filter"]
                    if (request.query.containsKey("category") || (f != null && f !in setOf("", "all", "recent", "frequent", "favorites")))
                        return ApiError.response(400, "bad_request", "Invalid parameter: filter")
                    val r = api.handle(request)
                    if (r.status != 200) return r
                    @Suppress("UNCHECKED_CAST")
                    val m = Json.parse(String(r.body, Charsets.UTF_8)) as MutableMap<String, Any?>
                    m.remove("filters")
                    return HttpResponse.json(200, m)
                }
                return api.handle(request)
            }
        }, 0, InetAddress.getByName("127.0.0.1"))
        old.start()
        try {
            val link = link(listOf(old.boundPort))
            val code = robot.hub.pairing.open().code
            val done = CountDownLatch(1)
            link.pairWithCode(code) { done.countDown() }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            val nav = FakeNav()
            var list = RecettesState()
            val flow = onMain { CatalogFlow(link, nav, { list = it }, {}, {}, schedule) }
            onMain { link.follow("test"); nav.select(Tab.RECIPES); flow.ensureLoaded() }
            eventually("première page") { !list.loading && list.items.isNotEmpty() }
            assertEquals(RecipeChip.FIXED, onMain { list.filters })
            onMain { flow.filter(RecipeChip.FAVORITES) }
            eventually("favoris") { list.filter == RecipeChip.FAVORITES && !list.loading && list.items.isNotEmpty() }
            assertTrue(onMain { list.items.all { it.favorite } })
            // Une pastille qu'il ne connaît pas (téléphone resté sur un robot mis à jour puis remis) : retour à « Toutes ».
            onMain { flow.filter(RecipeChip.category("Soupes")) }
            eventually("retour à Toutes") { list.filter == RecipeChip.ALL && !list.loading && list.error == null && list.items.isNotEmpty() }
            assertEquals(RecipeChip.FIXED, onMain { list.filters })
        } finally {
            old.stop()
        }
    }

    /** Nombre de personnes depuis la fiche : enregistré sur le robot, confirmé une fois, suivi par l'envoi ; refus ; robot plus ancien. */
    @Test fun servingsFromTheFiche() {
        val robot = robot()
        val link = paired(robot)
        val nav = FakeNav()
        var fiche: FicheState? = null
        var sent: EnvoyeeState? = null
        val notices = ArrayList<String>()
        val flow = onMain { CatalogFlow(link, nav, {}, { fiche = it }, { sent = it }, schedule, notice = { notices += it }) }
        onMain { link.follow("test") }
        eventually("connecté") { link.status.phase == LinkPhase.CONNECTED }
        val id = robot.recipe.id

        onMain { nav.select(Tab.RECIPES); flow.openRecipe(id) }
        eventually("fiche") { fiche?.loading == false && fiche?.baseServings != null }
        assertEquals(4, onMain { fiche!!.servings })
        assertEquals(2, onMain { fiche!!.minServings })
        assertEquals(8, onMain { fiche!!.maxServings })
        // Quitter le nombre de la recette se confirme ; ensuite, non ; y revenir, non plus.
        assertTrue(onMain { fiche!!.servingsNeedConfirm(6) })
        assertFalse(onMain { fiche!!.servingsNeedConfirm(4) })
        // Affiché pendant l'appui, enregistré ensuite par le robot.
        assertEquals(6, onMain { flow.setServings(id, 6); fiche!!.servings })
        eventually("6 personnes") { robot.servings[id] == 6 }
        assertEquals(6, onMain { fiche!!.servings })
        assertFalse(onMain { fiche!!.servingsNeedConfirm(7) })
        assertTrue(onMain { fiche!!.servingsNeedConfirm(4).not() })
        // La fiche rechargée garde le nombre (il est sur le robot) ; quantités du robot pour 6.
        onMain { flow.openRecipe(id) }
        eventually("fiche rechargée") { fiche?.loading == false && fiche?.servings == 6 }
        assertTrue(onMain { fiche!!.steps[1].detail }.contains("30"))

        // « Envoyer au robot » : la recette attend au nombre choisi ; un autre nombre la suit.
        onMain { flow.sendToRobot(id) }
        eventually("envoyée") { nav.current == Route.ENVOYEE && sent?.receivedAt != null }
        assertEquals(6, robot.hub.waitingRecipe!!.servings)
        assertEquals(6, onMain { sent!!.servings })
        onMain { nav.back(); flow.openRecipe(id) }
        eventually("fiche 3") { fiche?.loading == false }
        onMain { flow.setServings(id, 5) }
        eventually("5 personnes") { fiche?.servings == 5 && sent?.servings == 5 }
        assertEquals(5, robot.hub.waitingRecipe!!.servings)
        // Changé sur le robot : l'écran Envoyée suit par l'état.
        robot.hub.recipeServingsChanged(id, 3)
        eventually("3 personnes par l'état") { sent?.servings == 3 }

        // Recette en cours sur le robot : il refuserait, le réglage est inactif et rien ne part.
        robot.hub.updateSession(app.toque.core.companion.CookSession.of(robot.recipe, 0))
        eventually("réglage inactif") { fiche?.servingsLocked == true }
        val asked = robot.servingsRequests.size
        onMain { flow.setServings(id, 8) }
        assertEquals(5, onMain { fiche!!.servings })
        robot.hub.endSession(false)
        eventually("réglage actif") { fiche?.servingsLocked == false }
        assertEquals(asked, robot.servingsRequests.size)
        // Retour au nombre de la recette.
        onMain { flow.setServings(id, 4) }
        eventually("4 personnes") { robot.servings[id] == null && robot.servingsRequests.size == asked + 1 }
        assertEquals(4, onMain { fiche!!.servings })
    }

    /** Robot plus ancien : la fiche ne porte pas le réglage, le téléphone affiche seulement « N pers. ». */
    @Test fun olderRobotHasNoServingsStepper() {
        val robot = robot()
        val api = CompanionApi(robot.hub)
        val old = HttpServer(object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse {
                val r = api.handle(request)
                if (!request.path.startsWith("/api/recipes/") || r.status != 200 || r.contentType.startsWith("image")) return r
                @Suppress("UNCHECKED_CAST")
                val m = Json.parse(String(r.body, Charsets.UTF_8)) as MutableMap<String, Any?>
                for (k in listOf("baseServings", "minServings", "maxServings", "totalGrams", "bowlTooFull")) m.remove(k)
                return HttpResponse.json(200, m)
            }
        }, 0, InetAddress.getByName("127.0.0.1"))
        old.start()
        try {
            val link = link(listOf(old.boundPort))
            val done = CountDownLatch(1)
            link.pairWithCode(robot.hub.pairing.open().code) { done.countDown() }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            val nav = FakeNav()
            var fiche: FicheState? = null
            val flow = onMain { CatalogFlow(link, nav, {}, { fiche = it }, {}, schedule) }
            onMain { link.follow("test"); nav.select(Tab.RECIPES); flow.openRecipe(robot.recipe.id) }
            eventually("fiche") { fiche?.loading == false && fiche?.error == null }
            assertEquals(4, onMain { fiche!!.servings })
            assertNull(onMain { fiche!!.baseServings })
            // Sans réglage, rien n'est envoyé.
            onMain { flow.setServings(robot.recipe.id, 6) }
            assertEquals(4, onMain { fiche!!.servings })
            Thread.sleep(500)
            assertTrue(robot.servingsRequests.isEmpty())
        } finally {
            old.stop()
        }
    }

    /**
     * Le cas du propriétaire (0.3.0) : la liaison suit déjà le robot (service permanent) et tient un
     * état quand les flux sont créés (activité recréée), robot au repos, donc aucun état nouveau. Les
     * flux apprennent quand même ce que la liaison sait : envoi permis, version du robot, pastille.
     */
    @Test fun flowsCreatedAfterTheLinkKnowsTheRobotUseWhatItKnows() {
        val robot = robot()
        val link = paired(robot)
        onMain { link.follow("service") }
        eventually("connecté, robot au repos") { link.status.phase == LinkPhase.CONNECTED && link.latest?.machine?.link == "READY" }
        Thread.sleep(500)
        val revision = link.latest!!.revision

        val nav = FakeNav()
        var fiche: FicheState? = null
        var robotState: RobotState? = null
        val switches = object : AlertSwitches {
            override fun enabled() = AlertKind.values().filter { it.defaultOn }.toSet()
            override fun set(kind: AlertKind, on: Boolean) {}
        }
        val catalog = onMain { CatalogFlow(link, nav, {}, { fiche = it }, {}, schedule) }
        val robotFlow = onMain { RobotFlow(link, switches, { robotState = it }, notificationsAllowed = { false }) { _, _ -> } }
        // La liaison a redit son état par le fil principal, avant tout ce qui suit.
        onMain { assertEquals("0.7.0", robotState?.version) }
        onMain { nav.select(Tab.RECIPES); catalog.openRecipe("essai-3") }
        eventually("fiche chargée") { fiche?.loading == false && fiche?.id == "essai-3" }
        val f = onMain { fiche!! }
        assertEquals(SendStatus.READY, f.send)
        assertNull(f.sendNote)
        // Rien n'a changé sur le robot : c'est bien l'état déjà connu qui a servi.
        assertEquals(revision, link.latest!!.revision)
        onMain { catalog.close(); robotFlow.close() }

        // Robot injoignable, puis l'onglet Journal créé : sa pastille le dit tout de suite.
        robot.hub.stop()
        eventually("injoignable") { link.status.phase == LinkPhase.UNREACHABLE }
        var journal = JournalState()
        val journalFlow = onMain { JournalFlow(link, { journal = it }) }
        onMain { assertEquals(app.toque.companion.ui.Link.OFFLINE, journal.link) }
        onMain { journalFlow.close() }
    }

    @Test fun journalAfterShortCookings() {
        val robot = robot()
        val link = paired(robot)
        var state = JournalState()
        val flow = onMain { JournalFlow(link, { state = it }) }
        onMain { link.follow("test") }
        eventually("connecté") { link.status.phase == LinkPhase.CONNECTED && link.latest?.machine?.link == "READY" }

        assertTrue(robot.loop.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 2_000)) }.ok)
        eventually("cuisson finie") { link.latest?.last?.outcome == "finished" }
        robot.loop.post { it.clearFinished() }
        assertTrue(robot.loop.call { it.startJob(Programs.knead()) }.ok)
        eventually("pétrissage") { link.latest?.job?.state == "RUNNING" }
        val stopped = CountDownLatch(1)
        onMain { link.stop { stopped.countDown() } }
        assertTrue(stopped.await(10, TimeUnit.SECONDS))
        eventually("arrêté") { link.latest?.last?.outcome == "stopped" }

        onMain { flow.setVisible(true) }
        eventually("journal") { state.days.flatMap { it.entries }.size == 2 }
        val day = onMain { state.days.single() }
        assertEquals("Aujourd'hui", day.title)
        val (knead, manual) = day.entries
        assertEquals(JournalKind.KNEAD, knead.kind)
        assertEquals(JournalOutcome.STOPPED, knead.outcome)
        // La phrase du robot sur l'arrêt, telle quelle.
        assertTrue(knead.note!!, knead.note!!.startsWith("Arrêté depuis"))
        assertEquals(JournalKind.MANUAL, manual.kind)
        assertEquals(JournalOutcome.FINISHED, manual.outcome)
        assertNull(manual.note)
        assertTrue(manual.detail, Regex("\\d\\d:\\d\\d – \\d\\d:\\d\\d.*").matches(manual.detail))

        onMain { flow.filter(JournalFilter.PROGRAMS) }
        assertEquals(listOf(JournalKind.KNEAD), onMain { state.days.flatMap { it.entries }.map { it.kind } })
        onMain { flow.filter(JournalFilter.RECIPES) }
        assertTrue(onMain { state.days.isEmpty() })

        // Le journal bouge pendant qu'il est affiché : il se recharge.
        assertTrue(robot.loop.call { it.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 60_000)) }.ok)
        onMain { flow.filter(JournalFilter.ALL) }
        eventually("en cours") { state.days.flatMap { it.entries }.any { it.outcome == JournalOutcome.RUNNING } }
        onMain { flow.close() }
    }

    // ------------------------------------------------------------------ robot

    @Test fun robotTabAndForget() {
        val robot = robot()
        val link = paired(robot)
        val switches = object : AlertSwitches {
            val on = AlertKind.values().filter { it.defaultOn }.toMutableSet()
            override fun enabled() = on.toSet()
            override fun set(kind: AlertKind, on: Boolean) { if (on) this.on += kind else this.on -= kind }
        }
        var state: RobotState? = null
        var forgotten: Boolean? = null
        val flow = onMain { RobotFlow(link, switches, { state = it }, notificationsAllowed = { false }) { ok, _ -> forgotten = ok } }
        onMain { link.follow("test") }
        eventually("état du robot") { state?.version == "0.7.0" }
        val s = onMain { state!! }
        assertEquals("Monsieur Cuisine Connect", s.robotName)
        assertEquals(PairMethod.CODE, s.pairMethod)
        assertTrue(s.pairedOn.isNotEmpty())
        assertEquals(setOf(ScreenAlert.WAITING, ScreenAlert.STEP_END, ScreenAlert.PAUSE, ScreenAlert.TEMP), s.alerts)
        assertFalse(s.notificationsAllowed)

        onMain { flow.setAlert(ScreenAlert.OFFLINE, true); flow.setAlert(ScreenAlert.PAUSE, false) }
        assertEquals(setOf(AlertKind.WAITING, AlertKind.END, AlertKind.OFFLINE, AlertKind.TEMP), switches.on)
        assertEquals(setOf(ScreenAlert.WAITING, ScreenAlert.STEP_END, ScreenAlert.OFFLINE, ScreenAlert.TEMP), onMain { state!!.alerts })

        onMain { flow.forgetPhone() }
        assertTrue(onMain { state!!.forgetting })
        eventually("oublié") { forgotten != null }
        assertEquals(true, forgotten)
        assertEquals(LinkPhase.NOT_PAIRED, link.status.phase)
        assertNull(link.paired)
        assertTrue(robot.hub.pairing.list.isEmpty())
        assertNotNull(s)
        onMain { flow.close() }
    }
}
