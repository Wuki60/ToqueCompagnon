package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Route
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.SendStatus
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import app.toque.core.companion.ServingsChange
import app.toque.core.companion.Wires
import app.toque.core.json.Json
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.forServings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Nombre de personnes sur la fiche, de bout en bout contre le robot simulé : affiché pendant
 * l'appui, envoyé une fois après le dernier, confirmé ou refusé par le robot ; robot plus ancien.
 */
class ServingsFlowTest {
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
            Thread.sleep(20)
        }
        throw AssertionError("délai dépassé : $what")
    }

    /** La fiche et tout ce qui a été dessiné. */
    private inner class Phone(robot: SimRobot, port: Int = robot.port) {
        val link = RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(port), waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500).also { links += it }
        val nav = FakeNav()
        val drawn = CopyOnWriteArrayList<FicheState>()
        val notices = CopyOnWriteArrayList<String>()
        val flow: CatalogFlow

        init {
            val done = CountDownLatch(1)
            var r: PairResult? = null
            link.pairWithCode(robot.hub.pairing.open().code) { r = it; done.countDown() }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertTrue("$r", r is PairResult.Paired)
            flow = onMain { CatalogFlow(link, nav, {}, { drawn += it }, {}, schedule, notice = { notices += it }) }
            onMain { link.follow("test"); nav.select(Tab.RECIPES) }
            eventually("connecté") { link.status.phase == LinkPhase.CONNECTED && flow.fiche == null }
        }

        val fiche: FicheState get() = flow.fiche!!

        fun open(id: String) {
            onMain { flow.openRecipe(id) }
            eventually("fiche $id") { flow.fiche?.id == id && !flow.fiche!!.loading }
        }
    }

    private fun robot() = SimRobot().start().also { robots += it }

    /** Ce que la fiche montrerait avec la réponse du robot pour [n] personnes. */
    private fun robotSheet(robot: SimRobot, n: Int): FicheState =
        CatalogFlow.fiche(Wires.recipe(robot.base(robot.recipe.id)!!.forServings(n), robot.recipe.servings))

    private fun FicheState.content() = listOf(servings, ingredients, steps, totalGrams, bowlTooFull, quantitiesPending)

    /**
     * Un robot plus ancien : il ignore ?base=1 (répond au nombre choisi) et n'envoie pas la langue.
     * Relais devant le vrai hub, comme dans FlowsEndToEndTest.
     */
    private fun older(robot: SimRobot): Int {
        val api = CompanionApi(robot.hub)
        val old = HttpServer(object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse {
                val req = HttpRequest(request.method, request.path, request.query - "base", request.headers, request.body, request.remote,
                    request.local, request.segments)
                val r = api.handle(req)
                if (!request.path.startsWith("/api/recipes/") || r.status != 200 || r.contentType.startsWith("image")) return r
                @Suppress("UNCHECKED_CAST")
                val m = Json.parse(String(r.body, Charsets.UTF_8)) as MutableMap<String, Any?>
                m.remove("language")
                return HttpResponse.json(200, m)
            }
        }, 0, InetAddress.getByName("127.0.0.1"))
        old.start()
        servers += old
        return old.boundPort
    }

    @Test fun drawnDuringTheTapThenConfirmedWithoutRedrawing() {
        val robot = robot()
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        val before = p.drawn.size
        // Dans le même passage sur le fil principal : aucun rappel de la liaison n'a pu arriver.
        val shown = onMain {
            p.flow.setServings(id, 6)
            p.fiche
        }
        assertEquals(robotSheet(robot, 6).content(), shown.content())
        assertEquals("1 ½", shown.ingredients[0].quantity)
        assertEquals("0,75 l", shown.ingredients[3].quantity)
        assertTrue(robot.servingsRequests.isEmpty())
        eventually("enregistré") { robot.servings[id] == 6 }
        Thread.sleep(300)
        // La réponse confirme ce qui était affiché : rien de redessiné.
        assertEquals(before + 1, p.drawn.size)
        assertEquals(listOf(6), robot.servingsRequests.toList())
    }

    @Test fun tenQuickTapsSendTheLastNumberOnce() {
        val robot = robot()
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        val taps = listOf(5, 6, 7, 8, 7, 6, 5, 6, 7, 3)
        for (n in taps) {
            assertEquals(n, onMain { p.flow.setServings(id, n); p.fiche.servings })
            Thread.sleep(25)
        }
        eventually("3 sur le robot") { robot.servings[id] == 3 }
        Thread.sleep(500)
        assertTrue("${robot.servingsRequests}", robot.servingsRequests.size in 1..2)
        assertEquals(3, robot.servingsRequests.last())
        assertEquals(robotSheet(robot, 3).content(), onMain { p.fiche.content() })
    }

    @Test fun anAnswerForAnOldNumberIsNotDrawn() {
        val robot = robot()
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        robot.servingsDelayMs = 700
        onMain { p.flow.setServings(id, 6) }
        eventually("6 en route") { robot.servingsRequests.contains(6) }
        val mark = onMain {
            p.flow.setServings(id, 7)
            p.flow.setServings(id, 8)
            p.drawn.size
        }
        eventually("8 sur le robot", 10_000) { robot.servings[id] == 8 }
        Thread.sleep(300)
        val after = p.drawn.subList(mark, p.drawn.size)
        assertTrue(after.none { it.servings != 8 })
        assertEquals(listOf(6, 8), robot.servingsRequests.toList())
        assertEquals(robotSheet(robot, 8).content(), onMain { p.fiche.content() })
    }

    @Test fun aRefusalBringsBackTheConfirmedSheetAndItsMessage() {
        val robot = robot()
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        onMain { p.flow.setServings(id, 6) }
        eventually("6") { robot.servings[id] == 6 }
        robot.servingsRefusal = ServingsChange.cooking()
        assertEquals(7, onMain { p.flow.setServings(id, 7); p.fiche.servings })
        eventually("refus") { p.notices.isNotEmpty() }
        assertEquals("Recette en cours sur le robot", p.notices.last())
        assertEquals(robotSheet(robot, 6).content(), onMain { p.fiche.content() })
        assertEquals(6, robot.servings[id])
        // Ensuite, une autre demande repart normalement.
        robot.servingsRefusal = null
        onMain { p.flow.setServings(id, 5) }
        eventually("5") { robot.servings[id] == 5 }
    }

    @Test fun sendingWhileAChangeIsPendingSendsTheFinalNumberFirst() {
        val robot = robot()
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        onMain {
            p.flow.setServings(id, 6)
            p.flow.setServings(id, 7)
            p.flow.sendToRobot(id)
        }
        assertEquals(SendStatus.SENDING, onMain { p.fiche.send })
        eventually("envoyée") { p.nav.current == Route.ENVOYEE && robot.hub.waitingRecipe != null }
        assertEquals(7, robot.hub.waitingRecipe!!.servings)
        assertEquals(listOf(7), robot.servingsRequests.toList())

        // Refusé : la recette ne part pas, le message est montré.
        onMain { p.flow.withdraw() }
        eventually("retirée") { robot.hub.waitingRecipe == null }
        p.open(id)
        robot.servingsRefusal = ServingsChange.cooking()
        onMain {
            p.flow.setServings(id, 8)
            p.flow.sendToRobot(id)
        }
        eventually("refus") { p.notices.isNotEmpty() }
        Thread.sleep(300)
        assertNull(robot.hub.waitingRecipe)
        assertEquals(7, onMain { p.fiche.servings })
        assertEquals(SendStatus.READY, onMain { p.fiche.send })
    }

    @Test fun leavingTheSheetStillSavesTheNumber() {
        val robot = robot()
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        onMain {
            p.flow.setServings(id, 6)
            p.nav.back()
            p.flow.openRecipe("essai-1")
        }
        eventually("enregistré") { robot.servings[id] == 6 }
        eventually("autre fiche") { p.flow.fiche?.loading == false }
        Thread.sleep(200)
        assertEquals("essai-1", onMain { p.fiche.id })
        assertTrue(p.drawn.filter { it.id == "essai-1" }.none { it.servings == 6 })
        // Rouverte : au nombre enregistré.
        p.open(id)
        assertEquals(robotSheet(robot, 6).content(), onMain { p.fiche.content() })
    }

    @Test fun olderRobotShowsPendingQuantitiesThenItsOwn() {
        val robot = robot()
        val p = Phone(robot, older(robot))
        val id = robot.recipe.id
        // Ouverte à son propre nombre : instantané, même sans la langue (celle du téléphone).
        p.open(id)
        assertEquals(robotSheet(robot, 6).content(), onMain { p.flow.setServings(id, 6); p.fiche.content() })
        eventually("6") { robot.servings[id] == 6 }

        // Ouverte à un autre nombre (choisi plus tôt) sur un téléphone neuf : pas de fiche de base.
        val q = Phone(robot, older(robot))
        q.open(id)
        assertEquals(6, onMain { q.fiche.servings })
        Thread.sleep(300)
        val pending = onMain {
            q.flow.setServings(id, 7)
            q.fiche
        }
        assertEquals(7, pending.servings)
        assertTrue(pending.quantitiesPending)
        assertEquals(robotSheet(robot, 6).ingredients, pending.ingredients)
        eventually("quantités du robot") { !q.fiche.quantitiesPending }
        assertEquals(robotSheet(robot, 7).content(), onMain { q.fiche.content() })
        // Retour au nombre de la recette : sa réponse est la fiche de base, tout redevient instantané.
        onMain { q.flow.setServings(id, 4) }
        eventually("4") { robot.servings[id] == null && !q.fiche.quantitiesPending }
        assertEquals(robotSheet(robot, 5).content(), onMain { q.flow.setServings(id, 5); q.fiche.content() })
        assertEquals(0, robot.baseRequests.get())
    }

    @Test fun withBaseSupportASheetOpenedAtAnotherNumberBecomesInstant() {
        val robot = robot()
        robot.servings[robot.recipe.id] = 6
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        assertEquals(robotSheet(robot, 6).content(), onMain { p.fiche.content() })
        eventually("fiche de base") { robot.baseRequests.get() == 1 }
        Thread.sleep(200)
        val shown = onMain {
            p.flow.setServings(id, 8)
            p.fiche
        }
        assertFalse(shown.quantitiesPending)
        assertEquals(robotSheet(robot, 8).content(), shown.content())
        assertTrue(shown.bowlTooFull.not())
        eventually("8") { robot.servings[id] == 8 }
    }

    /** Corrections changées sur le robot après coup : la réponse corrige, la fiche de base est redemandée une fois. */
    @Test fun aStaleBaseSheetHealsItself() {
        val robot = robot()
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        assertEquals(0, robot.baseRequests.get())
        // Le beurre passe de 20 à 40 g sur le robot, dans le dos du téléphone.
        robot.corrections[id] = RecipeAdjustments().withQty(robot.recipe.allIngredients[1], "40")
        assertEquals("30 g", onMain { p.flow.setServings(id, 6); p.fiche.ingredients[1].quantity })
        eventually("corrigée par la réponse") { p.fiche.ingredients[1].quantity == "60 g" }
        assertEquals(robotSheet(robot, 6).content(), onMain { p.fiche.content() })
        eventually("fiche de base redemandée") { robot.baseRequests.get() == 1 }
        Thread.sleep(300)
        // Le tap suivant est juste tout de suite, et la réponse ne redemande rien.
        assertEquals(robotSheet(robot, 7).content(), onMain { p.flow.setServings(id, 7); p.fiche.content() })
        eventually("7") { robot.servings[id] == 7 }
        Thread.sleep(400)
        assertEquals(1, robot.baseRequests.get())
        assertEquals(robotSheet(robot, 7).content(), onMain { p.fiche.content() })
    }

    /** Ouverte à un autre nombre puis touchée avant l'arrivée de la fiche de base : une seule demande ?base=1. */
    @Test fun oneBaseFetchAtATime() {
        val robot = robot()
        val id = robot.recipe.id
        robot.servings[id] = 6
        robot.baseDelayMs = 1_500
        val p = Phone(robot)
        p.open(id)
        onMain { p.flow.setServings(id, 7) }
        eventually("7") { robot.servings[id] == 7 && !p.fiche.quantitiesPending }
        onMain { p.flow.setServings(id, 8) }
        eventually("8") { robot.servings[id] == 8 && !p.fiche.quantitiesPending }
        Thread.sleep(1_800)
        assertEquals(1, robot.baseRequests.get())
        // Arrivée : le tap suivant est calculé sur le téléphone.
        val shown = onMain { p.flow.setServings(id, 5); p.fiche }
        assertFalse(shown.quantitiesPending)
        assertEquals(robotSheet(robot, 5).content(), shown.content())
    }

    @Test fun offlineTapChangesNothing() {
        val robot = robot()
        val p = Phone(robot)
        val id = robot.recipe.id
        p.open(id)
        onMain { p.link.unfollow("test") }
        eventually("hors suivi") { p.link.status.phase != LinkPhase.CONNECTED }
        onMain { p.flow.setServings(id, 6) }
        assertEquals(4, onMain { p.fiche.servings })
        assertEquals("Le robot ne répond pas pour l'instant.", p.notices.last())
        Thread.sleep(500)
        assertTrue(robot.servingsRequests.isEmpty())
    }
}
