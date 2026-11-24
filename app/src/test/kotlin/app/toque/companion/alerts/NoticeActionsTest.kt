package app.toque.companion.alerts

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import app.toque.companion.CompanionApp
import app.toque.companion.CompanionLanguages
import app.toque.companion.link.FileLinkStore
import app.toque.companion.link.PairedRobot
import app.toque.companion.link.SimRobot
import app.toque.core.i18n.tr
import app.toque.core.machine.JobState
import app.toque.core.machine.Programs
import app.toque.core.protocol.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Un téléphone appairé à un robot simulé (vrai hub, vraie base simulée) sur 127.0.0.1. */
class SimPairedApp : CompanionApp() {
    override fun onCreate() {
        val r = SimRobot().start()
        robot = r
        val p = r.hub.pairing.pairWithCode(r.hub.pairing.open().code)
        FileLinkStore(File(noBackupFilesDir, "robot.json")).save(PairedRobot("127.0.0.1", r.port, p.token!!, p.phone!!.id,
            r.hub.pairing.robotId, "Monsieur Cuisine Connect", 1L, "code"))
        super.onCreate()
    }

    companion object {
        @Volatile var robot: SimRobot? = null
    }
}

/**
 * Boutons « Pause », « Reprendre » et « +1 min » de la notification de la cuisson, contre un vrai hub :
 * montrés selon les permissions du robot, la même commande que le bouton de l'appli, le refus du
 * robot dans la notification. Un broadcast, jamais une activité (utilisable téléphone verrouillé).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SimPairedApp::class)
class NoticeActionsTest {
    private val app get() = RuntimeEnvironment.getApplication() as CompanionApp
    private val robot get() = SimPairedApp.robot!!
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @After fun tearDown() {
        app.link.close()
        robot.shutdown()
    }

    private fun until(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            assertTrue("délai dépassé : $what (${notice()?.extras?.getCharSequence("android.text")})", System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
    }

    private fun notice() = nm.activeNotifications.firstOrNull { it.id == Notifier.FOLLOW_ID }?.notification
    private fun labels() = notice()?.actions?.map { it.title.toString() } ?: emptyList()
    private fun text() = notice()?.extras?.getCharSequence("android.text")?.toString()
    private fun tap(a: NoticeAction) = NoticeActionReceiver().onReceive(app, Intent(a.action))

    @Test fun pauseResumeAndAMinuteWithoutOpeningTheApp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // L'Application installe la langue du téléphone (anglais sous Robolectric) : ce test lit le français.
        CompanionLanguages.install(app, "fr")
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        until("base prête") { robot.snapshot.ready }
        assertTrue(robot.loop.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 600_000)) }.ok)
        until("notification de la cuisson et ses boutons") { labels() == listOf(tr("Pause"), tr("+1 min")) }
        val n = notice()!!
        assertEquals("Cuisson manuelle", n.extras.getCharSequence("android.title").toString())
        until("réglages affichés") { text() == "sans chauffe · vitesse 2" }
        // Chaque bouton est un broadcast vers le récepteur de l'appli : aucune activité, pas de déverrouillage demandé.
        for (a in n.actions) {
            val pi = shadowOf(a.actionIntent)
            assertTrue(pi.isBroadcastIntent)
            assertFalse(pi.isActivityIntent)
            assertEquals(ComponentName(app, NoticeActionReceiver::class.java), pi.savedIntent.component)
            assertFalse(a.isAuthenticationRequired)
        }
        // Jamais d'arrêt sur la notification.
        assertFalse(labels().any { it.contains("Arrêt") || it.contains("Stop") })

        tap(NoticeAction.PAUSE)
        until("en pause sur le robot") { robot.snapshot.state == JobState.PAUSED }
        until("« Reprendre » proposé") { labels() == listOf(tr("Reprendre"), tr("+1 min")) }
        tap(NoticeAction.RESUME)
        until("reprise") { robot.snapshot.state == JobState.RUNNING }
        tap(NoticeAction.ADD_MINUTE)
        until("une minute de plus") { robot.snapshot.totalMs == 660_000L }
        // Comme depuis l'appli : la pause est attribuée à ce téléphone dans le journal du robot.
        var journal: app.toque.core.companion.JournalPage? = null
        app.link.journal(0, 50) { r -> journal = r.getOrNull() }
        until("journal lu") { journal != null }
        assertTrue(journal!!.entries.any { it.kind == "paused" && it.origin.self })
        assertTrue(journal!!.entries.any { it.kind == "adjusted" && it.origin.self })

        // Refus du robot (plus de 99 min) : son message dans la notification, le compte à rebours continue.
        assertTrue(robot.loop.call { it.adjust(null, null, null, 5_920_000L, false) }.ok)
        until("durée longue vue") { app.link.latest?.job?.totalMs == 5_920_000L }
        until("boutons revenus") { labels() == listOf(tr("Pause"), tr("+1 min")) }
        tap(NoticeAction.ADD_MINUTE)
        until("refus affiché") { text() == tr("Durée hors limites") }
        assertEquals(5_920_000L, robot.snapshot.totalMs)
        assertTrue(app.diag.text(), app.diag.text().contains("refusé par le robot (too_long)"))

        // Couvercle ouvert pendant la pause : pas de « Reprendre » (la reprise se fait sur le robot).
        tap(NoticeAction.PAUSE)
        until("en pause") { robot.snapshot.state == JobState.PAUSED }
        robot.board.lidOpen = true
        until("couvercle vu") { robot.snapshot.lidOpen }
        robot.board.lidOpen = false
        until("plus de « Reprendre »") { labels() == listOf(tr("+1 min")) }
        // Un appui sur un ancien bouton « Reprendre » n'envoie rien.
        tap(NoticeAction.RESUME)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        assertEquals(JobState.PAUSED, robot.snapshot.state)
        c.destroy()
    }
}
