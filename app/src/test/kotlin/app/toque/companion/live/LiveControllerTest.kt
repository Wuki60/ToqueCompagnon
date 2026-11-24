package app.toque.companion.live

import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import app.toque.companion.CompanionLanguages
import app.toque.companion.R
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Route
import app.toque.companion.ui.Screens
import app.toque.companion.ui.Shell
import app.toque.companion.ui.Ui
import app.toque.companion.ui.screens.FinState
import app.toque.companion.ui.screens.MainState
import app.toque.companion.ui.screens.ManuelState
import app.toque.companion.ui.screens.ReposState
import app.toque.core.machine.JobState
import app.toque.core.machine.Programs
import app.toque.core.protocol.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Le contrôleur « En direct » contre un vrai hub sur base simulée : préparation et lancement d'une
 * cuisson manuelle depuis Repos, puis réglages en direct (appuis groupés, pas de retour en arrière,
 * refus qui rend les valeurs du robot).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-port-xhdpi")
class LiveControllerTest {
    private lateinit var robot: SimRobot
    private lateinit var link: RobotLink
    private lateinit var shell: Shell
    private lateinit var live: LiveController
    private val main = Handler(Looper.getMainLooper())

    @Before fun setUp() {
        val c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Companion)
        Ui.init(c)
        CompanionLanguages.install(c, "fr")
        LiveController.forgetDraft()
        robot = SimRobot().start()
        link = RobotLink(MemoryLinkStore(), post = { main.post(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(robot.port), waitSeconds = 2, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500)
        var paired: PairResult? = null
        link.pairWithCode(robot.hub.pairing.open().code) { paired = it }
        until("appairé") { paired != null }
        assertTrue("$paired", paired is PairResult.Paired)
        shell = Shell(c)
        live = LiveController(link, shell)
        val screens = Screens(c, live, shell)
        shell.screens = screens
        live.screens = screens
        live.attach()
    }

    @After fun tearDown() {
        live.detach()
        link.close()
        robot.shutdown()
    }

    /** Laisse tourner le fil principal (réponses du réseau) jusqu'à [cond]. */
    private fun until(what: String, timeoutMs: Long = 15_000, each: () -> Unit = {}, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            each()
            if (cond()) return
            assertTrue("délai dépassé : $what", System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private val view get() = live.lastView!!
    private val manuel get() = view.screen as ManuelState

    @Test fun startFromReposThenAdjustLive() {
        until("robot au repos, lancement permis") { (view.screen as? ReposState)?.canStartManual == true }

        // Préparation : le même écran Manuel, réglage local avec les règles du robot.
        live.openManual()
        assertEquals(Route.MANUEL, view.route)
        assertTrue(manuel.preparing)
        assertEquals(60_000L, manuel.totalMs)
        repeat(18) { live.stepDuration(1) } // 60 s → 10 min par 30 s
        repeat(10) { live.stepDuration(1) } // puis par minute : 20 min
        live.adjustSpeed(1)
        assertEquals(1_200_000L, manuel.totalMs)
        assertEquals(3, manuel.speed)
        live.adjustTemp(1)
        live.adjustTemp(-1)
        assertTrue(manuel.canStart)
        // Retour à Repos puis de nouveau : le brouillon est gardé.
        live.closeManual()
        assertEquals(Route.REPOS, view.route)
        live.openManual()
        assertEquals(3, manuel.speed)

        // « Démarrer » : la page en direct prend la suite sans repasser par Repos.
        live.startManual()
        assertTrue(manuel.starting)
        val routes = ArrayList<Route>()
        until("cuisson en cours", each = { routes += view.route }) {
            view.route == Route.MANUEL && !manuel.preparing && robot.snapshot.state == JobState.RUNNING
        }
        assertFalse(routes.toString(), Route.REPOS in routes)
        assertEquals(1_200_000L, robot.snapshot.totalMs)
        assertEquals(3, robot.snapshot.jobSpeed)

        // Deux appuis rapides : affichés aussitôt, envoyés ensemble, aucun perdu ni annulé à l'écran.
        live.adjustSpeed(-1)
        live.adjustSpeed(-1)
        assertEquals(1, manuel.speed)
        val shown = ArrayList<Int>()
        idleFor(400)
        until("vitesse appliquée par le robot", each = { shown += manuel.speed }) {
            robot.snapshot.jobSpeed == 1 && !liveBusy()
        }
        assertEquals(shown.toString(), setOf(1), shown.toSet())

        // Refus du robot (vitesse 7 au-delà de 15 min) : ses valeurs reviennent, avec son message.
        repeat(6) { live.adjustSpeed(1) }
        assertEquals(7, manuel.speed)
        idleFor(400)
        until("refus") { shell.noticeText != null && !liveBusy() }
        assertEquals(1, manuel.speed)
        assertEquals(1, robot.snapshot.jobSpeed)
        assertTrue(shell.noticeText!!, shell.noticeText!!.contains("15 min"))

        // La durée, par minute, depuis le téléphone.
        live.stepDuration(1)
        assertEquals(1_260_000L, manuel.totalMs)
        idleFor(400)
        until("durée appliquée") { robot.snapshot.totalMs == 1_260_000L && !liveBusy() }
        assertEquals(1_260_000L, manuel.totalMs)
        live.stop()
    }

    @Test fun aRefusedStartStaysInPreparationWithTheRobotMessage() {
        until("robot au repos") { (view.screen as? ReposState)?.canStartManual == true }
        live.openManual()
        // Le couvercle s'ouvre après l'affichage : le robot refuse.
        robot.board.lidOpen = true
        // Le robot doit avoir vu le couvercle ouvert (une trame de la base, toutes les 100 ms) avant la
        // demande : lancé plus tôt, il accepte, puis met en pause à l'ouverture, et aucun refus ne vient
        // (ce que cet essai attendait alors en vain, sur une machine chargée).
        until("couvercle vu ouvert par le robot") { robot.snapshot.lidOpen }
        live.startManual()
        until("refus du lancement") { shell.noticeText != null }
        // Refusé par la règle du compagnon, ou par la machine si l'instantané n'avait pas encore le couvercle ouvert.
        assertTrue(shell.noticeText, shell.noticeText in setOf("Fermez le couvercle sur le robot", "Fermez et verrouillez le couvercle"))
        assertTrue(manuel.preparing)
        until("raison affichée") { manuel.hint == "Fermez le couvercle sur le robot" }
        assertFalse(manuel.canStart)
        assertFalse(robot.snapshot.jobActive)
    }

    /** Arrêt depuis le téléphone, puis l'étape « OK » comme sur le robot : en attente, puis Repos. */
    @Test fun okAfterAStopClosesTheEndedJobOnTheRobot() {
        until("robot au repos") { (view.screen as? ReposState)?.canStartManual == true }
        assertTrue(robot.loop.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 120_000)) }.ok)
        until("cuisson en cours") { view.route == Route.MANUEL }
        live.stop()
        until("fin affichée") { view.route == Route.FIN }
        val fin = view.screen as FinState
        assertFalse(fin.finished)
        assertEquals("Arrêté", fin.end)
        assertTrue(fin.canOk)
        assertEquals(JobState.ABORTED, robot.snapshot.state)

        live.clearEnded()
        // Bouton grisé tant que la réponse n'est pas là.
        assertFalse((view.screen as FinState).canOk)
        until("repos") { view.route == Route.REPOS }
        assertFalse(robot.snapshot.jobActive)
        assertEquals(null, shell.noticeText)
    }

    /** OK refusé (le robot a déjà fermé le programme de son côté) : le message du robot, puis Repos. */
    @Test fun aRefusedOkShowsTheRobotMessage() {
        until("robot au repos") { (view.screen as? ReposState)?.canStartManual == true }
        assertTrue(robot.loop.call { it.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 120_000)) }.ok)
        until("cuisson en cours") { view.route == Route.MANUEL }
        assertTrue(robot.loop.call { it.stop() }.ok)
        until("fin affichée") { view.route == Route.FIN }
        // OK sur le robot ; l'appui sur le téléphone arrive après : le robot refuse, rien ne change.
        robot.loop.call { it.clearFinished() }
        until("instantané sans programme") { !robot.snapshot.jobActive }
        live.clearEnded()
        until("refus") { shell.noticeText != null }
        assertEquals("Aucun programme terminé à fermer", shell.noticeText)
        until("repos") { view.route == Route.REPOS }
    }

    private fun liveBusy(): Boolean {
        val f = LiveController::class.java.getDeclaredField("tuner").apply { isAccessible = true }
        return (f.get(live) as ManualTuner).busy
    }

    /** « Arrêter la recette » confirmé sur le téléphone : la recette guidée se ferme sur le robot, la cuisson s'arrête. */
    @Test fun stopTheGuidedRecipeFromThePhone() {
        robot.openRecipe(2, app.toque.core.companion.WaitingFor.LAUNCH)
        until("étapes, arrêt proposé") { view.etapes?.stopRecipeOffered == true && view.etapes?.canStopRecipe == true }
        until("base prête") { robot.snapshot.ready }
        assertTrue(robot.loop.call { it.startJob(Programs.manual(1, 0, Direction.CLOCKWISE, 120_000, label = robot.recipe.title)) }.ok)
        robot.hub.updateSession(app.toque.core.companion.CookSession.of(robot.recipe, 2, running = listOf(2),
            waitingFor = app.toque.core.companion.WaitingFor.NOTHING))
        until("étape en cours") { view.route == Route.MAIN && (view.screen as MainState).stopRecipeOffered }
        live.stopRecipe()
        // En route : le bouton est grisé (pas deux demandes).
        assertFalse((view.screen as? MainState)?.canStopRecipe ?: false)
        until("recette arrêtée") { robot.stopRecipeRequests.get() == 1 && link.latest?.session == null && view.etapes == null }
        until("cuisson arrêtée") { robot.snapshot.state == JobState.ABORTED }
        assertEquals(1, robot.stopRecipeRequests.get())
    }

    /** Un robot plus ancien ne sait pas arrêter la recette depuis le téléphone : pas de bouton. */
    @Test fun anOlderRobotOffersNoStopRecipe() {
        robot.older = true
        robot.openRecipe(0)
        until("étapes") { view.etapes != null && view.route == Route.ETAPES }
        assertFalse(view.etapes!!.stopRecipeOffered)
        live.stopRecipe()
        until("refus montré") { shell.noticeText != null }
        // Le hub refuse sans rien demander à l'écran du robot.
        assertEquals(0, robot.stopRecipeRequests.get())
    }
}
