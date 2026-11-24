package app.toque.companion.flows

import app.toque.companion.flows.PhotoQueue.Fetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La file des téléchargements : priorités, annulation, limite, un seul téléchargement par photo, échecs. */
class PhotoQueueTest {
    private class Started(val id: String, val done: (Fetch) -> Unit)
    private class Timer(val ms: Long, val r: Runnable, var cancelled: Boolean = false)

    private val started = ArrayList<Started>()
    private val timers = ArrayList<Timer>()
    private val schedule: Scheduler = { ms, r -> val t = Timer(ms, r); timers += t; { t.cancelled = true } }
    private fun queue(max: Int = 2) = PhotoQueue(max, schedule) { id, done -> started += Started(id, done) }
    private fun ids() = started.map { it.id }
    /** Termine le dernier téléchargement lancé de [id]. */
    private fun finish(id: String, f: Fetch = Fetch.STORED) = started.last { it.id == id }.done(f)
    /** Le temps passe : les nouveaux essais prévus partent. */
    private fun elapse() {
        val due = timers.toList()
        timers.clear()
        for (t in due) if (!t.cancelled) t.r.run()
    }
    /** Trois essais ratés de suite (le premier et deux nouveaux essais). */
    private fun failThrice(id: String) {
        finish(id, Fetch.FAILED)
        elapse()
        finish(id, Fetch.FAILED)
        elapse()
        finish(id, Fetch.FAILED)
    }

    @Test fun theNewestVisibleRequestComesFirst() {
        val q = queue()
        for (id in listOf("a", "b", "c", "d", "e")) q.want(id) {}
        assertEquals(listOf("a", "b"), ids())
        finish("a")
        assertEquals(listOf("a", "b", "e"), ids())
        finish("b")
        finish("e")
        assertEquals(listOf("a", "b", "e", "d", "c"), ids())
    }

    @Test fun neverMoreThanTwoAtATime() {
        val q = queue()
        q.prefetch((1..10).map { "p$it" })
        for (i in 1..10) q.want("u$i") {}
        assertEquals(2, started.size)
        assertEquals(2, q.running)
        finish(started[0].id)
        assertEquals(3, started.size)
        assertEquals(2, q.running)
    }

    @Test fun prefetchWaitsForTheUrgentOnes() {
        val q = queue(max = 1)
        q.want("u1") {}
        q.prefetch(listOf("p1", "p2"))
        q.want("u2") {}
        finish("u1")
        assertEquals(listOf("u1", "u2"), ids())
        finish("u2")
        assertEquals(listOf("u1", "u2", "p1"), ids())
        // Une photo préchargée qu'une carte demande devient urgente.
        q.want("p2") {}
        q.prefetch(listOf("p3"))
        finish("p1")
        assertEquals(listOf("u1", "u2", "p1", "p2"), ids())
    }

    @Test fun aPageOfFortyEightNeverStarvesTheCardsOnScreen() {
        // Page de 48 (CatalogFlow.PAGE_SIZE) préchargée : deux téléchargements partent tout de suite.
        val q = queue()
        q.prefetch((1..CatalogFlow.PAGE_SIZE).map { "p$it" })
        assertEquals(listOf("p1", "p2"), ids())
        // Six cartes à l'écran demandent leur photo : elles attendent seulement les deux en cours,
        // puis passent toutes avant le reste de la page.
        for (i in 1..6) q.want("c$i") {}
        finish("p1")
        finish("p2")
        for (i in 6 downTo 3) finish("c$i")
        assertEquals(listOf("p1", "p2", "c6", "c5", "c4", "c3", "c2", "c1"), ids())
        finish("c2")
        finish("c1")
        assertEquals(listOf("p3", "p4"), ids().drop(8))
    }

    @Test fun aCancelledRequestIsDemotedNotLost() {
        val q = queue(max = 1)
        q.want("a") {}
        q.want("b") {}
        var cCalled = false
        val c = q.want("c") { cCalled = true }
        q.prefetch(listOf("p"))
        c.cancel()
        finish("a")
        // « c » abandonnée : après « b » (urgente) mais avant le préchargement.
        assertEquals(listOf("a", "b"), ids())
        finish("b")
        assertEquals(listOf("a", "b", "c"), ids())
        finish("c")
        assertEquals(false, cCalled)
        assertEquals(listOf("a", "b", "c", "p"), ids())
    }

    @Test fun inTheBackgroundOnlyUrgentRequestsRun() {
        val q = queue(max = 1)
        q.active = false
        q.prefetch(listOf("p1", "p2"))
        val t = q.want("u") {}
        q.want("v") {}.cancel()
        assertEquals(listOf("u"), ids())
        t.cancel()
        finish("u")
        // Plus rien d'urgent, appli quittée : le fond attend.
        assertEquals(listOf("u"), ids())
        q.active = true
        assertEquals(listOf("u", "v"), ids())
        finish("v")
        assertEquals(listOf("u", "v", "p1"), ids())
    }

    @Test fun severalAskersShareOneDownload() {
        val q = queue()
        val got = ArrayList<Fetch>()
        repeat(3) { q.want("a") { got += it } }
        assertEquals(listOf("a"), ids())
        q.want("a") { got += it }.cancel()
        finish("a")
        assertEquals(listOf(Fetch.STORED, Fetch.STORED, Fetch.STORED), got)
        assertEquals(1, started.size)
    }

    @Test fun aNetworkFailureIsRetriedA404IsRemembered() {
        val q = queue()
        val got = ArrayList<Fetch>()
        q.want("a") { got += it }
        failThrice("a")
        assertEquals(listOf(Fetch.FAILED), got)
        started.clear()
        q.want("a") { got += it }
        assertEquals(listOf("a"), ids())
        finish("a")
        assertEquals(listOf(Fetch.FAILED, Fetch.STORED), got)

        q.want("b") { got += it }
        finish("b", Fetch.MISSING)
        assertTrue(q.isMissing("b"))
        q.want("b") { got += it }
        q.prefetch(listOf("b"))
        q.refresh("b")
        assertEquals(listOf(Fetch.FAILED, Fetch.STORED, Fetch.MISSING, Fetch.MISSING), got)
        assertEquals(listOf("a", "b"), ids())
    }

    @Test fun aFailureIsTriedTwiceMoreWithoutHoldingASlot() {
        val q = queue(max = 1)
        val got = ArrayList<Fetch>()
        q.want("a") { got += it }
        q.want("b") {}
        finish("a", Fetch.FAILED)
        // « a » attend 1,5 s son nouvel essai ; pendant ce temps « b » prend la place.
        assertEquals(listOf(1_500L), timers.map { it.ms })
        assertEquals(listOf("a", "b"), ids())
        assertTrue(got.isEmpty())
        elapse()
        assertEquals(listOf("a", "b"), ids())
        finish("b")
        assertEquals(listOf("a", "b", "a"), ids())
        finish("a", Fetch.FAILED)
        elapse()
        assertEquals(listOf("a", "b", "a", "a"), ids())
        // Réussi au troisième essai.
        finish("a")
        assertEquals(listOf(Fetch.STORED), got)

        // Trois échecs : FAILED, puis la prochaine demande recommence avec trois essais.
        q.want("c") { got += it }
        failThrice("c")
        assertEquals(Fetch.FAILED, got.last())
        assertEquals(3, ids().count { it == "c" })
        assertTrue(timers.isEmpty())
        q.want("c") { got += it }
        assertEquals(4, ids().count { it == "c" })
    }

    @Test fun nothingStartsWhileTheLinkIsDown() {
        val q = queue()
        q.online = false
        val got = ArrayList<Fetch>()
        q.want("a") { got += it }
        q.prefetch(listOf("p"))
        assertTrue(started.isEmpty())
        q.online = true
        assertEquals(listOf("a", "p"), ids())
        // Échec pendant une coupure : pas un essai, la photo attend la reconnexion.
        q.online = false
        finish("a", Fetch.FAILED)
        assertTrue(timers.isEmpty())
        assertTrue(got.isEmpty())
        q.online = true
        assertEquals(listOf("a", "p", "a"), ids())
        finish("a", Fetch.UNSTORED)
        assertEquals(listOf(Fetch.UNSTORED), got)
        // Gardée nulle part : pas retenue, redemandée.
        assertFalse(q.isMissing("a"))
        q.want("a") {}
        assertEquals(listOf("a", "p", "a", "a"), ids())
    }

    @Test fun aNewListDropsThePreviousPrefetch() {
        val q = queue(max = 1)
        q.want("u") {}
        q.prefetch(listOf("p1", "p2"))
        q.refresh("old")
        q.newList()
        q.prefetch(listOf("n1"))
        finish("u")
        finish("old")
        assertEquals(listOf("u", "old", "n1"), ids())
        assertTrue(q.waiting().isEmpty())
    }

    @Test fun afterAResetOldDownloadsStillHoldTheirSlotButTellNobody() {
        val q = queue()
        var told = false
        q.want("a") { told = true }
        q.want("b") {}
        q.reset()
        q.want("c") {}
        assertEquals(listOf("a", "b"), ids())
        finish("a", Fetch.MISSING)
        assertEquals(false, told)
        assertEquals(false, q.isMissing("a"))
        assertEquals(listOf("a", "b", "c"), ids())
    }
}
