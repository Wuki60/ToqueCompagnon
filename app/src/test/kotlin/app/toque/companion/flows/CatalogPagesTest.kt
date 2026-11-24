package app.toque.companion.flows

import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.RecettesScreen
import app.toque.companion.ui.screens.RecettesState
import app.toque.core.companion.ApiError
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Le catalogue page par page : taille des pages et avance de la grille, recherche qui garde les cartes
 * d'avant (estompées) jusqu'à la réponse, réponse vide, erreur. Vrai hub (60 recettes) derrière un
 * serveur qui note les pages demandées et peut retenir ou refuser la réponse.
 */
class CatalogPagesTest {
    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val schedule: Scheduler = { ms, r -> val f = main.schedule(r, ms, TimeUnit.MILLISECONDS); { f.cancel(false) } }
    private val robot = SimRobot().start()
    private val api = CompanionApi(robot.hub)
    /** Pages demandées : « q|offset|limit ». */
    private val asked = CopyOnWriteArrayList<String>()
    @Volatile private var hold: CountDownLatch? = null
    @Volatile private var fail = false
    private val server = HttpServer(object : HttpHandler {
        override fun handle(request: HttpRequest): HttpResponse {
            if (request.path == "/api/recipes") {
                asked += "${request.query["q"]}|${request.query["offset"]}|${request.query["limit"]}"
                hold?.await(10, TimeUnit.SECONDS)
                if (fail) return ApiError.response(500, "internal", "Internal error")
            }
            return api.handle(request)
        }
    }, 0, InetAddress.getByName("127.0.0.1")).also { it.start() }
    private lateinit var link: RobotLink

    @After fun tearDown() {
        hold?.countDown()
        if (::link.isInitialized) link.close()
        server.stop()
        robot.shutdown()
        main.shutdownNow()
    }

    private fun <T> onMain(block: () -> T): T = main.submit(Callable { block() }).get(10, TimeUnit.SECONDS)

    private fun eventually(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(30)
        }
        throw AssertionError("délai dépassé : $what")
    }

    private fun flow(onList: (RecettesState) -> Unit): CatalogFlow {
        link = RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(server.boundPort), waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500)
        val done = CountDownLatch(1)
        var r: PairResult? = null
        link.pairWithCode(robot.hub.pairing.open().code) { r = it; done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue("$r", r is PairResult.Paired)
        val nav = FakeNav()
        return onMain { CatalogFlow(link, nav, onList, {}, {}, schedule).also { nav.select(Tab.RECIPES) } }
    }

    @Test fun pagesOfFortyEightAndAMatchingLookAhead() {
        assertEquals(48, CatalogFlow.PAGE_SIZE)
        // Page suivante demandée à 8 lignes de la fin : le tiers d'une page, jamais plus de la moitié.
        assertEquals(16, RecettesScreen.LOOKAHEAD)
        assertTrue(RecettesScreen.LOOKAHEAD * 2 <= CatalogFlow.PAGE_SIZE)
        var list = RecettesState()
        val flow = flow { list = it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { !list.loading && list.items.isNotEmpty() }
        assertEquals(listOf("|0|48"), asked.toList())
        assertEquals(48, onMain { list.items.size })
        assertTrue(onMain { list.hasMore })
        onMain { flow.loadMore() }
        eventually("deuxième page") { !list.loading && list.items.size == 60 }
        assertEquals(listOf("|0|48", "|48|48"), asked.toList())
        assertFalse(onMain { list.hasMore })
        assertEquals(60, onMain { list.items.map { it.id }.toSet().size })
    }

    @Test fun searchKeepsThePreviousCardsUntilTheAnswer() {
        var list = RecettesState()
        val seen = CopyOnWriteArrayList<RecettesState>()
        val flow = flow { list = it; seen += it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { !list.loading && list.items.size == 48 }
        val before = onMain { list.items }
        seen.clear()

        // La réponse est retenue : la recherche est partie, les cartes d'avant restent, estompées.
        hold = CountDownLatch(1)
        onMain { flow.search("Soupe") }
        eventually("recherche partie") { asked.size == 2 && list.loading }
        onMain {
            assertTrue(list.stale)
            assertEquals(before, list.items)
            assertNull(list.error)
        }
        // Jamais de grille vide entre-temps (ni écran de chargement plein).
        assertTrue(seen.none { it.items.isEmpty() })
        hold!!.countDown()
        eventually("réponse") { !list.loading }
        onMain {
            assertFalse(list.stale)
            assertTrue(list.items.isNotEmpty())
            assertTrue(list.items != before)
        }
        assertEquals("Soupe|0|48", asked.last())

        // Réponse vide : la grille se vide maintenant (état vide de l'écran), sans erreur.
        hold = null
        onMain { flow.search("zqxjwv") }
        eventually("réponse vide") { asked.last().startsWith("zqxjwv|") && !list.loading && !list.stale }
        onMain {
            assertTrue(list.items.isEmpty())
            assertNull(list.error)
            assertFalse(list.hasMore)
        }

        // Une autre recherche puis une erreur : les cartes d'avant ne répondent pas, elles s'en vont.
        onMain { flow.search("Crème") }
        eventually("crème") { asked.last().startsWith("Crème|") && !list.loading && list.items.isNotEmpty() }
        fail = true
        onMain { flow.search("Risotto") }
        eventually("erreur") { list.query == "Risotto" && !list.loading && list.error != null }
        onMain {
            assertTrue(list.items.isEmpty())
            assertFalse(list.stale)
            assertNotNull(list.error)
        }
        // Réessai : la page arrive.
        fail = false
        onMain { flow.retry() }
        eventually("réessai") { !list.loading && list.error == null && list.items.isNotEmpty() }
    }

    @Test fun anErrorOnALaterPageKeepsTheCards() {
        var list = RecettesState()
        val flow = flow { list = it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { !list.loading && list.items.size == 48 }
        fail = true
        onMain { flow.loadMore() }
        eventually("erreur en bas de liste") { !list.loading && list.error != null }
        assertEquals(48, onMain { list.items.size })
        assertFalse(onMain { list.stale })
    }
}
