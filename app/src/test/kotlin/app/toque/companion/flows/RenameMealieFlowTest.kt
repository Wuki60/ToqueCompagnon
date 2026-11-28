package app.toque.companion.flows

import app.toque.companion.alerts.AlertKind
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.RecettesState
import app.toque.companion.ui.screens.RobotState
import app.toque.companion.ui.screens.myVersionText
import app.toque.companion.ui.screens.recipeSubtitle
import app.toque.core.companion.ApiError
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import app.toque.core.companion.PhoneWire
import app.toque.core.json.Json
import app.toque.core.recipes.RecipeChip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ce téléphone se renomme depuis l'onglet Robot, et les recettes Mealie du robot : de bout en bout sur la
 * JVM, un vrai hub sur base simulée et la vraie liaison ; un robot plus ancien simulé par un relais qui
 * retire ce qu'il ne connaissait pas.
 */
class RenameMealieFlowTest {
    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val robots = ArrayList<SimRobot>()
    private val links = ArrayList<RobotLink>()
    private val servers = ArrayList<HttpServer>()
    private val schedule: Scheduler = { ms, r -> val f = main.schedule(r, ms, TimeUnit.MILLISECONDS); { f.cancel(false) } }

    @After fun tearDown() {
        links.forEach { it.close() }
        servers.forEach { it.stop() }
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

    private fun robot() = SimRobot().start().also { robots += it }

    private fun paired(robot: SimRobot, port: Int = robot.port): RobotLink {
        val l = RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(port), waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500).also { links += it }
        val done = CountDownLatch(1)
        var r: PairResult? = null
        l.pairWithCode(robot.hub.pairing.open().code) { r = it; done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue("$r", r is PairResult.Paired)
        return l
    }

    /**
     * Relais devant [robot]. [older] : un robot d'avant ces changements, ni `can.renamePhone`, ni `source` sur
     * une fiche, et POST /api/phones/me répond 405. [refuseRename] : le robot refuse le nouveau nom (409).
     */
    private fun relay(robot: SimRobot, older: Boolean = false, refuseRename: Boolean = false): Int {
        val api = CompanionApi(robot.hub)
        val old = HttpServer(object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse {
                if (request.path == "/api/phones/me" && request.method == "POST") {
                    if (older) return ApiError.response(405, "method_not_allowed", "Method not allowed")
                    if (refuseRename) return ApiError.response(409, "busy", "Réessayez dans un instant")
                }
                val r = api.handle(request)
                if (!older || r.status != 200) return r
                @Suppress("UNCHECKED_CAST")
                val m = try {
                    Json.parse(String(r.body, Charsets.UTF_8)) as? MutableMap<String, Any?>
                } catch (_: Exception) {
                    null
                } ?: return r
                when {
                    request.path == "/api/state" -> {
                        @Suppress("UNCHECKED_CAST")
                        (m["can"] as? MutableMap<String, Any?>)?.remove("renamePhone")
                    }
                    request.path.startsWith("/api/recipes/") -> m.remove("source")
                    else -> return r
                }
                return HttpResponse.json(200, m)
            }
        }, 0, InetAddress.getByName("127.0.0.1"))
        old.start()
        servers += old
        return old.boundPort
    }

    private val switches = object : AlertSwitches {
        override fun enabled() = AlertKind.values().filter { it.defaultOn }.toSet()
        override fun set(kind: AlertKind, on: Boolean) {}
    }

    // ------------------------------------------------------------------ renommer ce téléphone

    /** Le nom par défaut montré dans « Renommer » est celui du robot, écrit dans sa langue, pas dans celle du téléphone. */
    @Test fun theDefaultNameIsTheRobotsOwn() {
        // Un robot en allemand, un téléphone déjà nommé : le robot dit le nom qu'il lui rendrait.
        assertEquals("Telefon 2", phoneDefaultName(PhoneWire(id = "a", number = 2, name = "Cuisine", label = "Cuisine", defaultName = "Telefon 2")))
        // Sans nom donné, son nom est déjà celui-là.
        assertEquals("Telefon 2", phoneDefaultName(PhoneWire(id = "a", number = 2, name = "Telefon 2")))
        // Un robot plus ancien ne le dit pas pour un téléphone nommé : « Téléphone N », dans la langue d'ici.
        assertEquals("Téléphone 2", phoneDefaultName(PhoneWire(id = "a", number = 2, name = "Cuisine", label = "Cuisine")))
        assertEquals("", phoneDefaultName(null))
    }

    @Test fun thisPhoneRenamesItselfFromTheRobotTab() {
        val robot = robot()
        val link = paired(robot)
        var state: RobotState? = null
        val notices = ArrayList<String>()
        val flow = onMain { RobotFlow(link, switches, { state = it }) { _, _ -> }.also { it.notice = { t -> notices += t } } }
        onMain { link.follow("test") }
        eventually("état du robot") { state?.phoneName == "Téléphone 1" }
        val s = onMain { state!! }
        assertTrue("le robot le permet", s.canRename)
        assertEquals("", s.phoneLabel)
        assertEquals("Téléphone 1", s.phoneDefault)

        // Un nom tapé : nettoyé par le robot, montré dès sa réponse, puis par l'état suivant.
        onMain { flow.renamePhone("  Cuisine\tde   Léa ") }
        assertTrue(onMain { state!!.renaming })
        eventually("renommé") { state?.phoneName == "Cuisine de Léa" && state?.renaming == false }
        assertEquals("Cuisine de Léa", onMain { state!!.phoneLabel })
        // Le nom qu'il reprendrait, tel que le robot le donne.
        eventually("nom par défaut") { state?.phoneDefault == "Téléphone 1" }
        assertEquals("Cuisine de Léa", robot.hub.pairing.list.single().name)
        eventually("l'état du robot le porte") { link.latest?.me?.name == "Cuisine de Léa" }
        assertEquals("Cuisine de Léa", onMain { state!!.phoneName })

        // Vide : le nom par défaut revient.
        onMain { flow.renamePhone("") }
        eventually("nom par défaut") { state?.phoneName == "Téléphone 1" && state?.renaming == false }
        assertEquals("", onMain { state!!.phoneLabel })

        // Renommé sur le robot : l'onglet suit (nouvelle révision).
        robot.hub.renamePhone(robot.hub.pairing.list.single().id, "Tablette du salon")
        eventually("nom donné sur le robot") { state?.phoneName == "Tablette du salon" }
        assertTrue(notices.isEmpty())

        onMain { flow.close() }
    }

    /** Le robot met ce téléphone en lecture seule (Réglages › Téléphone) : l'onglet Robot le dit, et plus de « Renommer » refusé. */
    @Test fun aReadOnlyPhoneIsToldOnTheRobotTab() {
        val robot = robot()
        val link = paired(robot)
        var state: RobotState? = null
        val flow = onMain { RobotFlow(link, switches, { state = it }) { _, _ -> } }
        onMain { link.follow("test") }
        eventually("état du robot") { state?.phoneName == "Téléphone 1" }
        assertFalse(onMain { state!!.readOnly })

        robot.hub.setPhoneReadOnly(robot.hub.pairing.list.single().id, true)
        eventually("lecture seule") { state?.readOnly == true }
        // Les commandes sont refusées par le robot : le téléphone les montre coupées, avec la raison du robot.
        eventually("commandes coupées") { link.latest?.can?.start?.code == "read_only" }

        robot.hub.setPhoneReadOnly(robot.hub.pairing.list.single().id, false)
        eventually("commandes rendues") { state?.readOnly == false }
        onMain { flow.close() }
    }

    @Test fun aRefusalIsShownAndTheNameStays() {
        val robot = robot()
        val link = paired(robot, relay(robot, refuseRename = true))
        var state: RobotState? = null
        val notices = ArrayList<String>()
        val flow = onMain { RobotFlow(link, switches, { state = it }) { _, _ -> }.also { it.notice = { t -> notices += t } } }
        onMain { link.follow("test") }
        eventually("état du robot") { state?.phoneName == "Téléphone 1" }
        onMain { flow.renamePhone("Cuisine") }
        eventually("refus") { notices.isNotEmpty() && state?.renaming == false }
        // Le message du robot, tel quel ; le nom ne change ni ici ni sur le robot.
        assertEquals(listOf("Réessayez dans un instant"), onMain { notices.toList() })
        assertEquals("Téléphone 1", onMain { state!!.phoneName })
        assertEquals("Téléphone 1", robot.hub.pairing.list.single().name)
        assertEquals(LinkPhase.CONNECTED, link.status.phase)
        onMain { flow.close() }
    }

    @Test fun anOlderRobotOffersNoRenaming() {
        val robot = robot()
        val link = paired(robot, relay(robot, older = true))
        var state: RobotState? = null
        val notices = ArrayList<String>()
        val flow = onMain { RobotFlow(link, switches, { state = it }) { _, _ -> }.also { it.notice = { t -> notices += t } } }
        onMain { link.follow("test") }
        eventually("état du robot") { state?.phoneName == "Téléphone 1" }
        assertFalse(onMain { state!!.canRename })
        // Même appelé, rien ne part vers ce robot.
        onMain { flow.renamePhone("Cuisine") }
        assertFalse(onMain { state!!.renaming })
        Thread.sleep(200)
        assertEquals("Téléphone 1", robot.hub.pairing.list.single().name)
        assertTrue(notices.isEmpty())
        onMain { flow.close() }
    }

    // ------------------------------------------------------------------ Mealie

    @Test fun mealieChipAndSheetLikeTheRobot() {
        val robot = robot()
        val m = robot.addMealie()
        val link = paired(robot)
        val nav = FakeNav()
        var list = RecettesState()
        var fiche: FicheState? = null
        val flow = onMain { CatalogFlow(link, nav, { list = it }, { fiche = it }, {}, schedule, pageSize = 10) }
        onMain { link.follow("test"); nav.select(Tab.RECIPES); flow.ensureLoaded() }
        eventually("pastilles du robot") { !list.loading && list.filters.size > 4 }
        // La pastille « Mealie » envoyée par le robot est montrée (un téléphone plus ancien la sautait).
        assertTrue(onMain { RecipeChip.MEALIE in list.filters })
        assertEquals("Mealie", RecipeChip.MEALIE.label)
        onMain { flow.filter(RecipeChip.MEALIE) }
        eventually("recettes Mealie") { list.filter == RecipeChip.MEALIE && !list.loading && list.items.isNotEmpty() }
        assertEquals(listOf(m.id), onMain { list.items.map { it.id } })

        // La fiche : « Depuis Mealie », et « Ma version » dit que la recette reste telle quelle dans Mealie.
        onMain { flow.openRecipe(m.id) }
        eventually("fiche chargée") { fiche?.loading == false && fiche?.id == m.id }
        val f = onMain { fiche!! }
        assertTrue(f.mealie)
        assertFalse(f.personal)
        assertFalse(f.community)
        assertTrue("« Ma version » proposée", f.canWrite)
        assertEquals("Depuis Mealie · 2 étapes", recipeSubtitle(f.personal, f.category, f.stepCount, f.mealie))
        assertTrue(myVersionText(f).endsWith("La recette reste telle quelle dans Mealie."))

        // « Ma version » : une copie à soi, la recette Mealie inchangée.
        val done = CountDownLatch(1)
        var copy: Result<EditableRecipe>? = null
        link.copyRecipe(m.id) { copy = it; done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        val c = copy!!.getOrThrow()
        assertTrue(c.id != m.id)
        assertEquals(m, robot.mealie[m.id])

        // Une recette Lidl : ni l'un ni l'autre, le texte Lidl.
        onMain { flow.openRecipe("essai-3") }
        eventually("fiche Lidl") { fiche?.loading == false && fiche?.id == "essai-3" }
        val lidl = onMain { fiche!! }
        assertFalse(lidl.mealie)
        assertTrue(myVersionText(lidl).endsWith("La recette Lidl reste telle quelle."))
        onMain { flow.close() }
    }

    @Test fun anOlderRobotDoesNotSayWhereARecipeComesFrom() {
        val robot = robot()
        val m = robot.addMealie()
        val link = paired(robot, relay(robot, older = true))
        val nav = FakeNav()
        var fiche: FicheState? = null
        val flow = onMain { CatalogFlow(link, nav, {}, { fiche = it }, {}, schedule) }
        onMain { link.follow("test") }
        eventually("connecté") { link.status.phase == LinkPhase.CONNECTED }
        onMain { flow.openRecipe(m.id) }
        eventually("fiche chargée") { fiche?.loading == false && fiche?.id == m.id }
        // Sans le champ : comme avant, la catégorie en sous-titre et le texte Lidl.
        assertFalse(onMain { fiche!!.mealie })
        assertEquals("Desserts · 2 étapes", onMain { recipeSubtitle(fiche!!.personal, fiche!!.category, fiche!!.stepCount, fiche!!.mealie) })
        onMain { flow.close() }
    }
}
