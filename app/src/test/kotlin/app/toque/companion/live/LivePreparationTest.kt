package app.toque.companion.live

import app.toque.companion.WireFixtures
import app.toque.companion.WireFixtures.input
import app.toque.companion.WireFixtures.job
import app.toque.companion.WireFixtures.no
import app.toque.companion.WireFixtures.ready
import app.toque.companion.WireFixtures.session
import app.toque.companion.WireFixtures.state
import app.toque.companion.ui.Route
import app.toque.companion.ui.screens.ManuelState
import app.toque.companion.ui.screens.ReposState
import app.toque.core.companion.Permission
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lancement d'une cuisson manuelle depuis le téléphone : Repos, Manuel en préparation, puis en direct. */
class LivePreparationTest {
    private fun present(i: LiveInput) = LivePresenter.present(i)

    @Test fun reposOffersManualCookingWithTheRobotReason() {
        val ok = present(input(state())).screen as ReposState
        assertTrue(ok.canStartManual)
        val lid = present(input(state(machine = ready.copy(lidOpen = true),
            can = WireFixtures.can(null).copy(start = no("lid", "Fermez le couvercle sur le robot"))))).screen as ReposState
        assertFalse(lid.canStartManual)
        assertEquals("Fermez le couvercle sur le robot", lid.startManualReason)
        // Robot plus ancien : la permission n'existe pas chez lui.
        val old = present(input(state(can = WireFixtures.can(null).copy(start = Permission(false))))).screen as ReposState
        assertFalse(old.canStartManual)
        assertEquals("Mettez Toque à jour sur le robot", old.startManualReason)
    }

    @Test fun preparationShowsTheDraftOnTheManuelScreen() {
        val v = present(input(state(machine = ready.copy(tempC = 24))).copy(prep = ManualSettings()))
        assertEquals(Route.MANUEL, v.route)
        val m = v.screen as ManuelState
        assertTrue(m.preparing)
        assertEquals(60_000L, m.totalMs)
        assertEquals(0, m.targetC)
        assertEquals(2, m.speed)
        assertFalse(m.reverse)
        assertFalse(m.whisk)
        assertEquals(24, m.bowlC)
        assertTrue(m.lidClosed && m.bowlInPlace)
        assertTrue(m.canStart)
        assertEquals("", m.hint)
        assertEquals("max 99 min", m.durationNote)
        assertFalse(m.canPause || m.canStop)
    }

    @Test fun preparationHintsAndRefusals() {
        val s = state(machine = ready.copy(tempC = 24))
        // Sans mélange : le rappel du robot.
        val noStir = present(input(s).copy(prep = ManualSettings(speed = 0, tempLevel = 13))).screen as ManuelState
        assertEquals("Sans mélange : surveillez la cuisson", noStir.hint)
        assertTrue(noStir.canStart)
        // Bol chaud, vitesse 4 : à lancer sur le robot.
        val hot = present(input(state(machine = ready.copy(tempC = 72, hot = true))).copy(prep = ManualSettings(speed = 5))).screen as ManuelState
        assertEquals("Contenu chaud : à lancer sur le robot", hot.hint)
        assertFalse(hot.canStart)
        // Le robot refuse le lancement : sa raison, bouton grisé.
        val lid = present(input(state(machine = ready.copy(lidOpen = true), can = WireFixtures.can(null).copy(
            start = no("lid", "Fermez le couvercle sur le robot")))).copy(prep = ManualSettings())).screen as ManuelState
        assertEquals("Fermez le couvercle sur le robot", lid.hint)
        assertFalse(lid.lidClosed)
        assertFalse(lid.canStart)
        // Démarrage en route : bouton grisé, réglages figés.
        val going = present(input(s, pending = setOf(Pending.START)).copy(prep = ManualSettings(accessory = Accessory.WHISK))).screen as ManuelState
        assertTrue(going.starting)
        assertFalse(going.canStart)
        assertFalse(going.canAdjust)
        assertTrue(going.whisk)
    }

    @Test fun aRunningJobOrARecipeTakesOverThePreparation() {
        val live = present(input(state(job = job(speed = 2, tempC = 90))).copy(prep = ManualSettings()))
        assertEquals(Route.MANUEL, live.route)
        assertFalse((live.screen as ManuelState).preparing)
        assertEquals(Route.ETAPES, present(input(state(session = session(0, "hand"))).copy(prep = ManualSettings())).route)
    }

    @Test fun aLocalInputShowsInsteadOfTheRobotValues() {
        val j = job(speed = 1, tempC = 100, totalMs = 1_200_000, remainingMs = 760_000)
        val m = present(input(state(job = j)).copy(manual = ManualOverride(3, 95, true, 1_260_000))).screen as ManuelState
        assertEquals(3, m.speed)
        assertEquals(95, m.targetC)
        assertTrue(m.reverse)
        assertEquals(1_260_000L, m.totalMs)
        assertEquals(820_000L, m.remainingMs)
        // Une demande en route ne grise pas les réglages : les appuis s'accumulent.
        assertTrue((present(input(state(job = j), pending = setOf(Pending.ADJUST))).screen as ManuelState).canAdjust)
    }
}
