package app.toque.companion.link

import app.toque.companion.alerts.Alert
import app.toque.companion.alerts.AlertCenter
import app.toque.companion.alerts.AlertKind
import app.toque.companion.alerts.AlertSink
import app.toque.companion.live.LiveInput
import app.toque.companion.live.LivePresenter
import app.toque.companion.live.LiveView
import app.toque.companion.ui.Route
import app.toque.companion.ui.screens.EtapesState
import app.toque.companion.ui.screens.ManuelState
import app.toque.companion.ui.screens.PeseeState
import app.toque.companion.ui.screens.ReposState
import app.toque.companion.ui.screens.RepriseState
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.CompanionApi
import app.toque.core.companion.HttpHandler
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.HttpResponse
import app.toque.core.companion.HttpServer
import app.toque.core.companion.CookSession
import app.toque.core.companion.WaitingFor
import app.toque.core.machine.Programs
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * De bout en bout sur la JVM : un vrai hub et sa base simulée sur 127.0.0.x, la vraie liaison du
 * téléphone (appairage par code, suivi, commandes), le présentateur vérifié à chaque étape.
 */
class RobotLinkEndToEndTest {
    /** Le « fil principal » : tous les rappels de la liaison y arrivent, dans l'ordre. */
    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val robots = ArrayList<SimRobot>()
    private val links = ArrayList<RobotLink>()
    private val servers = ArrayList<HttpServer>()

    @After fun tearDown() {
        links.forEach { it.close() }
        servers.forEach { it.stop() }
        robots.forEach { it.shutdown() }
        main.shutdownNow()
    }

    private fun robot() = SimRobot().start().also { robots += it }

    private fun link(ports: List<Int>, hosts: List<String> = listOf("127.0.0.1"), store: LinkStore = MemoryLinkStore()) =
        RobotLink(store, post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { hosts }, ports = ports,
            waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500).also { links += it }

    private fun <T> await(call: ((T) -> Unit) -> Unit): T {
        val box = AtomicReference<T>()
        val done = CountDownLatch(1)
        call { box.set(it); done.countDown() }
        assertTrue("pas de réponse", done.await(20, TimeUnit.SECONDS))
        return box.get()
    }

    private fun view(link: RobotLink): LiveView {
        val now = System.nanoTime() / 1_000_000L
        val st = link.status
        return LivePresenter.present(LiveInput(st, link.latest, (now - link.latestMono).coerceAtLeast(0),
            if (st.phase == LinkPhase.UNREACHABLE) now - st.sinceMono else 0))
    }

    private fun eventually(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("délai dépassé : $what")
    }

    private fun pair(robot: SimRobot, link: RobotLink) {
        val code = robot.hub.pairing.open().code
        // Un mauvais code d'abord : le refus du robot revient avec son message.
        val wrong = await<PairResult> { link.pairWithCode(if (code == "000000") "111111" else "000000", it) }
        assertTrue("$wrong", wrong is PairResult.Refused && wrong.code == "wrong_code" && wrong.message.isNotBlank())
        val r = await<PairResult> { link.pairWithCode(code, it) }
        assertEquals(PairResult.Paired("Monsieur Cuisine Connect"), r)
        assertEquals(robot.hub.pairing.robotId, link.paired!!.robotId)
    }

    private class Sink : AlertSink {
        val alerts: MutableList<Alert> = Collections.synchronizedList(ArrayList())
        override fun show(alert: Alert, robotName: String, onScreen: Boolean) { alerts += alert }
        override fun showOffline(robotName: String, onScreen: Boolean) {}
        override fun clearOffline() {}
    }

    @Test fun pairsFollowsAndSendsCommands() {
        val robot = robot()
        val store = MemoryLinkStore()
        val link = link(listOf(robot.port), store = store)
        assertEquals(LinkPhase.NOT_PAIRED, link.status.phase)
        pair(robot, link)
        assertEquals(robot.port, store.value!!.port)
        val sink = Sink()
        val center = AlertCenter(link, sink, enabled = { AlertKind.values().toSet() }, foreground = { false },
            schedule = { ms, r -> main.schedule(r, ms, TimeUnit.MILLISECONDS) })
        link.follow("test")
        eventually("robot au repos") { view(link).route == Route.REPOS }
        assertEquals(LinkPhase.CONNECTED, link.status.phase)

        // Recette guidée (poussée comme le fait l'écran de cuisson du robot).
        robot.hub.updateSession(CookSession.of(robot.recipe, 1, setOf(0), waitingFor = WaitingFor.WEIGH))
        eventually("pesée") { view(link).route == Route.PESEE }
        assertEquals("Mettre le beurre dans le bol.", (view(link).screen as PeseeState).instruction)
        robot.hub.updateSession(CookSession.of(robot.recipe, 0, waitingFor = WaitingFor.HAND))
        eventually("étape à la main") { view(link).route == Route.ETAPES }
        val e = view(link).screen as EtapesState
        assertTrue(e.root)
        assertEquals("À faire à la main, puis « Suivant » sur le robot.", e.steps[0].waiting)
        robot.hub.endSession(false)
        eventually("repos après la recette") { view(link).route == Route.REPOS }

        // Cuisson manuelle lancée sur le robot.
        assertTrue(robot.loop.call { it.startJob(Programs.manual(2, Mcc.levelAtMost(60), Direction.CLOCKWISE, 120_000)) }.ok)
        eventually("cuisson manuelle") { view(link).route == Route.MANUEL && (view(link).screen as ManuelState).totalMs == 120_000L }

        val paused = await<CommandOutcome> { link.pause(it) }
        assertTrue("$paused", paused.ok)
        eventually("reprise possible") { view(link).route == Route.REPRISE }
        assertNull((view(link).screen as RepriseState).pausedBy)

        // Reprendre deux fois : la seconde est refusée par le robot, avec son message.
        assertTrue(await<CommandOutcome> { link.resume(it) }.ok)
        eventually("reprise") { view(link).route == Route.MANUEL }
        val again = await<CommandOutcome> { link.resume(it) }
        assertFalse(again.ok)
        assertEquals("not_paused", again.code)
        assertTrue(again.message.isNotBlank())

        assertTrue(await<CommandOutcome> { link.adjust(AdjustRequest(addMs = 60_000), it) }.ok)
        eventually("+1 min") { (view(link).screen as? ManuelState)?.totalMs == 180_000L }

        assertTrue(await<CommandOutcome> { link.stop(it) }.ok)
        // Le robot garde le programme arrêté avec son OK : le téléphone montre la même fin, puis OK.
        eventually("arrêt") { view(link).route == Route.FIN }
        assertTrue(await<CommandOutcome> { link.clear(it) }.ok)
        eventually("OK") { view(link).route == Route.REPOS }
        eventually("dernière activité") { (view(link).screen as ReposState).last?.detail?.startsWith("Arrêté à") == true }

        // Alertes : nos propres pause et arrêt ne notifient pas ; une pause et un arrêt faits sur le robot, si
        // (passé le délai où le robot attribue une transition à la dernière commande d'un téléphone).
        Thread.sleep(app.toque.core.companion.CompanionHub.ATTRIBUTION_MS + 200)
        assertTrue(robot.loop.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 120_000)) }.ok)
        eventually("deuxième cuisson") { view(link).route == Route.MANUEL }
        assertTrue(robot.loop.call { it.pause() }.ok)
        eventually("pause sur le robot") { view(link).route == Route.REPRISE && (view(link).screen as RepriseState).pausedBy == "Pause sur le robot" }
        assertTrue(robot.loop.call { it.stop() }.ok)
        try {
            eventually("alertes") { sink.alerts.map { it.kind } == listOf(AlertKind.WAITING, AlertKind.WAITING, AlertKind.PAUSE, AlertKind.END) }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message} : ${sink.alerts}")
        }
        Thread.sleep(500)
        // Pesée et étape à la main (le robot attend), puis la pause et l'arrêt faits sur le robot ; rien
        // pour la pause, la reprise, le réglage et l'arrêt envoyés depuis ce téléphone.
        assertEquals(4, sink.alerts.size)
        assertEquals("Velouté de potimarron", sink.alerts[0].title)
        assertEquals(sink.alerts.map { it.seq }.distinct(), sink.alerts.map { it.seq })
        center.close()

        link.unfollow("test")
        assertFalse(link.isFollowing)
    }

    /**
     * Le cas signalé : programme lancé sur le robot pendant que seul le service suit (appli fermée,
     * robot au repos : attente longue de 120 s), il se termine : exactement une alerte de fin.
     */
    @Test fun programStartedOnTheRobotWhileOnlyTheServiceFollows() {
        val robot = robot()
        val answers = java.util.concurrent.atomic.AtomicInteger()
        lateinit var l: RobotLink
        val pacing = FollowPacing(visible = { false }, busy = { LivePresenter.keepFollowing(l.latest) }, ownIpv4 = { "127.0.0.1" },
            robotHost = { l.paired?.host }, elapsedMs = { System.nanoTime() / 1_000_000L })
        l = RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(robot.port), followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500,
            pacing = pacing, onAnswer = { answers.incrementAndGet() }).also { links += it }
        pair(robot, l)
        val sink = Sink()
        val center = AlertCenter(l, sink, enabled = { AlertKind.values().filter { it.defaultOn }.toSet() }, foreground = { false },
            schedule = { ms, r -> main.schedule(r, ms, TimeUnit.MILLISECONDS) })
        // Seul le service suit (l'appli n'est pas à l'écran).
        l.follow("service")
        eventually("robot au repos") { view(l).route == Route.REPOS }
        assertEquals(120, pacing.waitSeconds())
        Thread.sleep(300)
        assertTrue(robot.loop.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 3_000)) }.ok)
        eventually("cuisson vue") { view(l).route == Route.MANUEL }
        assertEquals(20, pacing.waitSeconds())
        // Fin de la cuisson : affichée avec son OK, comme sur le robot.
        eventually("fin de la cuisson", 30_000) { view(l).route == Route.FIN }
        eventually("alerte de fin") { sink.alerts.any { it.kind == AlertKind.END } }
        Thread.sleep(1_000)
        assertEquals("${sink.alerts}", listOf(AlertKind.END), sink.alerts.map { it.kind })
        assertTrue(answers.get() >= 3)
        center.close()
        l.unfollow("service")
        assertFalse(l.isFollowing)
    }

    @Test fun findsTheRobotAgainOnAnotherPortAndAddress() {
        val robot = robot()
        val newPort = ServerSocket(0).use { it.localPort }
        val second = canBind("127.0.0.2")
        val hosts = if (second) listOf("127.0.0.1", "127.0.0.2") else listOf("127.0.0.1")
        val link = link(listOf(robot.port, newPort), hosts)
        pair(robot, link)
        link.follow("test")
        eventually("suivi") { link.status.phase == LinkPhase.CONNECTED }

        robot.listen(newPort, "127.0.0.1")
        eventually("injoignable") { link.status.phase != LinkPhase.CONNECTED }
        eventually("retrouvé sur le nouveau port", 30_000) { link.status.phase == LinkPhase.CONNECTED && link.paired!!.port == newPort }
        eventually("état") { view(link).route == Route.REPOS }

        assumeTrue("127.0.0.2 indisponible sur cette machine", second)
        robot.listen(newPort, "127.0.0.2")
        eventually("retrouvé à la nouvelle adresse", 30_000) { link.status.phase == LinkPhase.CONNECTED && link.paired!!.host == "127.0.0.2" }
        // Les commandes suivent la nouvelle adresse.
        assertEquals("tare", await<CommandOutcome> { link.tare(it) }.let { if (it.ok) "tare" else it.code })
    }

    /** Couleur et thème du robot : appris à l'appairage, suivis à chaque état, gardés avec l'appairage. */
    @Test fun theRobotLookIsLearntAtPairingAndKept() {
        val robot = robot()
        robot.hub.updateRobot(app.toque.core.companion.RobotInfo("Monsieur Cuisine Connect", "0.8.0", "fr", accent = "#ff6f61", theme = "light"))
        val store = MemoryLinkStore()
        val link = link(listOf(robot.port), store = store)
        pair(robot, link)
        assertEquals("#FF6F61", link.paired!!.accent)
        assertEquals("light", store.value!!.theme)
        link.follow("test")
        eventually("suivi") { link.status.phase == LinkPhase.CONNECTED }
        robot.hub.updateRobot(app.toque.core.companion.RobotInfo("Cuisine du bas", "0.8.0", "fr", accent = "#6E9BFF", theme = "dark"))
        eventually("nouvelle couleur gardée") { store.value?.accent == "#6E9BFF" }
        assertEquals("dark", store.value!!.theme)
        assertEquals("Cuisine du bas", store.value!!.robotName)
        // Gardé dans le fichier de l'appairage, relu tel quel ; un fichier d'avant (sans couleur) se lit encore.
        val back = PairedRobot.fromJson(store.value!!.toJson())!!
        assertEquals(store.value, back)
        val old = PairedRobot.fromJson("""{"host":"192.168.0.12","port":8088,"token":"t","phoneId":"p","robotId":"r","robotName":"n","pairedAt":1}""")!!
        assertEquals("", old.accent)
        assertEquals("", old.theme)
        assertEquals("", PairedRobot.fromJson(back.toJson().replace("#6E9BFF", "bleu"))!!.accent)
    }

    @Test fun phoneRemovedOnTheRobot() {
        val robot = robot()
        val store = MemoryLinkStore()
        val link = link(listOf(robot.port), store = store)
        pair(robot, link)
        link.follow("test")
        eventually("suivi") { link.status.phase == LinkPhase.CONNECTED }
        robot.hub.pairing.remove(link.paired!!.phoneId)
        eventually("retiré", 30_000) { link.status.phase == LinkPhase.REMOVED }
        assertTrue(link.status.message!!.contains("retiré"))
        assertNull(store.value)
        assertNull(link.paired)
        assertFalse(link.isFollowing)
        assertEquals("not_paired", await<CommandOutcome> { link.pause(it) }.code)
    }

    @Test fun removedPhoneLooksLikeAnUnreachableRobot() {
        val robot = robot()
        val link = link(listOf(robot.port))
        pair(robot, link)
        link.follow("test")
        eventually("suivi") { link.status.phase == LinkPhase.CONNECTED }
        // Le robot disparaît du réseau et retire le téléphone ; à son adresse répond un appareil qui ne reconnaît pas
        // ce téléphone : la preuve y échoue, le reste n'y répond pas (404, pas 401). Le robot lui-même revenu, le
        // suivi recevrait un 401 et oublierait l'appairage (phoneRemovedOnTheRobot) : selon qui passait le
        // premier, ce test échouait une fois sur trois.
        val port = robot.port
        robot.hub.stop()
        robot.hub.pairing.remove(link.paired!!.phoneId)
        val api = CompanionApi(robot.hub)
        servers += HttpServer(object : HttpHandler {
            override fun handle(request: HttpRequest): HttpResponse =
                if (request.path == "/api/prove") api.handle(request) else HttpResponse.text(404, "Not found\n")
        }, port, InetAddress.getByName("127.0.0.1")).also { it.start() }
        eventually("peut-être retiré", 30_000) { link.status.phase == LinkPhase.UNREACHABLE && link.status.maybeRemoved }
        // Le jeton n'est jamais parti : l'appairage reste, l'écran le dit.
        assertTrue(link.paired != null)
        val v = LivePresenter.present(LiveInput(link.status, link.latest, 0, LivePresenter.OFFLINE_GRACE_MS))
        assertEquals(Route.INCIDENT, v.route)
        assertTrue((v.screen as app.toque.companion.ui.screens.IncidentState).note!!.contains("appairer de nouveau"))
    }

    @Test fun severalRobotsWithPairingOpenAreReported() {
        assumeTrue(canBind("127.0.0.2"))
        val a = robot()
        val b = SimRobot().also { robots += it }.start(a.port, "127.0.0.2")
        a.hub.pairing.open()
        b.hub.pairing.open()
        val link = link(listOf(a.port), listOf("127.0.0.1", "127.0.0.2"))
        val r = await<PairResult> { link.pairWithCode("123456", it) }
        assertEquals(PairResult.SeveralRobots(listOf("127.0.0.1", "127.0.0.2")), r)
        // Le code n'a été essayé sur aucun des deux.
        assertTrue(a.hub.pairing.isOpen && b.hub.pairing.isOpen)
        assertNull(link.paired)
    }

    private fun canBind(host: String): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName(host), 0)) }
        true
    } catch (_: Exception) {
        false
    }
}
