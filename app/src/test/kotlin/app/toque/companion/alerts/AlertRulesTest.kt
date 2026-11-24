package app.toque.companion.alerts

import app.toque.core.companion.ActivityWire
import app.toque.core.companion.JournalEntry
import app.toque.core.companion.JournalPage
import app.toque.core.companion.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quelles lignes du journal notifier, une fois chacune ; quand prévenir d'une panne ; quand arrêter le service. */
class AlertRulesTest {
    private val t0 = 1_795_262_400_000L
    private val all = AlertKind.values().toSet()

    private fun e(seq: Long, kind: String, alert: String, at: Long = t0 + seq * 1_000, self: Boolean = false, text: String = "ligne $seq") =
        JournalEntry(seq, at, kind, text, alert, if (self) Origin(Origin.PHONE, "1eeddd36", "Téléphone 1", true) else Origin(), activityId = 1)

    /** Page comme l'API la renvoie : les plus récentes d'abord. */
    private fun page(vararg entries: JournalEntry) = JournalPage(entries.maxOfOrNull { it.seq } ?: 0, entries.sortedByDescending { it.seq },
        listOf(ActivityWire(1, "recipe", "Velouté de potimarron")))

    @Test fun nothingBeforeFollowingStarted() {
        val r = JournalAlerts()
        // Premier état : la base, rien à lire.
        assertNull(r.onState(17, t0))
        assertEquals(17L, r.lastSeq)
        // Même numéro : rien de neuf.
        assertNull(r.onState(17, t0 + 1_000))
        assertEquals(17L, r.onState(19, t0 + 2_000))
        // La page contient aussi d'anciennes lignes (limite, réponse plus large) : seules 18 et 19 comptent.
        val out = r.onPage(page(e(16, "paused", "pause"), e(17, "waiting", "waiting"), e(18, "step_finished", "step_end"),
            e(19, "paused", "pause")), all)
        assertEquals(listOf(18L, 19L), out.map { it.seq })
        assertEquals(listOf(AlertKind.END, AlertKind.PAUSE), out.map { it.kind })
        assertEquals("Velouté de potimarron", out[0].title)
        assertEquals("ligne 18", out[0].text)
    }

    @Test fun eachLineOnce() {
        val r = JournalAlerts()
        r.onState(0, t0)
        assertEquals(0L, r.onState(2, t0))
        val p = page(e(1, "waiting", "waiting"), e(2, "job_finished", "job_end"))
        assertEquals(2, r.onPage(p, all).size)
        // La même page relue (réponse en double, nouvel état sans nouvelle ligne) : rien.
        assertTrue(r.onPage(p, all).isEmpty())
        assertNull(r.onState(2, t0))
        assertEquals(2L, r.onState(3, t0))
        assertEquals(listOf(3L), r.onPage(page(e(3, "fault", "fault"), e(2, "job_finished", "job_end")), all).map { it.seq })
    }

    @Test fun ownActionsSwitchedOffKindsAndPlainLinesAreSilent() {
        val r = JournalAlerts()
        r.onState(0, t0)
        r.onState(5, t0)
        val out = r.onPage(page(
            e(1, "paused", "pause", self = true),
            e(2, "resumed", ""),
            e(3, "adjusted", ""),
            e(4, "waiting", "waiting"),
            e(5, "job_stopped", "job_end"),
        ), setOf(AlertKind.END))
        assertEquals(listOf(5L), out.map { it.seq })
        // Les lignes silencieuses sont quand même vues : pas de rattrapage plus tard.
        assertEquals(5L, r.lastSeq)
    }

    @Test fun robotAppRestartNotifiesOnlyNewerLines() {
        val r = JournalAlerts()
        r.onState(40, t0)
        r.onState(41, t0 + 5_000)
        r.onPage(page(e(41, "waiting", "waiting", at = t0 + 5_000)), all)
        // L'appli du robot redémarre : numéros repartis de 1, journal en mémoire perdu.
        val after = r.onState(2, t0 + 60_000)
        assertEquals(0L, after)
        val out = r.onPage(page(e(1, "fault", "fault", at = t0 + 4_000), e(2, "waiting", "waiting", at = t0 + 50_000)), all)
        assertEquals(listOf(2L), out.map { it.seq })
        assertEquals(2L, r.lastSeq)
        // Redémarrage avec un journal vide : rien à lire.
        assertNull(JournalAlerts().apply { onState(9, t0) }.onState(0, t0))
    }

    @Test fun categories() {
        assertEquals(AlertKind.WAITING, AlertKind.ofJournal("waiting"))
        assertEquals(AlertKind.END, AlertKind.ofJournal("step_end"))
        assertEquals(AlertKind.END, AlertKind.ofJournal("job_end"))
        assertEquals(AlertKind.PAUSE, AlertKind.ofJournal("pause"))
        assertEquals(AlertKind.PAUSE, AlertKind.ofJournal("fault"))
        assertNull(AlertKind.ofJournal(""))
        assertNull(AlertKind.ofJournal("other"))
        assertEquals(AlertKind.TEMP, AlertKind.ofJournal("temp_reached"))
        // Valeurs par défaut de la maquette : oui, oui, oui, non ; puis « Température atteinte » : oui (ajoutée en dernier).
        assertEquals(listOf(true, true, true, false, true), AlertKind.values().map { it.defaultOn })
    }

    @Test fun offlineOncePerOutageAfterADelay() {
        val w = OfflineWatch(30_000)
        assertFalse(w.update(0, unreachable = true, wasInProgress = true))
        assertFalse(w.update(29_000, unreachable = true, wasInProgress = true))
        assertTrue(w.update(30_000, unreachable = true, wasInProgress = true))
        assertFalse(w.update(60_000, unreachable = true, wasInProgress = true))
        assertFalse(w.update(61_000, unreachable = false, wasInProgress = true))
        // Nouvelle panne : de nouveau une alerte.
        assertFalse(w.update(70_000, unreachable = true, wasInProgress = true))
        assertTrue(w.update(100_000, unreachable = true, wasInProgress = true))
        // Robot au repos : pas d'alerte.
        val idle = OfflineWatch(30_000)
        idle.update(0, true, false)
        assertFalse(idle.update(60_000, true, false))
    }

    // Le service ne s'arrête plus quand le robot est au repos (suivi permanent) : KeepAlive a laissé la place à LockPolicy.
    @Test fun locksOnlyWhileCooking() {
        val p = LockPolicy(offlineHoldMs = 600_000)
        // Robot au repos : rien n'est tenu, le téléphone dort.
        assertFalse(p.hold(0, reachable = true, inProgress = false))
        assertTrue(p.hold(1_000, reachable = true, inProgress = true))
        assertTrue(p.hold(3_600_000, reachable = true, inProgress = true))
        // Robot disparu en pleine cuisson : encore 10 min, le temps de prévenir et de le retrouver.
        assertTrue(p.hold(3_900_000, reachable = false, inProgress = true))
        assertFalse(p.hold(4_200_000, reachable = false, inProgress = true))
        // Fin de cuisson : rendus aussitôt ; une panne ensuite ne les reprend pas.
        val q = LockPolicy(offlineHoldMs = 600_000)
        q.hold(0, true, true)
        assertFalse(q.hold(10_000, reachable = true, inProgress = false))
        assertFalse(q.hold(20_000, reachable = false, inProgress = false))
        // Démarré sur un robot injoignable, sans cuisson vue : rien.
        assertFalse(LockPolicy().hold(0, reachable = false, inProgress = true))
    }

    @Test fun staleLinesAfterAnOutageAreSeenButNotNotified() {
        val r = JournalAlerts(maxAgeMs = 120_000)
        r.onState(10, t0)
        // Téléphone revenu à la maison 3 h plus tard : le journal a bougé pendant l'absence.
        val later = t0 + 3 * 3_600_000L
        assertEquals(10L, r.onState(14, later))
        val dropped = ArrayList<String>()
        val out = r.onPage(page(
            e(11, "job_finished", "job_end", at = t0 + 600_000),
            e(12, "waiting", "waiting", at = later - 130_000),
            e(13, "paused", "pause", at = later - 90_000),
            e(14, "paused", "pause", self = true, at = later - 1_000),
        ), all, dropped)
        assertEquals(listOf(13L), out.map { it.seq })
        assertEquals(14L, r.lastSeq)
        assertEquals(3, dropped.size)
        assertTrue(dropped[0], dropped[0].contains("trop ancienne"))
        assertTrue(dropped[2], dropped[2].contains("action de ce téléphone"))
        // Rien n'est rattrapé ensuite.
        assertNull(r.onState(14, later + 1_000))
    }

    @Test fun switchedOffKindIsReportedForTheDiagnostic() {
        val r = JournalAlerts()
        r.onState(0, t0)
        r.onState(1, t0 + 1_000)
        val dropped = ArrayList<String>()
        assertTrue(r.onPage(page(e(1, "job_finished", "job_end")), setOf(AlertKind.WAITING), dropped).isEmpty())
        assertEquals(listOf("ligne 1 (job_finished) écartée : alerte désactivée"), dropped)
    }

    /** Notification du suivi : toutes les combinaisons (en cours, déjà montrée, premier plan dû). */
    @Test fun noticeStepAllCombinations() {
        val C = FollowNotice.COOKING
        val I = FollowNotice.IDLE
        for (owed in listOf(false, true)) {
            // Rien encore montré : toujours poser.
            assertEquals(NoticeStep(C, true), noticeStep(busy = true, shown = null, owed = owed))
            assertEquals(NoticeStep(I, true), noticeStep(busy = false, shown = null, owed = owed))
            // Passage de l'une à l'autre : poser.
            assertEquals(NoticeStep(C, true), noticeStep(busy = true, shown = I, owed = owed))
            assertEquals(NoticeStep(I, true), noticeStep(busy = false, shown = C, owed = owed))
        }
        // Même sorte : posée seulement si un premier plan est dû.
        assertEquals(NoticeStep(C, false), noticeStep(busy = true, shown = C, owed = false))
        assertEquals(NoticeStep(C, true), noticeStep(busy = true, shown = C, owed = true))
        assertEquals(NoticeStep(I, false), noticeStep(busy = false, shown = I, owed = false))
        assertEquals(NoticeStep(I, true), noticeStep(busy = false, shown = I, owed = true))
    }
}
