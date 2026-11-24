package app.toque.companion.flows

import app.toque.companion.WireFixtures.T
import app.toque.companion.WireFixtures.UTC
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.screens.JournalFilter
import app.toque.core.companion.JournalPage
import app.toque.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Onglet Journal : la ligne « Température atteinte » d'une cuisson manuelle, avec son heure. */
class JournalTemperatureTest {
    @Test fun manualCookingShowsWhenItReachedItsTemperature() {
        val m = 60_000L
        @Suppress("UNCHECKED_CAST")
        val page = JournalPage.fromMap(Json.parse("""
            {"lastSeq":3,"entries":[
             {"seq":3,"at":${T - 2 * m},"kind":"job_finished","text":"Terminé : Cuisson manuelle","alert":"job_end","origin":{"kind":"robot"},"activityId":1,"stepIndex":-1,"elapsedMs":600000,"detail":""},
             {"seq":2,"at":${T - 8 * m},"kind":"temp_reached","text":"Température atteinte : 100 °C","alert":"temp_reached","origin":{"kind":"robot"},"activityId":1,"stepIndex":-1,"elapsedMs":240000,"detail":""},
             {"seq":1,"at":${T - 12 * m},"kind":"job_started","text":"Démarrage sur le robot : Cuisson manuelle","alert":"","origin":{"kind":"robot"},"activityId":1,"stepIndex":-1,"elapsedMs":0,"detail":""}],
             "activities":[{"id":1,"kind":"manual","title":"Cuisson manuelle","recipeId":"","stepCount":0,"handSteps":0,"startedAt":${T - 12 * m},"endedAt":${T - 2 * m},"outcome":"finished","summary":"10 min · 100 °C · vitesse 1","totalMs":600000,"endOrigin":null}]}""") as Map<String, Any?>)
        val flow = JournalFlow(RobotLink(MemoryLinkStore(), post = { it.run() }), {}, PhoneClock(wallMs = { T }, zone = { UTC }))
        val entry = flow.present(page, JournalFilter.ALL).single().entries.single()
        assertEquals("Température atteinte : 100 °C à 11:52", entry.note)
        flow.close()
    }
}
