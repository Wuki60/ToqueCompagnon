package app.toque.companion

import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.toque.companion.flows.DraftStore
import app.toque.companion.flows.EditFlow
import app.toque.companion.flows.OwnStepFlow
import app.toque.companion.flows.Scheduler
import app.toque.companion.link.MemoryLinkStore
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.Route
import app.toque.companion.ui.Samples
import app.toque.companion.ui.Screens
import app.toque.companion.ui.Shell
import app.toque.companion.ui.Ui
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * La flèche de retour et le retour du système sur les écrans que leur déroulement referme lui-même : l'Étape
 * de l'éditeur, l'éditeur, et « Mon réglage » d'une recette Mealie. L'écran demande au déroulement de se fermer, et le déroulement le dit à la
 * coquille. Avec la vraie coquille et le vrai déroulement, pas la navigation sans vues des autres essais : la
 * coquille demande d'abord à l'écran affiché s'il traite le retour, et ils ne doivent pas s'appeler sans fin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-port-xhdpi")
class BackArrowTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var shell: Shell
    private lateinit var screens: Screens
    private lateinit var link: RobotLink
    private lateinit var editor: EditFlow
    private lateinit var own: OwnStepFlow

    @Before fun setUp() {
        val c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Companion)
        Ui.init(c)
        CompanionLanguages.install(c, "fr")
        val main = Handler(Looper.getMainLooper())
        val schedule: Scheduler = { ms, r -> main.postDelayed(r, ms); { main.removeCallbacks(r) } }
        // Téléphone pas appairé : rien ne part sur le réseau, l'éditeur s'ouvre quand même.
        link = RobotLink(MemoryLinkStore(), post = { main.post(it) }, ownIpv4 = { null }, candidates = { emptyList() })
        shell = Shell(c)
        editor = EditFlow(link, shell, { screens.editeur.render(it) }, { screens.etape.render(it) }, DraftStore(tmp.newFolder("brouillons")), schedule)
        own = OwnStepFlow(link, shell, { screens.reglage.render(it) })
        screens = Screens(c, object : LiveActions {}, shell, edit = editor, ownStep = own)
        shell.screens = screens
        Samples.renderAll(screens)
        shell.refresh()
    }

    @After fun tearDown() {
        editor.close()
        own.close()
        link.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    /** La flèche en haut à gauche de l'écran affiché. */
    private fun backArrow(root: View, description: String) {
        assertTrue(all(root).first { it.contentDescription?.toString() == description && it.hasOnClickListeners() }.performClick())
        idle()
    }

    /** Le bouton qui porte [text], visible à l'écran. */
    private fun click(root: View, text: String) {
        var v: View = all(root).filterIsInstance<TextView>().first { it.text.toString() == text && visible(it) }
        while (!v.isClickable) v = v.parent as View
        assertTrue(v.performClick())
        idle()
    }

    private fun shows(root: View, text: String) = all(root).filterIsInstance<TextView>().any { it.text.toString() == text && visible(it) }

    /** [v] et tout ce qui le contient sont affichés (la coquille de l'essai n'est dans aucune fenêtre : pas d'isShown). */
    private fun visible(v: View): Boolean {
        var x: View? = v
        while (x != null) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return true
    }

    private fun newRecipe() {
        editor.startNew()
        idle()
        assertEquals(Route.EDITEUR, shell.current)
    }

    private fun newStep() {
        editor.addStep()
        idle()
        assertEquals(Route.ETAPE, shell.current)
    }

    /** « Mon réglage » d'une étape, ouvert depuis la fiche d'une recette Mealie (le robot ne répond pas : peu importe ici). */
    private fun openMyStepSetting() {
        shell.open(Route.FICHE)
        own.open("mealie-5e6f7a8b", 1, "empreinte", "Tarte d'essai")
        idle()
        assertEquals(Route.REGLAGE, shell.current)
    }

    @Test fun theBackArrowOfMyStepSettingReturnsToTheRecipe() {
        openMyStepSetting()
        backArrow(screens.reglage.view, "Retour à la recette")
        assertEquals(Route.FICHE, shell.current)
    }

    @Test fun theSystemBackOnMyStepSettingReturnsToTheRecipe() {
        openMyStepSetting()
        assertTrue(shell.back())
        idle()
        assertEquals(Route.FICHE, shell.current)
    }

    @Test fun theBackArrowOfAStepReturnsToTheEditor() {
        newRecipe()
        newStep()
        editor.setStepText("Verser le lait.")
        backArrow(screens.etape.view, "Retour à la recette")
        assertEquals(Route.EDITEUR, shell.current)
        assertEquals(listOf("Verser le lait."), editor.state.steps.map { it.text })
    }

    @Test fun theSystemBackOnAStepReturnsToTheEditor() {
        newRecipe()
        newStep()
        assertTrue(shell.back())
        idle()
        assertEquals(Route.EDITEUR, shell.current)
        // L'étape ajoutée et laissée vide est partie avec le retour.
        assertTrue(editor.state.steps.isEmpty())
    }

    @Test fun aRemovedStepReturnsToTheEditor() {
        newRecipe()
        newStep()
        editor.setStepText("Verser le lait.")
        backArrow(screens.etape.view, "Retour à la recette")
        // Rouverte, l'étape n'est plus nouvelle : elle se supprime par son bouton.
        editor.openStep(0)
        idle()
        assertEquals(Route.ETAPE, shell.current)
        click(screens.etape.view, "Supprimer l'étape")
        assertTrue(shows(screens.etape.view, "Supprimer l'étape ?"))
        click(screens.etape.view, "Supprimer")
        assertEquals(Route.EDITEUR, shell.current)
        assertTrue(editor.state.steps.isEmpty())
    }

    @Test fun anUntouchedEditorIsLeftAtOnce() {
        newRecipe()
        backArrow(screens.editeur.view, "Retour")
        assertEquals(Route.RECETTES, shell.current)

        newRecipe()
        assertTrue(shell.back())
        idle()
        assertEquals(Route.RECETTES, shell.current)
    }

    /** Une recette commencée : le retour demande d'abord ; « Rester » garde l'éditeur, « Quitter » le ferme. */
    @Test fun aStartedRecipeAsksBeforeLeaving() {
        newRecipe()
        editor.setTitle("Soupe d'essai")
        idle()
        assertTrue(shell.back())
        idle()
        assertEquals(Route.EDITEUR, shell.current)
        assertTrue(shows(screens.editeur.view, "Quitter sans enregistrer ?"))
        click(screens.editeur.view, "Rester")
        assertEquals(Route.EDITEUR, shell.current)

        backArrow(screens.editeur.view, "Retour")
        assertTrue(shows(screens.editeur.view, "Quitter sans enregistrer ?"))
        click(screens.editeur.view, "Quitter")
        assertEquals(Route.RECETTES, shell.current)
    }
}
