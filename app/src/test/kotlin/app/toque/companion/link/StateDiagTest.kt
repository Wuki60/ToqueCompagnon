package app.toque.companion.link

import app.toque.companion.WireFixtures.job
import app.toque.companion.WireFixtures.paused
import app.toque.companion.WireFixtures.ready
import app.toque.companion.WireFixtures.session
import app.toque.companion.WireFixtures.state
import app.toque.core.companion.WaitingWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les états dans le diagnostic : une ligne par changement qui compte, un compte par minute au plus. */
class StateDiagTest {
    private val d = StateDiag()

    @Test fun onlyChangesThatMatterWriteALine() {
        val cooking = state(job = job(), session = session(4, "nothing", running = listOf(4)), revision = 100, journalSeq = 17)
        assertEquals(listOf("état n° 100, journal 17, programme MANUAL RUNNING, recette étape 5/${cooking.session!!.stepCount}"),
            d.onState(cooking, 0))
        // Température, poids, minuteur : rien pendant une minute.
        for (i in 1..50) {
            val s = state(job = job(remainingMs = 760_000L - i * 1_000), session = session(4, "nothing", running = listOf(4)),
                machine = ready.copy(tempC = 60 + i % 30, weightG = i), revision = 100L + i, journalSeq = 17)
            assertEquals(emptyList<String>(), d.onState(s, i * 1_000L))
        }
        // Une minute passée : une ligne de compte, pour voir que les états arrivent.
        val tick = state(job = job(), session = session(4, "nothing", running = listOf(4)), revision = 151, journalSeq = 17)
        assertEquals(listOf("51 état(s) sans changement notable en 60 s (dernier n° 151)"), d.onState(tick, 60_000))
        assertEquals(emptyList<String>(), d.onState(tick.copy(revision = 152), 61_000))
        // Nouvelle ligne du journal (« température atteinte ») : écrite, avec le compte depuis la précédente.
        assertEquals(listOf("état n° 153, journal 18, programme MANUAL RUNNING, recette étape 5/${cooking.session!!.stepCount} (1 sans changement notable avant)"),
            d.onState(tick.copy(revision = 153, journalSeq = 18), 62_000))
        // Pause, étape suivante, recette en attente, fin : chacune écrite.
        assertEquals(1, d.onState(state(job = paused(job()), session = session(4, "nothing"), revision = 160, journalSeq = 18), 63_000).size)
        assertEquals(1, d.onState(state(session = session(5, "hand"), revision = 161, journalSeq = 18), 64_000).size)
        val w = WaitingWire("r1", "Gratin", 4, 30, 6, sentAt = 0)
        val waiting = d.onState(state(waiting = w, revision = 162, journalSeq = 19), 65_000)
        assertEquals(listOf("état n° 162, journal 19, pas de programme, recette en attente sur le robot"), waiting)
        assertEquals(1, d.onState(state(revision = 163, journalSeq = 19), 66_000).size)
    }

    @Test fun aLinkChangeWritesTheNextState() {
        val s = state(revision = 5)
        assertEquals(1, d.onState(s, 0).size)
        assertEquals(0, d.onState(s.copy(revision = 6), 1_000).size)
        d.reset()
        assertEquals(listOf("état n° 7, journal 17, pas de programme (1 sans changement notable avant)"), d.onState(s.copy(revision = 7), 2_000))
    }

    @Test fun waitingStepIsShown() {
        val line = d.onState(state(session = session(2, "weigh"), revision = 9), 0).single()
        assertTrue(line, line.endsWith("recette étape 3/${session(2, "weigh").stepCount} (attend : weigh)"))
    }
}
