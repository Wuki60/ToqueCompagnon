package app.toque.companion.link

import app.toque.core.companion.StateWire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Un écouteur ajouté à une liaison qui sait déjà quelque chose l'apprend sans attendre un changement :
 * l'état de la liaison, puis le dernier état du robot, par [post], s'il est encore inscrit.
 */
class RobotLinkReplayTest {
    /** Fil « principal » à la main : rien ne tourne tant que [pump] n'est pas appelé. */
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private fun pump() { while (true) (queue.poll() ?: return).run() }
    private val robots = ArrayList<SimRobot>()
    private val links = ArrayList<RobotLink>()

    @After fun tearDown() {
        links.forEach { it.close() }
        robots.forEach { it.shutdown() }
    }

    private fun link(store: LinkStore = MemoryLinkStore(), ports: List<Int> = listOf(8088)) =
        RobotLink(store, post = { queue.add(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") }, ports = ports,
            waitSeconds = 2, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 300).also { links += it }

    private fun until(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            pump()
            if (cond()) return
            assertTrue("délai dépassé : $what", System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
    }

    private class Recorder : RobotLink.Listener {
        val events = ArrayList<String>()
        val states = ArrayList<Pair<StateWire, Long>>()
        override fun onStatus(status: LinkStatus) { events += "status ${status.phase}" }
        override fun onState(state: StateWire, receivedMono: Long) {
            events += "state ${state.revision}"
            states += state to receivedMono
        }
    }

    private fun connected(): Pair<SimRobot, RobotLink> {
        val robot = SimRobot().start().also { robots += it }
        val l = link(ports = listOf(robot.port))
        val done = CountDownLatch(1)
        l.pairWithCode(robot.hub.pairing.open().code) { done.countDown() }
        until("appairé") { done.count == 0L }
        assertTrue(done.await(1, TimeUnit.SECONDS))
        l.follow("service")
        until("connecté avec un état") { l.status.phase == LinkPhase.CONNECTED && l.latest != null }
        return robot to l
    }

    @Test fun connectedWithAStateTellsStatusThenStateWithItsRealArrival() {
        val (_, l) = connected()
        pump()
        val r = Recorder()
        l.addListener(r)
        // Jamais pendant addListener : seulement quand le fil principal tourne.
        assertTrue(r.events.isEmpty())
        val latest = l.latest!!
        val mono = l.latestMono
        pump()
        assertEquals("status CONNECTED", r.events.first())
        val (state, received) = r.states.first()
        assertEquals(latest.revision, state.revision)
        assertEquals(mono, received)
        // Rien deux fois, rien de plus ancien ensuite.
        val end = System.currentTimeMillis() + 1_500
        while (System.currentTimeMillis() < end) { pump(); Thread.sleep(20) }
        val revisions = r.states.map { it.first.revision }
        assertEquals(revisions.sorted().distinct(), revisions)
    }

    @Test fun unreachableIsToldWithoutAState() {
        val (robot, l) = connected()
        robot.hub.stop()
        until("injoignable") { l.status.phase == LinkPhase.UNREACHABLE }
        pump()
        val r = Recorder()
        l.addListener(r)
        pump()
        assertEquals("status UNREACHABLE", r.events.first())
    }

    @Test fun notPairedIsToldToo() {
        val l = link()
        val r = Recorder()
        l.addListener(r)
        assertTrue(r.events.isEmpty())
        pump()
        assertEquals(listOf("status NOT_PAIRED"), r.events)
        assertTrue(r.states.isEmpty())
    }

    @Test fun aListenerRemovedBeforeThePostRunsHearsNothing() {
        val (_, l) = connected()
        pump()
        val r = Recorder()
        val remove = l.addListener(r)
        remove()
        pump()
        Thread.sleep(300)
        pump()
        assertTrue(r.events.toString(), r.events.isEmpty())
    }

    @Test fun twoListenersEachGetItOnce() {
        val (_, l) = connected()
        pump()
        val a = Recorder()
        val b = Recorder()
        l.addListener(a)
        l.addListener(b)
        pump()
        for (r in listOf(a, b)) {
            assertEquals(1, r.events.count { it.startsWith("status") })
            assertNotNull(r.states.firstOrNull())
        }
    }
}
