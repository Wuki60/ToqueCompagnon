package app.toque.companion.alerts

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import app.toque.companion.CompanionApp
import app.toque.companion.WireFixtures
import app.toque.companion.link.FileLinkStore
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.PairedRobot
import app.toque.core.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Un téléphone appairé (robot à une adresse où personne ne répond : le suivi échoue sans bruit). */
class PairedTestApp : CompanionApp() {
    override fun onCreate() {
        FileLinkStore(File(noBackupFilesDir, "robot.json")).save(
            PairedRobot("127.0.0.1", 9, "0".repeat(64), "1eeddd36", "a1b2c3d4e5f6", "Monsieur Cuisine Connect", 1L, "qr"))
        super.onCreate()
    }
}

/**
 * Quand le service de suivi démarre et s'arrête : appairé ou non, interrupteur, démarrage du téléphone ;
 * et sa notification : celle de la cuisson, ou la discrète du repos, posée une fois.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FollowServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as CompanionApp
    private val service = ComponentName("app.toque.companion", FollowService::class.java.name)

    private fun startedService(): Intent? = shadowOf(app).nextStartedService
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    /** Numéros des notifications affichées. */
    private fun posted(): Set<Int> = nm.activeNotifications.map { it.id }.toSet()
    private val cooking = WireFixtures.state(job = WireFixtures.job())
    private val idle = WireFixtures.state()
    private fun heldWakeLock() = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    @Test fun notPairedNothingStarts() {
        assertNull(app.link.paired)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        FollowService.start(app, "test")
        assertNull(startedService())
        // Démarré quand même (relance du système) : premier plan puis arrêt, sans plantage.
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertFalse(app.link.isFollowing)
        c.destroy()
        assertTrue(app.diag.text(), app.diag.text().contains("rien à suivre"))
    }

    @Test @Config(application = PairedTestApp::class)
    fun bootAndUpdateStartTheServiceWhenPaired() {
        assertNotNull(app.link.paired)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(service, startedService()?.component)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(service, startedService()?.component)
        BootReceiver().onReceive(app, Intent("autre"))
        assertNull(startedService())
        // Interrupteur sur Non : rien.
        app.alertPrefs.background = false
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(startedService())
    }

    @Test @Config(application = PairedTestApp::class)
    fun runsUntilTheSwitchGoesOff() {
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        val s = shadowOf(c.get())
        assertNotNull(s.lastForegroundNotification)
        assertFalse(s.isStoppedBySelf)
        assertTrue(FollowService.running)
        assertTrue(app.link.isFollowing)
        // Une alarme de réveil est prévue (inexacte).
        assertNotNull(shadowOf(app.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm())
        // Robot au repos ou injoignable : pas de verrou de veille tenu.
        assertNull(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()?.takeIf { it.isHeld })
        app.alertPrefs.background = false
        assertTrue(s.isStoppedBySelf)
        c.destroy()
        assertFalse(FollowService.running)
        assertFalse(app.link.isFollowing)
        assertTrue(app.diag.text(), app.diag.text().contains("suivi en arrière-plan coupé"))
    }

    @Test @Config(application = PairedTestApp::class)
    fun idleStartShowsTheDiscreetNotificationOnly() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        val s = shadowOf(c.get())
        assertEquals(Notifier.IDLE_ID, s.lastForegroundNotificationId)
        assertEquals(Notifier.IDLE_CHANNEL, s.lastForegroundNotification.channelId)
        assertEquals(setOf(Notifier.IDLE_ID), posted())
        assertTrue(app.link.isFollowing)
        // Canal à part, discret, sans pastille.
        val ch = nm.getNotificationChannel(Notifier.IDLE_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
        assertFalse(ch.canShowBadge())
        // « Masquer » ouvre la page d'Android de ce seul canal.
        val action = s.lastForegroundNotification.actions.single()
        val i = shadowOf(action.actionIntent).savedIntent
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, i.action)
        assertEquals(Notifier.IDLE_CHANNEL, i.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(app.packageName, i.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(app.diag.text(), app.diag.text().contains("notification discrète (rien en cours)"))
        assertFalse(app.diag.text(), app.diag.text().contains("masquée"))
        c.destroy()
    }

    @Test @Config(application = PairedTestApp::class)
    fun cookingNotificationWhileBusyThenDiscreetAgain() {
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        val svc = c.get()
        val s = shadowOf(svc)
        // Une cuisson commence : notification de la cuisson, la discrète est retirée, verrous pris.
        svc.onStatus(LinkStatus(LinkPhase.CONNECTED))
        svc.onState(cooking, 0)
        assertEquals(Notifier.FOLLOW_ID, s.lastForegroundNotificationId)
        assertEquals(Notifier.FOLLOW_CHANNEL, s.lastForegroundNotification.channelId)
        assertEquals(setOf(Notifier.FOLLOW_ID), posted())
        assertTrue(heldWakeLock())
        assertTrue(app.diag.text(), app.diag.text().contains("quelque chose a commencé"))
        // Elle se termine : retour à la notification discrète, même règle que les verrous.
        svc.onState(idle, 0)
        assertEquals(Notifier.IDLE_ID, s.lastForegroundNotificationId)
        assertEquals(setOf(Notifier.IDLE_ID), posted())
        assertFalse(heldWakeLock())
        assertTrue(app.diag.text(), app.diag.text().contains("rien en cours : notification de la cuisson retirée"))
        c.destroy()
    }

    @Test @Config(application = PairedTestApp::class)
    fun robotLostMidCookingKeepsTheCookingNotificationForTheGrace() {
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        val svc = c.get()
        svc.onStatus(LinkStatus(LinkPhase.CONNECTED))
        svc.onState(cooking, 0)
        svc.onStatus(LinkStatus(LinkPhase.UNREACHABLE))
        // Toujours « en cours » (délai de grâce de LockPolicy) : notification de la cuisson, texte mis à jour.
        assertEquals(setOf(Notifier.FOLLOW_ID), posted())
        val n = nm.activeNotifications.single().notification
        assertEquals(tr("Le robot ne répond pas"), n.extras.getCharSequence("android.text").toString())
        c.destroy()
    }

    /**
     * La liaison rejoue son dernier état connu à qui s'inscrit, même pendant une coupure : une cuisson
     * vue avant la coupure ne fait ni prendre les verrous ni montrer la notification de la cuisson.
     */
    @Test @Config(application = PairedTestApp::class)
    fun aStateReplayedWhileTheRobotIsAwayIsNotACookingInProgress() {
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        val svc = c.get()
        svc.onStatus(LinkStatus(LinkPhase.UNREACHABLE))
        svc.onState(cooking, 0)
        assertEquals(Notifier.IDLE_ID, shadowOf(svc).lastForegroundNotificationId)
        assertFalse(heldWakeLock())
        // Le robot répond de nouveau avec cette cuisson : elle est en cours.
        svc.onStatus(LinkStatus(LinkPhase.CONNECTED))
        svc.onState(cooking, 0)
        assertEquals(Notifier.FOLLOW_ID, shadowOf(svc).lastForegroundNotificationId)
        assertTrue(heldWakeLock())
        c.destroy()
    }

    @Test @Config(application = PairedTestApp::class)
    fun discreetNotificationIsNeverPostedAgainWhileIdle() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        val svc = c.get()
        // L'utilisateur la glisse.
        nm.cancel(Notifier.IDLE_ID)
        assertTrue(posted().isEmpty())
        // La liaison change de phase, des états de repos arrivent, contrôle et alarme : rien n'est reposé.
        svc.onStatus(LinkStatus(LinkPhase.UNREACHABLE))
        svc.onStatus(LinkStatus(LinkPhase.CONNECTING))
        svc.onStatus(LinkStatus(LinkPhase.CONNECTED))
        svc.onState(idle, 0)
        FollowService.recheck()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))
        assertTrue(posted().isEmpty())
        // Un nouveau démarrage (startForegroundService) doit un premier plan : elle revient.
        c.startCommand(0, 2)
        assertEquals(setOf(Notifier.IDLE_ID), posted())
        c.destroy()
    }

    @Test @Config(application = PairedTestApp::class)
    fun hiddenChannelIsNotedAndShownAgainWhenTheUserBringsItBack() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(app.notifier.idleHidden())
        // Canal coupé par l'utilisateur (Robolectric rend le canal qu'il garde : on le règle comme l'utilisateur).
        nm.getNotificationChannel(Notifier.IDLE_CHANNEL).importance = NotificationManager.IMPORTANCE_NONE
        assertTrue(app.notifier.idleHidden())
        val c = Robolectric.buildService(FollowService::class.java).create().startCommand(0, 1)
        val s = shadowOf(c.get())
        // Premier plan quand même, sans plantage.
        assertEquals(Notifier.IDLE_ID, s.lastForegroundNotificationId)
        assertFalse(s.isStoppedBySelf)
        assertTrue(app.diag.text(), app.diag.text().contains("masquée dans les réglages d'Android"))
        // Réaffiché : posée de nouveau au prochain recalcul, une fois.
        nm.getNotificationChannel(Notifier.IDLE_CHANNEL).importance = NotificationManager.IMPORTANCE_LOW
        assertFalse(app.notifier.idleHidden())
        nm.cancel(Notifier.IDLE_ID)
        FollowService.recheck()
        assertEquals(setOf(Notifier.IDLE_ID), posted())
        assertTrue(app.diag.text(), app.diag.text().contains("réaffichée"))
        nm.cancel(Notifier.IDLE_ID)
        FollowService.recheck()
        assertTrue(posted().isEmpty())
        c.destroy()
    }

    @Test @Config(application = PairedTestApp::class)
    fun startUsesStartForegroundService() {
        FollowService.start(app, "test")
        // startForegroundService : Robolectric le range avec les services démarrés ; le service doit un premier plan.
        assertEquals(service, startedService()?.component)
    }
}
