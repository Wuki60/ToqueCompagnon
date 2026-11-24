package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.screens.RecettesState
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Le cache des photos de bout en bout sur la JVM : vrai hub sur base simulée derrière un serveur
 * qui compte les requêtes d'image, vraie liaison, vrai disque ; seule l'image est remplacée par un
 * texte (« octets@largeur ») : le décodage Android a son propre test.
 */
class PhotoCacheEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()

    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val work = Executors.newFixedThreadPool(2) { r -> Thread(r, "test-photo").apply { isDaemon = true } }
    private val schedule: Scheduler = { ms, r -> val f = main.schedule(r, ms, TimeUnit.MILLISECONDS); { f.cancel(false) } }
    private val robot = SimRobot().start()
    /** Requêtes d'image reçues par le robot, par recette. */
    private val imageRequests = ConcurrentHashMap<String, AtomicInteger>()
    private val api = CompanionApi(robot.hub)
    private val server = HttpServer(object : HttpHandler {
        override fun handle(request: HttpRequest): HttpResponse {
            if (request.path.endsWith("/image")) imageRequests.getOrPut(request.path) { AtomicInteger() }.incrementAndGet()
            return api.handle(request)
        }
    }, 0, InetAddress.getByName("127.0.0.1")).also { it.start() }
    private val links = ArrayList<RobotLink>()

    @After fun tearDown() {
        links.forEach { it.close() }
        server.stop()
        robot.shutdown()
        work.shutdownNow()
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

    private fun requests() = imageRequests.values.sumOf { it.get() }

    private fun paired(): RobotLink {
        val l = RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(server.boundPort), waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500)
        links += l
        val done = CountDownLatch(1)
        var r: PairResult? = null
        l.pairWithCode(robot.hub.pairing.open().code) { r = it; done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue("$r", r is PairResult.Paired)
        // Les photos ne se téléchargent que liaison connectée (suivie, comme par l'activité ou le service).
        onMain { l.follow("test") }
        eventually("connecté") { l.status.phase == LinkPhase.CONNECTED }
        return l
    }

    /** Un démarrage de l'appli : nouveau disque (même dossier), mémoire vide ; [laterMs] : horloge avancée. */
    private fun loader(link: RobotLink, visible: Boolean, laterMs: Long = 0, disk: PhotoDisk = PhotoDisk(tmp.root)): PhotoLoader<String> =
        onMain {
            PhotoLoader(link, disk, { main.execute(it) }, work, schedule, { b, w -> "${b.size}@$w" }, { it.length * 100 }, 1 shl 20,
                now = { System.currentTimeMillis() + laterMs }).also { it.setVisible(visible) }
        }

    private fun catalog(link: RobotLink, loader: PhotoLoader<String>?, onList: (RecettesState) -> Unit): CatalogFlow = onMain {
        // Pages de 20 ici (48 dans l'appli) : trois pages sur les 60 recettes du robot simulé.
        CatalogFlow(link, FakeNav(), { onList(it); loader?.catalogShown(it.items) }, {}, {}, schedule, pageSize = 20)
    }

    /** Demande chaque photo deux fois (deux cartes) ; attend [answers] réponses par photo et les rend. */
    private fun askAll(loader: PhotoLoader<String>, ids: List<String>, width: Int = 300, answers: Int = 2): Map<String, List<String?>> {
        val got = ConcurrentHashMap<String, MutableList<String?>>()
        onMain {
            for (id in ids) repeat(2) { loader.load(id, width) { b -> got.getOrPut(id) { mutableListOf() } += b } }
        }
        eventually("photos reçues") { ids.all { (got[it]?.size ?: 0) >= answers } }
        Thread.sleep(100)
        return got
    }

    private fun photoFiles(): List<File> = tmp.root.walkTopDown().filter { it.isFile }.toList()

    @Test fun photosAskedAgainAfterARestartCostNoRequest() {
        val link = paired()
        val first = loader(link, visible = false)
        var list = RecettesState()
        val flow = catalog(link, null) { list = it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { list.items.size == 20 && !list.loading }
        val ids = onMain { list.items.filter { it.picture }.map { it.id } }
        assertTrue(ids.size >= 5)

        // Première fois : une requête par photo, même demandée par deux cartes.
        val got = askAll(first, ids)
        assertTrue(got.values.flatten().all { it == "3@320" })
        assertEquals(ids.size, requests())
        assertTrue(imageRequests.values.all { it.get() == 1 })
        assertEquals(ids.size, photoFiles().size)

        // En mémoire : rendue pendant l'appel.
        var sync: String? = null
        onMain { first.load(ids[0], 300) { sync = it } }
        assertEquals("3@320", sync)

        // L'appli redémarre : tout vient du disque, le robot ne reçoit aucune requête d'image.
        val second = loader(link, visible = true)
        val again = askAll(second, ids)
        assertTrue(again.values.flatten().all { it == "3@320" })
        // Une autre taille (la fiche) décode le même fichier : la petite image tout de suite, puis la grande.
        assertEquals(listOf("3@320", "3@320", "3@704", "3@704"), askAll(second, ids.take(1), width = 700, answers = 4)[ids[0]])
        onMain { second.catalogShown(list.items) }
        Thread.sleep(500)
        assertEquals(ids.size, requests())

        // Une recette sans photo (404) : rien d'affiché, pas redemandée.
        val none = onMain { list.items.first { !it.picture }.id }
        assertEquals(listOf<String?>(null, null), askAll(second, listOf(none))[none])
        val before = requests()
        assertEquals(listOf<String?>(null, null), askAll(second, listOf(none))[none])
        assertEquals(before, requests())
    }

    @Test fun eachNewPageIsPrefetchedToDiskThenForgettingThePhoneDeletesIt() {
        val link = paired()
        val loader = loader(link, visible = true)
        var list = RecettesState()
        val flow = catalog(link, loader) { list = it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { list.items.size == 20 && !list.loading }
        val page1 = onMain { list.items.filter { it.picture }.map { it.id } }
        eventually("première page préchargée") { requests() == page1.size && photoFiles().size == page1.size }
        // Préchargé : disque seulement, rien en mémoire.
        assertEquals(0L, onMain { loader.memoryBytes() })

        onMain { flow.loadMore() }
        eventually("deuxième page") { list.items.size == 40 && !list.loading }
        val both = onMain { list.items.filter { it.picture }.map { it.id } }
        eventually("deuxième page préchargée") { requests() == both.size && photoFiles().size == both.size }

        // Les cartes de ces pages s'affichent sans requête.
        askAll(loader, both)
        assertEquals(both.size, requests())
        assertTrue(imageRequests.values.all { it.get() == 1 })

        // Oublié : les photos de ce robot sont effacées.
        val forgotten = CountDownLatch(1)
        onMain { link.forget { forgotten.countDown() } }
        assertTrue(forgotten.await(20, TimeUnit.SECONDS))
        eventually("pas appairé") { link.status.phase == LinkPhase.NOT_PAIRED }
        eventually("photos effacées") { tmp.root.listFiles()!!.isEmpty() }
    }

    @Test fun aPhotoOlderThanThirtyDaysIsShownAtOnceThenDownloadedAgain() {
        val link = paired()
        var list = RecettesState()
        val flow = catalog(link, null) { list = it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { list.items.size == 20 && !list.loading }
        val id = onMain { list.items.first { it.picture }.id }
        askAll(loader(link, visible = false), listOf(id))
        assertEquals(1, requests())

        // 31 jours plus tard : montrée depuis le disque, puis vérifiée en fond (les mêmes octets : le fichier reste).
        val later = loader(link, visible = true, laterMs = 31L * 24 * 3600 * 1000)
        assertEquals(listOf<String?>("3@320", "3@320"), askAll(later, listOf(id))[id])
        eventually("vérifiée") { requests() == 2 }
        Thread.sleep(300)
        assertEquals(2, requests())
        assertEquals(1, photoFiles().size)
    }

    @Test fun aPhotoIsStillShownWhenTheDiskCannotKeepIt() {
        val link = paired()
        var list = RecettesState()
        val flow = catalog(link, null) { list = it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { list.items.size == 20 && !list.loading }
        val ids = onMain { list.items.filter { it.picture }.map { it.id } }
        val full = { PhotoDisk(tmp.root, writer = { _, _ -> throw java.io.IOException("disque plein") }) }

        // Disque plein : montrées quand même, en mémoire seulement, une requête par photo.
        val first = loader(link, visible = false, disk = full())
        assertTrue(askAll(first, ids).values.flatten().all { it == "3@320" })
        assertEquals(ids.size, requests())
        assertTrue(photoFiles().isEmpty())
        var sync: String? = null
        onMain { first.load(ids[0], 300) { sync = it } }
        assertEquals("3@320", sync)

        // Rien n'est retenu comme « pas de photo » : après un redémarrage, téléchargées de nouveau.
        val second = loader(link, visible = false, disk = full())
        assertTrue(askAll(second, ids).values.flatten().all { it == "3@320" })
        assertEquals(2 * ids.size, requests())
    }

    @Test fun nothingIsDownloadedUntilTheLinkIsConnectedThenTheWaitingCardsGetTheirPhoto() {
        val link = paired()
        val loader = loader(link, visible = true)
        var list = RecettesState()
        val flow = catalog(link, null) { list = it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { list.items.size == 20 && !list.loading }
        val ids = onMain { list.items.filter { it.picture }.map { it.id } }
        val (early, late) = ids.take(2) to ids.drop(2)
        askAll(loader, early)
        assertEquals(early.size, requests())

        // Plus suivie : la liaison n'est plus connectée. Mémoire et disque servent encore, rien ne part vers le robot.
        onMain { link.unfollow("test") }
        eventually("pas connecté") { link.status.phase != LinkPhase.CONNECTED }
        val fromDisk = loader(link, visible = true)
        assertTrue(askAll(fromDisk, early).values.flatten().all { it == "3@320" })
        val got = ConcurrentHashMap<String, String>()
        onMain { for (id in late) loader.load(id, 300) { b -> if (b != null) got[id] = b } }
        Thread.sleep(1_000)
        assertEquals(early.size, requests())
        assertTrue(got.isEmpty())

        // De nouveau connectée : les cartes qui attendent reçoivent leur photo, sans rien redemander.
        onMain { link.follow("test") }
        eventually("photos après reconnexion") { got.size == late.size }
        assertEquals(ids.size, requests())
    }

    @Test fun inTheBackgroundNothingIsPrefetched() {
        val link = paired()
        val loader = loader(link, visible = false)
        var list = RecettesState()
        val flow = catalog(link, loader) { list = it }
        onMain { flow.ensureLoaded() }
        eventually("première page") { list.items.size == 20 && !list.loading }
        Thread.sleep(800)
        assertEquals(0, requests())
        // De retour à l'écran : la page est préchargée.
        onMain { loader.setVisible(true) }
        val ids = onMain { list.items.filter { it.picture }.map { it.id } }
        eventually("préchargée") { requests() == ids.size }
    }
}
