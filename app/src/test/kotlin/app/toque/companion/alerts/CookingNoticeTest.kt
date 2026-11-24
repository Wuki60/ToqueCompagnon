package app.toque.companion.alerts

import app.toque.companion.WireFixtures
import app.toque.companion.WireFixtures.can
import app.toque.companion.WireFixtures.job
import app.toque.companion.WireFixtures.no
import app.toque.companion.WireFixtures.paused
import app.toque.companion.WireFixtures.session
import app.toque.companion.WireFixtures.state
import app.toque.companion.WireFixtures.yes
import app.toque.companion.link.LinkPhase
import app.toque.core.companion.Permission
import app.toque.core.companion.WaitingWire
import org.junit.Assert.assertEquals
import org.junit.Test

/** La notification de la cuisson : titre, étape et état, boutons selon les permissions du robot. */
class CookingNoticeTest {
    private fun describe(s: app.toque.core.companion.StateWire?, phase: LinkPhase = LinkPhase.CONNECTED, sending: Boolean = false) =
        CookingNotices.describe(s, phase, sending)

    @Test fun recipeNameInTheTitleThenStepAndState() {
        val running = job(label = "Velouté de potimarron")
        val n = describe(state(job = running, session = session(4, "nothing", running = listOf(4))))
        assertEquals(CookingNotice("Velouté de potimarron", "Étape 5 sur 6 · Cuisson", 760_000), n)
        // Une étape de programme : son nom.
        val knead = job(kind = "KNEAD", kindLabel = "Pétrir", label = "Velouté de potimarron", phaseCount = 3)
        assertEquals("Étape 5 sur 6 · Pétrir", describe(state(job = knead, session = session(4, "nothing", running = listOf(4)))).text)
        // En pause : la raison donnée par le robot, et plus de compte à rebours.
        val p = describe(state(job = paused(running, reason = "LID", label = "Couvercle ouvert"), session = session(4, "resume")))
        assertEquals(CookingNotice("Velouté de potimarron", "Étape 5 sur 6 · Couvercle ouvert"), p)
        assertEquals("Étape 5 sur 6 · En pause", describe(state(job = paused(running, label = ""), session = session(4, "resume"))).text)
    }

    @Test fun whatTheRobotWaitsForBetweenSteps() {
        fun text(current: Int, waitingFor: String) = describe(state(session = session(current, waitingFor))).text
        assertEquals("Étape 2 sur 6 · Attend : pesée (Beurre)", text(1, "weigh"))
        assertEquals("Étape 1 sur 6 · Attend : étape à la main", text(0, "hand"))
        assertEquals("Étape 3 sur 6 · Attend : lancez l'étape sur le robot", text(2, "launch"))
        assertEquals("Étape 6 sur 6 · Attend : confirmez le mixage sur le robot", text(5, "hot_blend"))
        assertEquals("Étape 6 sur 6 · Attend : terminez sur le robot", text(5, "finish"))
        assertEquals("Étape 3 sur 6", text(2, "nothing"))
        // Pas de bouton dans une recette guidée.
        assertEquals(emptyList<NoticeAction>(), describe(state(session = session(1, "weigh"))).actions)
    }

    @Test fun manualCookingShowsItsSettingsAndItsButtons() {
        val running = job(speed = 2, tempC = 90)
        val n = describe(state(job = running))
        assertEquals("Cuisson manuelle", n.title)
        assertEquals("90 °C · vitesse 2", n.text)
        assertEquals(760_000L, n.remainingMs)
        assertEquals(listOf(NoticeAction.PAUSE, NoticeAction.ADD_MINUTE), n.actions)
        assertEquals("sans chauffe · sans mélange", describe(state(job = job(speed = 0, tempC = 0))).text)
        // En pause par l'utilisateur, reprise permise : « Reprendre ».
        val userPause = paused(running)
        assertEquals(listOf(NoticeAction.RESUME, NoticeAction.ADD_MINUTE),
            describe(state(job = userPause, can = can(userPause, resume = yes))).actions)
        // Couvercle ouvert pendant la pause : la reprise reste sur le robot, pas de bouton « Reprendre ».
        val lid = paused(running, reason = "LID", label = "Couvercle ouvert")
        val l = describe(state(job = lid, can = can(lid, resume = no("lid_opened", "Couvercle ouvert : reprenez sur le robot"))))
        assertEquals("Couvercle ouvert", l.text)
        assertEquals(listOf(NoticeAction.ADD_MINUTE), l.actions)
        // Le robot refuse le réglage : pas de « +1 min ».
        assertEquals(listOf(NoticeAction.PAUSE),
            describe(state(job = running, can = can(running).copy(adjust = no("fault", "Alerte en cours")))).actions)
        // Une demande en route : pas de bouton jusqu'à la réponse.
        assertEquals(emptyList<NoticeAction>(), describe(state(job = running), sending = true).actions)
        // Arrêt en cours : rien à régler.
        val stopping = running.copy(state = "STOPPING", counting = false)
        assertEquals(CookingNotice("Cuisson manuelle", "Arrêt en cours"), describe(state(job = stopping,
            can = can(stopping).copy(pause = no("nothing_running", ""), adjust = Permission(false)))))
    }

    @Test fun anOlderRobotGivesNoButtons() {
        val running = job()
        val old = can(running).copy(pause = Permission(false), resume = Permission(false), adjust = Permission(false))
        assertEquals(emptyList<NoticeAction>(), describe(state(job = running, can = old)).actions)
    }

    @Test fun programsWaitingRecipeAndLink() {
        val knead = job(kind = "KNEAD", kindLabel = "Pétrir", label = "Pétrir", phaseCount = 3)
        val k = describe(state(job = knead))
        assertEquals(CookingNotice("Pétrir", "En cours", 760_000), k)
        val w = WaitingWire("essai-1", "Pâte à pizza", 4, 7, 2, sentAt = WireFixtures.T)
        assertEquals(CookingNotice("Pâte à pizza", "Recette reçue : lancez-la sur le robot"), describe(state(waiting = w)))
        assertEquals(CookingNotice("Toque Compagnon suit le robot", "Le robot ne répond pas"), describe(state(job = job()), LinkPhase.UNREACHABLE))
        assertEquals(CookingNotice("Toque Compagnon suit le robot", "Connexion au robot"), describe(null, LinkPhase.CONNECTING))
    }

    /** Ni la température mesurée ni le poids : en arrière-plan ils ne réveillent pas le téléphone. */
    @Test fun neverUsesTelemetry() {
        val running = job()
        val a = state(job = running, session = session(4, "nothing", running = listOf(4)))
        val b = a.copy(machine = a.machine.copy(tempC = 97, weightG = 640, scaleStable = false, heaterOn = true))
        assertEquals(describe(a), describe(b))
        val m = state(job = running)
        assertEquals(describe(m), describe(m.copy(machine = m.machine.copy(tempC = 88, weightG = 12))))
    }
}
