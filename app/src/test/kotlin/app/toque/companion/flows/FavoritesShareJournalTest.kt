package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.PairResult
import app.toque.companion.link.RobotLink
import app.toque.companion.link.SimRobot
import app.toque.companion.ui.Tab
import app.toque.companion.ui.screens.FicheState
import app.toque.companion.ui.screens.JournalKind
import app.toque.companion.ui.screens.JournalState
import app.toque.companion.ui.screens.RecettesState
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CookSession
import app.toque.core.companion.WaitingFor
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeChip
import app.toque.core.recipes.Step
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Favoris depuis le téléphone, liste de courses et recettes du journal, de bout en bout sur la JVM :
 * vrai hub sur base simulée, vraie liaison, flux appelés comme les écrans les appellent.
 */
class FavoritesShareJournalTest {
    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val robots = ArrayList<SimRobot>()
    private val links = ArrayList<RobotLink>()
    private val schedule: Scheduler = { ms, r -> val f = main.schedule(r, ms, TimeUnit.MILLISECONDS); { f.cancel(false) } }

    @After fun tearDown() {
        links.forEach { it.close() }
        robots.forEach { it.shutdown() }
        main.shutdownNow()
    }

    private fun <T> onMain(block: () -> T): T = main.submit(Callable { block() }).get(10, TimeUnit.SECONDS)

    private fun eventually(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(40)
        }
        throw AssertionError("délai dépassé : $what")
    }

    private fun paired(robot: SimRobot): RobotLink {
        val l = RobotLink(MemoryLinkStore(), post = { main.execute(it) }, ownIpv4 = { "127.0.0.1" }, candidates = { listOf("127.0.0.1") },
            ports = listOf(robot.port), waitSeconds = 3, followerSleep = { Thread.sleep(it / 20) }, locateTimeoutMs = 500).also { links += it }
        val done = CountDownLatch(1)
        var r: PairResult? = null
        l.pairWithCode(robot.hub.pairing.open().code) { r = it; done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue("$r", r is PairResult.Paired)
        onMain { l.follow("test") }
        eventually("connecté") { l.status.phase == LinkPhase.CONNECTED && l.latest != null }
        return l
    }

    private class Catalog(link: RobotLink, schedule: Scheduler) {
        val nav = FakeNav()
        var list = RecettesState()
        var fiche: FicheState? = null
        val notices = ArrayList<String>()
        val shared = ArrayList<Pair<String, String>>()
        val flow = CatalogFlow(link, nav, { list = it }, { fiche = it }, {}, schedule, notice = { notices += it }, pageSize = 20,
            share = { subject, text -> shared += subject to text })
    }

    @Test fun favoritesChosenOnThePhoneAreRecordedByTheRobot() {
        val robot = SimRobot().start().also { robots += it }
        val link = paired(robot)
        val c = onMain { Catalog(link, schedule) }
        onMain { c.nav.select(Tab.RECIPES); c.flow.ensureLoaded() }
        eventually("catalogue") { c.list.items.size == 20 && !c.list.loading && c.list.canFavorite }

        // Carte : l'étoile change aussitôt, le robot l'enregistre.
        val a = onMain { c.list.items.first { !it.favorite }.id }
        onMain { c.flow.setFavorite(a, true) }
        assertTrue(onMain { c.list.items.first { it.id == a }.favorite })
        eventually("enregistré") { robot.favorites[a] == true }
        // Fiche : le robot la donne en favori ; la retirer depuis la fiche, la carte suit.
        onMain { c.flow.openRecipe(a) }
        eventually("fiche") { c.fiche?.loading == false }
        assertTrue(onMain { c.fiche!!.favorite && c.fiche!!.canFavorite })
        onMain { c.flow.setFavorite(a, false) }
        assertFalse(onMain { c.fiche!!.favorite })
        assertFalse(onMain { c.list.items.first { it.id == a }.favorite })
        eventually("retiré") { robot.favorites[a] == false }
        // La fiche reste juste quand elle est redessinée (nombre de personnes, état…).
        onMain { c.flow.setServings(a, c.fiche!!.servings!! + 1) }
        assertFalse(onMain { c.fiche!!.favorite })

        // Pastille « Favoris » : la liste suit les choix du téléphone.
        onMain { c.nav.back(); c.flow.filter(RecipeChip.FAVORITES) }
        eventually("favoris") { c.list.filter == RecipeChip.FAVORITES && !c.list.loading && c.list.items.isNotEmpty() }
        assertFalse(onMain { c.list.items.any { it.id == a } })
        onMain { c.flow.setFavorite(a, true) }
        eventually("ajouté à la liste des favoris") { !c.list.loading && c.list.items.any { it.id == a } }
        onMain { c.flow.setFavorite("essai-7", false) }
        eventually("retiré de la liste des favoris") { !c.list.loading && c.list.items.none { it.id == "essai-7" } }

        // Refus du robot : l'étoile revient, avec un message bref.
        robot.favoriteRefusal = CommandResult.refuse("busy", "Refusé pour l'essai")
        onMain { c.flow.filter(RecipeChip.ALL) }
        eventually("toutes") { c.list.filter == RecipeChip.ALL && !c.list.loading && c.list.items.isNotEmpty() }
        val b = onMain { c.list.items.first { !it.favorite }.id }
        // L'étoile change tout de suite (vu dans le même passage du fil principal : le refus peut revenir très vite).
        assertTrue(onMain { c.flow.setFavorite(b, true); c.list.items.first { it.id == b }.favorite })
        eventually("remise") { !c.list.items.first { it.id == b }.favorite && c.notices.isNotEmpty() }
        assertTrue(onMain { c.notices.last() }, onMain { c.notices.last() }.contains("Refusé pour l'essai"))
        assertEquals(null, robot.favorites[b])
        onMain { c.flow.close() }
    }

    @Test fun anOlderRobotKeepsTheLabel() {
        val robot = SimRobot().also { it.older = true }.start().also { robots += it }
        val link = paired(robot)
        val c = onMain { Catalog(link, schedule) }
        onMain { c.nav.select(Tab.RECIPES); c.flow.ensureLoaded() }
        eventually("catalogue") { c.list.items.size == 20 && !c.list.loading }
        assertFalse(onMain { c.list.canFavorite })
        onMain { c.flow.openRecipe("essai-7") }
        eventually("fiche") { c.fiche?.loading == false }
        assertTrue(onMain { c.fiche!!.favorite && !c.fiche!!.canFavorite })
        onMain { c.flow.setFavorite("essai-7", false) }
        assertTrue(onMain { c.fiche!!.favorite })
        Thread.sleep(200)
        assertTrue(robot.favoriteRequests.isEmpty())
        onMain { c.flow.close() }
    }

    @Test fun theShoppingListIsTheSheetAsShown() {
        val robot = SimRobot().start().also { robots += it }
        val link = paired(robot)
        val c = onMain { Catalog(link, schedule) }
        val id = robot.recipe.id
        onMain { c.flow.openRecipe(id) }
        eventually("fiche") { c.fiche?.loading == false }
        onMain { c.flow.shareIngredients(id) }
        assertEquals("Velouté de potimarron" to """
            Velouté de potimarron (4 personnes)
            - 1 oignon
            - 20 g de beurre
            - 600 g de potimarron
            - 0,5 l de lait
        """.trimIndent(), onMain { c.shared.single() })
        // Six personnes, pas encore confirmé par le robot : les quantités affichées, calculées comme lui.
        onMain { c.flow.setServings(id, 6) }
        onMain { c.flow.shareIngredients(id) }
        val six = onMain { c.shared.last().second }
        assertEquals("Velouté de potimarron (6 personnes)", six.lines().first())
        assertTrue(six, six.contains("- 30 g de beurre"))
        assertTrue(six, six.contains("- 900 g de potimarron"))
        eventually("confirmé") { robot.servings[id] == 6 }
        onMain { c.flow.shareIngredients(id) }
        assertEquals(six, onMain { c.shared.last().second })
        onMain { c.flow.close() }
    }

    @Test fun journalRecipesStillInTheCatalogueOpenTheirSheet() {
        val robot = SimRobot().start().also { robots += it }
        val link = paired(robot)
        // Une recette menée au bout, une recette qui n'existe plus au catalogue.
        robot.openRecipe()
        robot.hub.endSession(true)
        val gone = Recipe(id = "supprimee", title = "Recette supprimée", steps = listOf(Step("Mélanger.")))
        robot.hub.updateSession(CookSession.of(gone, 0, waitingFor = WaitingFor.HAND))
        robot.hub.endSession(false)
        var state = JournalState()
        val opened = ArrayList<String>()
        val flow = onMain { JournalFlow(link, { state = it }, open = { opened += it }) }
        onMain { flow.setVisible(true) }
        eventually("recette vérifiée") { state.days.flatMap { it.entries }.any { it.recipeId != null } }
        val entries = onMain { state.days.flatMap { it.entries } }
        assertEquals(listOf(JournalKind.RECIPE, JournalKind.RECIPE), entries.map { it.kind })
        assertEquals(robot.recipe.id, entries.first { it.title == "Velouté de potimarron" }.recipeId)
        // Supprimée : vérifiée (404), elle ne se touche pas.
        Thread.sleep(300)
        assertNull(onMain { state.days.flatMap { it.entries }.first { it.title == "Recette supprimée" }.recipeId })
        onMain { flow.openRecipe("supprimee"); flow.openRecipe(robot.recipe.id) }
        assertEquals(listOf(robot.recipe.id), onMain { opened.toList() })
        onMain { flow.close() }
    }
}
