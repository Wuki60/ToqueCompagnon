package app.toque.companion.live

import app.toque.companion.WireFixtures.job
import app.toque.companion.WireFixtures.state
import app.toque.companion.link.CommandOutcome
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.JobWire
import app.toque.core.companion.StateWire
import app.toque.core.machine.ManualSettings.Companion.CARD_SPEED
import app.toque.core.machine.ManualSettings.Companion.CARD_TEMP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saisie de la cuisson manuelle en cours sur le téléphone : appuis groupés, affichage immédiat, pas de retour en arrière. */
class ManualTunerTest {
    private var now = 0L
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val sent = ArrayList<Pair<AdjustRequest, (CommandOutcome) -> Unit>>()
    private val notices = ArrayList<String>()
    private val tuner = ManualTuner(
        send = { r, done -> sent += r to done },
        schedule = { ms, b -> val t = (now + ms) to b; timers += t; { timers.remove(t) } },
        mono = { now }, notice = { notices += it }, changed = {},
    )

    private fun advance(ms: Long) {
        now += ms
        for (t in timers.filter { it.first <= now }) {
            timers.remove(t)
            t.second()
        }
    }

    private var revision = 40L
    private fun robot(j: JobWire): StateWire = state(job = j, revision = ++revision)

    // 20 min à 100 °C, vitesse 1 (WireFixtures.job()).
    private val base = job(speed = 1, tempC = 100, totalMs = 1_200_000, remainingMs = 760_000)

    @Test fun quickTapsAccumulateShowAtOnceAndLeaveAsOneRequest() {
        tuner.onState(robot(base))
        assertNull(tuner.override)
        tuner.step(CARD_SPEED, 1)
        assertEquals(2, tuner.override!!.speed)
        advance(100)
        tuner.step(CARD_SPEED, 1)
        tuner.time(1)
        tuner.time(1)
        tuner.step(CARD_TEMP, -1)
        val o = tuner.override!!
        assertEquals(3, o.speed)
        assertEquals(95, o.tempC)
        assertEquals(1_320_000L, o.totalMs)
        // Rien ne part avant 350 ms sans appui.
        advance(300)
        assertTrue(sent.isEmpty())
        advance(60)
        assertEquals(1, sent.size)
        assertEquals(AdjustRequest(speed = 3, tempC = 95, addMs = 120_000), sent[0].first)
    }

    @Test fun tapsDuringARequestAreKeptAndSentAfterIt() {
        tuner.onState(robot(base))
        tuner.step(CARD_SPEED, 1)
        advance(400)
        assertEquals(1, sent.size)
        // Pendant que la demande est en route.
        tuner.step(CARD_SPEED, 1)
        tuner.time(1)
        advance(400)
        assertEquals(1, sent.size)
        assertEquals(3, tuner.override!!.speed)
        sent[0].second(CommandOutcome(true, revision + 1))
        assertEquals(2, sent.size)
        assertEquals(AdjustRequest(speed = 3, addMs = 60_000), sent[1].first)
    }

    @Test fun noFlickerBackToTheOldValueBeforeTheRobotShowsTheChange() {
        tuner.onState(robot(base))
        tuner.step(CARD_SPEED, 1)
        advance(400)
        // Un état d'avant la réponse : toujours la saisie.
        tuner.onState(robot(base))
        assertEquals(2, tuner.override!!.speed)
        sent[0].second(CommandOutcome(true, revision + 2))
        // Réponse reçue, état de la révision annoncée pas encore là.
        tuner.onState(robot(base))
        assertEquals(2, tuner.override!!.speed)
        // Révision atteinte, mais l'instantané de la base ne montre pas encore le réglage (il suit de peu).
        tuner.onState(robot(base))
        assertEquals(2, tuner.override!!.speed)
        // L'état montre le réglage : l'écran suit de nouveau le robot.
        tuner.onState(robot(base.copy(speed = 2)))
        assertNull(tuner.override)
    }

    @Test fun afterTheGraceTheRobotValuesWinEvenIfDifferent() {
        tuner.onState(robot(base))
        tuner.step(CARD_SPEED, 1)
        advance(400)
        sent[0].second(CommandOutcome(true, revision))
        tuner.onState(robot(base))
        assertEquals(2, tuner.override!!.speed)
        advance(ManualTuner.GRACE_MS)
        tuner.tick()
        assertNull(tuner.override)
    }

    @Test fun refusalRestoresTheRobotValuesWithItsMessage() {
        tuner.onState(robot(base))
        tuner.step(CARD_SPEED, 1)
        advance(400)
        tuner.step(CARD_SPEED, 1)
        sent[0].second(CommandOutcome(false, 0, "hot_blend", "Contenu chaud : changez la vitesse sur le robot"))
        assertNull(tuner.override)
        assertEquals(listOf("Contenu chaud : changez la vitesse sur le robot"), notices)
        // L'appui fait pendant la demande refusée ne part pas.
        advance(1_000)
        assertEquals(1, sent.size)
    }

    @Test fun robotInputRulesApplyWithTheirMessages() {
        tuner.onState(robot(base.copy(speed = 3)))
        tuner.step(CARD_SPEED, 1)
        assertNull(tuner.override)
        assertEquals(listOf("En chauffe, vitesse 3 maximum"), notices)
        tuner.reverse(true)
        assertTrue(tuner.override!!.reverse)
        advance(400)
        assertEquals(AdjustRequest(reverse = true), sent[0].first)
        // Durée : pas sous le temps écoulé plus 5 s, jamais au-delà de 99 min.
        val t = ManualTuner({ _, _ -> }, { _, _ -> {} }, { 0L }, {}, {})
        t.onState(robot(base.copy(totalMs = 445_000, remainingMs = 5_000, elapsedMs = 440_000)))
        t.time(-1)
        assertNull(t.override)
        t.onState(robot(base.copy(totalMs = 99 * 60_000L, remainingMs = 60_000)))
        t.time(1)
        assertNull(t.override)
    }

    /** Smart : une cuisson lancée sur son écran à 40 °C (hors de la table de Toque) se règle d'ici à partir de 40 °C. */
    @Test fun smartFortyDegreesStepsFromForty() {
        val smart = job(speed = 1, tempC = 40, totalMs = 1_200_000, remainingMs = 760_000).copy(tempLevel = 1)
        val st = state(job = smart, revision = ++revision)
        tuner.onState(st.copy(robot = st.robot.copy(model = "mc-smart"), limits = app.toque.companion.WireFixtures.limits.copy(heatNoStir = false)))
        // 37 °C, pas « sans chauffe » : le cran d'en dessous de 40 °C.
        tuner.step(CARD_TEMP, -1)
        assertEquals(37, tuner.override!!.tempC)
        tuner.step(CARD_TEMP, 1)
        assertEquals(45, tuner.override!!.tempC)
    }

    @Test fun anotherJobDropsTheInput() {
        tuner.onState(robot(base))
        tuner.step(CARD_SPEED, 1)
        tuner.onState(robot(base.copy(startedAt = base.startedAt + 1, label = "Autre")))
        assertNull(tuner.override)
        advance(400)
        assertTrue(sent.isEmpty())
    }
}
