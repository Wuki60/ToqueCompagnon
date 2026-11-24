package app.toque.companion

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.TextView
import app.toque.companion.ui.JournalActions
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.RecipeActions
import app.toque.companion.ui.Route
import app.toque.companion.ui.Samples
import app.toque.companion.ui.Screens
import app.toque.companion.ui.Shell
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.screens.JournalDay
import app.toque.companion.ui.screens.JournalEntry
import app.toque.companion.ui.screens.JournalKind
import app.toque.companion.ui.screens.JournalOutcome
import app.toque.companion.ui.screens.JournalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Les boutons de ce lot sur les écrans : « Arrêter la recette » et sa confirmation (le Stop rouge
 * reste en un appui), l'étoile des favoris (fiche, cartes), « Ajouter à la liste », « Partager les ingrédients », les
 * cartes du journal qui ouvrent leur recette.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-port-xhdpi")
class RecipeButtonsTest {
    private lateinit var shell: Shell
    private lateinit var screens: Screens
    private val calls = ArrayList<String>()

    @Before fun setUp() {
        val c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Companion)
        Ui.init(c)
        CompanionLanguages.install(c, "fr")
        shell = Shell(c)
        screens = Screens(c, object : LiveActions {
            override fun stop() { calls += "stop" }
            override fun stopRecipe() { calls += "stopRecipe" }
        }, shell, recipes = object : RecipeActions {
            override fun openRecipe(id: String) { calls += "open $id" }
            override fun setFavorite(id: String, favorite: Boolean) { calls += "favorite $id $favorite" }
            override fun shareIngredients(id: String) { calls += "share $id" }
            override fun addToList(id: String) { calls += "list $id" }
        }, journalActions = object : JournalActions {
            override fun openRecipe(recipeId: String) { calls += "journal $recipeId" }
        })
        shell.screens = screens
        Samples.renderAll(screens)
        shell.refresh()
    }

    @Test fun stopTheRecipeAsksFirstAndContinueSendsNothing() {
        for ((route, view, render) in listOf(
            Triple(Route.ETAPES, { screens.etapes.view }) { on: Boolean ->
                screens.etapes.render(Samples.etapes().copy(stopRecipeOffered = on, canStopRecipe = on)) },
            Triple(Route.MAIN, { screens.main.view }) { on: Boolean ->
                screens.main.render(Samples.main().copy(stopRecipeOffered = on, canStopRecipe = on)) },
        )) {
            calls.clear()
            if (route == Route.ETAPES) shell.open(route)
            else {
                while (shell.current == Route.ETAPES) shell.back()
                shell.showLive(route)
            }
            // Robot plus ancien (pas de can.stopRecipe) : pas de bouton.
            render(false)
            assertFalse(route.name, shown(view(), "Arrêter la recette"))
            render(true)
            assertTrue(route.name, shown(view(), "Arrêter la recette"))
            click(view(), "Arrêter la recette")
            assertTrue(shown(view(), "Arrêter la recette ?"))
            assertTrue(shown(view(), "La cuisson en cours s'arrête, et la recette ne sera plus proposée sur l'accueil du robot."))
            // « Continuer » : rien n'est envoyé ; le retour ferme aussi la confirmation sans rien envoyer.
            click(view(), "Continuer")
            assertFalse(shown(view(), "Arrêter la recette ?"))
            click(view(), "Arrêter la recette")
            assertTrue(shell.back())
            assertEquals(route, shell.current)
            assertFalse(shown(view(), "Arrêter la recette ?"))
            assertEquals(emptyList<String>(), calls)
            // Confirmé : une demande.
            click(view(), "Arrêter la recette")
            clickLast(view(), "Arrêter la recette")
            assertEquals(listOf("stopRecipe"), calls)
            // Le Stop rouge de la barre reste en un seul appui, sans confirmation.
            assertTrue(all(view()).first { it.contentDescription == "Arrêter" && it.visibleUp() }.performClick())
            assertEquals(listOf("stopRecipe", "stop"), calls)
        }
        // Rien en cours dans la recette : la confirmation ne parle pas d'une cuisson.
        screens.etapes.render(Samples.etapes().copy(now = null, stopRecipeOffered = true, canStopRecipe = true))
        shell.open(Route.ETAPES)
        click(screens.etapes.view, "Arrêter la recette")
        assertTrue(shown(screens.etapes.view, "La recette ne sera plus proposée sur l'accueil du robot."))
    }

    @Test fun theStarIsAButtonOnlyWhenTheRobotRecordsIt() {
        shell.open(Route.FICHE)
        val v = screens.fiche.view
        // Robot plus ancien : l'étiquette d'avant, pas de bouton.
        assertFalse(all(v).any { it.visibleUp() && it.contentDescription in setOf("Retirer des favoris", "Ajouter aux favoris") })
        screens.fiche.render(Samples.fiche().copy(canFavorite = true))
        assertTrue(all(v).first { it.visibleUp() && it.contentDescription == "Retirer des favoris" }.performClick())
        screens.fiche.render(Samples.fiche().copy(canFavorite = true, favorite = false))
        assertTrue(all(v).first { it.visibleUp() && it.contentDescription == "Ajouter aux favoris" }.performClick())
        assertFalse(all(v).any { it.visibleUp() && it.contentDescription == "Favori sur le robot" })
        click(v, "Partager les ingrédients")
        click(v, "Ajouter à la liste")
        assertEquals(listOf("favorite perso-1 false", "favorite perso-1 true", "share perso-1", "list perso-1"), calls)
        // Onglet Étapes : ni partage ni liste.
        click(v, "Étapes")
        assertFalse(shown(v, "Partager les ingrédients"))
        assertFalse(shown(v, "Ajouter à la liste"))

        // Cartes du catalogue.
        calls.clear()
        shell.select(Tab.RECIPES)
        screens.recettes.render(Samples.recettes().copy(canFavorite = true))
        layout(shell)
        val grid = all(screens.recettes.view).filterIsInstance<GridView>().first()
        assertTrue(all(grid.getChildAt(0)).first { it.contentDescription == "Retirer des favoris" }.performClick())
        assertTrue(all(grid.getChildAt(1)).first { it.contentDescription == "Ajouter aux favoris" }.performClick())
        assertTrue(grid.getChildAt(1).performClick())
        assertEquals(listOf("favorite perso-1 false", "favorite lidl-101 true", "open lidl-101"), calls)
        // Robot plus ancien : l'étoile n'est qu'un repère, toucher la carte l'ouvre.
        calls.clear()
        screens.recettes.render(Samples.recettes())
        layout(shell)
        val star = all(grid.getChildAt(0)).first { it.contentDescription == "Favori" }
        assertFalse(star.isClickable)
        assertFalse(all(grid.getChildAt(1)).any { it.visibleUp() && it.contentDescription != null && it.contentDescription.contains("favori") })
    }

    @Test fun onlyRecipesStillInTheCatalogueOpenFromTheJournal() {
        shell.select(Tab.LOG)
        screens.journal.render(JournalState(days = listOf(JournalDay("Aujourd'hui", listOf(
            JournalEntry("a1", JournalKind.RECIPE, "Velouté de potimarron", "12:05", JournalOutcome.FINISHED, recipeId = "perso-1"),
            JournalEntry("a2", JournalKind.RECIPE, "Recette supprimée", "11:05", JournalOutcome.STOPPED),
            JournalEntry("a3", JournalKind.MANUAL, "Cuisson manuelle", "10:05", JournalOutcome.FINISHED),
        )))))
        val v = screens.journal.view
        // La carte : le premier parent du titre qui a un fond (la surface arrondie).
        fun card(title: String): View {
            var x: View = all(v).filterIsInstance<TextView>().first { it.text.toString() == title }
            while (x.background == null) x = x.parent as View
            return x
        }
        assertTrue(card("Velouté de potimarron").performClick())
        assertFalse(card("Recette supprimée").isClickable)
        assertFalse(card("Cuisson manuelle").isClickable)
        assertEquals(listOf("journal perso-1"), calls)
    }

    private fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 780, 1688)
    }

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    /** Visible : la vue et tous ses parents dans l'écran. */
    private fun View.visibleUp(): Boolean {
        var x: View? = this
        while (x != null) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }

    private fun shown(root: View, text: String) = all(root).any { it is TextView && it.text.toString() == text && it.visibleUp() }

    private fun clickable(t: View): View {
        var v: View = t
        while (!v.isClickable) v = v.parent as View
        return v
    }

    private fun click(root: View, text: String) {
        val t = all(root).first { it is TextView && it.text.toString() == text && it.visibleUp() }
        assertTrue(clickable(t).performClick())
    }

    /** Le dernier (la feuille de confirmation passe par-dessus l'écran). */
    private fun clickLast(root: View, text: String) {
        val t = all(root).last { it is TextView && it.text.toString() == text && it.visibleUp() }
        assertTrue(clickable(t).performClick())
    }
}
