package app.toque.companion.ui

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import app.toque.companion.CompanionLanguages
import app.toque.companion.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * La couleur du robot sur les écrans, sans robot : [Ui.setLook] et ses replis, aucun orange laissé sur
 * aucun écran avec une autre couleur, et [Shell.restyle] qui refait les écrans sur place sans perdre
 * l'éditeur, une feuille ouverte ou le défilement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookTest {
    private lateinit var c: Context

    @Before fun setUp() {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-port-xhdpi")
        c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Companion)
        Ui.init(c)
        CompanionLanguages.install(c, "fr")
        Ui.setLook("")
    }

    @After fun tearDown() {
        Ui.setLook("")
    }

    private fun phone(): Pair<Shell, Screens> {
        val shell = Shell(c)
        val screens = Screens(c, object : LiveActions {}, shell)
        shell.screens = screens
        Samples.renderAll(screens)
        shell.refresh()
        return shell to screens
    }

    private fun layout(v: View) {
        val dm = c.resources.displayMetrics
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun show(shell: Shell, route: Route) {
        when {
            route == Route.APPAIRAGE -> shell.showPairing(true)
            route.tab == Tab.LIVE && route != Route.ETAPES -> { shell.showPairing(false); shell.select(Tab.LIVE); shell.showLive(route) }
            else -> { shell.showPairing(false); shell.open(route) }
        }
        shell.refresh()
    }

    private fun <T : View> find(root: View, type: Class<T>, cond: (T) -> Boolean = { true }): T? {
        if (type.isInstance(root) && cond(type.cast(root)!!)) return type.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), type, cond)?.let { return it }
        return null
    }

    private fun click(root: View, label: String) {
        var v: View = find(root, TextView::class.java) { it.text.toString() == label }!!
        while (!v.isClickable) v = v.parent as View
        v.performClick()
    }

    @Test fun setLookTakesTheRobotColourAndFallsBackToOrange() {
        assertEquals(Accent.DEFAULT, Ui.ACCENT)
        // L'orange d'origine : exactement les couleurs d'avant.
        assertEquals(0x33FF8A3D, Ui.ACCENT_DIM)
        assertEquals(0xFF78492E.toInt(), Ui.ACCENT_DONE)
        assertEquals(0xFF603D2B.toInt(), Ui.ACCENT_SOFT)
        assertEquals(0xFFA15E37.toInt(), Ui.ACCENT_LINE)
        assertEquals(0xFF1A0E05.toInt(), Ui.ON_ACCENT)

        val seq = Ui.lookSeq
        assertTrue(Ui.setLook("#4fc3f7", "light"))
        assertEquals(0xFF4FC3F7.toInt(), Ui.ACCENT)
        assertEquals(0x334FC3F7, Ui.ACCENT_DIM)
        assertEquals(0xFF09171D.toInt(), Ui.ON_ACCENT)
        assertEquals("#4FC3F7", Ui.accentHex)
        // Thème clair : les fonds du robot, l'accent garde sa couleur en fond, l'encre est plus foncée.
        assertTrue(Ui.light)
        assertEquals("light", Ui.theme)
        assertEquals(0xFFF3F5F8.toInt(), Ui.BG)
        assertEquals(0xFF1A2332.toInt(), Ui.TEXT)
        assertTrue(Accent.luminance(Ui.ACCENT_INK) < Accent.luminance(Ui.ACCENT))
        assertEquals(seq + 1, Ui.lookSeq)
        // La même couleur une seconde fois : rien ne change.
        assertFalse(Ui.setLook("#4FC3F7", "light"))
        assertEquals(seq + 1, Ui.lookSeq)

        // Couleur trop sombre : éclaircie ; inconnue ou illisible : l'orange.
        // Retour au sombre : exactement les couleurs d'avant.
        assertTrue(Ui.setLook("#4FC3F7", "dark"))
        assertEquals(0xFF0A1322.toInt(), Ui.BG)
        assertEquals(0xFF4FC3F7.toInt(), Ui.ACCENT_INK)
        assertTrue(Ui.setLook("#1A237E"))
        assertEquals(0xFF8D91BF.toInt(), Ui.ACCENT)
        assertEquals("#1A237E", Ui.accentHex)
        for (bad in listOf("", "bleu", "#12345", "4FC3F7")) {
            Ui.setLook("#F2639A")
            Ui.setLook(bad)
            assertEquals(bad, Accent.DEFAULT, Ui.ACCENT)
            assertEquals(0xFFA15E37.toInt(), Ui.ACCENT_LINE)
            assertEquals("", Ui.accentHex)
        }
    }

    /**
     * Avec une autre couleur que l'orange, aucun écran ne garde une couleur de l'orange ; sur un thème,
     * aucun ne garde une couleur de l'autre (photo et caméra à part).
     */
    @Test fun noOrangeLeftOnAnyScreen() {
        for (theme in listOf("dark", "light")) for (accent in listOf("#4FC3F7", "#E8D44D", "#F2639A")) {
            Ui.setLook(accent, theme)
            val (shell, screens) = phone()
            val problems = ArrayList<String>()
            val other = Looks.themeColours(if (theme == "light") Palette.DARK else Palette.LIGHT) - Looks.themeColours(Palette.of(theme))
            for (route in Route.values()) {
                show(shell, route)
                layout(shell)
                Looks.find(shell, Looks.ORANGE).forEach { problems += "$theme $accent $route : $it" }
                Looks.find(shell, other).forEach { problems += "$theme $accent $route (autre thème) : $it" }
            }
            // Pastilles : la pastille d'un programme en cours et celle du robot sont pleines de l'accent.
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
            assertTrue(Looks.find(screens.robot.view, setOf(Ui.ACCENT)).any { it.contains("fond") })
            assertTrue(Looks.find(screens.robot.view, setOf(Ui.ON_ACCENT)).any { it.contains("icône") })
            Ui.setLook("")
        }
    }

    @Test fun restyleRebuildsTheShownScreenInPlace() {
        val (shell, screens) = phone()
        show(shell, Route.ROBOT)
        layout(shell)
        val old = screens.robot
        val scroll = find(old.view, ScrollView::class.java)!!
        scroll.scrollTo(0, Ui.dp(120))
        val y = scroll.scrollY
        assertTrue(y > 0)
        assertTrue(Looks.find(shell, Looks.ORANGE).isNotEmpty())

        assertTrue(Ui.setLook("#8BD85F"))
        shell.restyle()
        val fresh = screens.robot
        assertNotSame(old, fresh)
        assertEquals(old.state, fresh.state)
        assertSame(shell, fresh.view.parent?.parent)
        assertTrue(old.view.parent == null)
        layout(shell)
        assertEquals(y, find(fresh.view, ScrollView::class.java)!!.scrollY)
        assertEquals(emptyList<String>(), Looks.find(shell, Looks.ORANGE))
        // La barre d'onglets aussi : l'onglet Robot prend la couleur.
        assertTrue(Looks.find(shell, setOf(0xFF8BD85F.toInt())).any { it.contains("« Robot »") })
        assertFalse(screens.hasStale)
    }

    @Test fun theEditorKeepsWhatIsBeingTypedUntilItCloses() {
        val (shell, screens) = phone()
        shell.select(Tab.RECIPES)
        show(shell, Route.FICHE)
        show(shell, Route.EDITEUR)
        layout(shell)
        val editor = screens.editeur
        val title = find(editor.view, EditText::class.java) { it.contentDescription == "Titre" }!!
        title.setText("Soupe pas encore enregistrée")
        title.setSelection(5)
        val fiche = screens.fiche

        assertTrue(Ui.setLook("#F2639A"))
        shell.restyle()
        // L'éditeur ouvert reste tel quel : même écran, même texte, même curseur.
        assertSame(editor, screens.editeur)
        assertSame(title, find(screens.editeur.view, EditText::class.java) { it.contentDescription == "Titre" })
        assertEquals("Soupe pas encore enregistrée", title.text.toString())
        assertEquals(5, title.selectionStart)
        assertTrue(screens.isStale(Route.EDITEUR))
        // La fiche, sous l'éditeur, est refaite tout de suite ; la barre d'onglets aussi.
        assertNotSame(fiche, screens.fiche)
        assertEquals(fiche.state, screens.fiche.state)
        // Une seconde plus tard, toujours pas : l'éditeur est encore ouvert.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertSame(editor, screens.editeur)

        // Fermé (retour au catalogue) : refait avec la nouvelle couleur.
        shell.select(Tab.RECIPES)
        assertEquals(Route.RECETTES, shell.current)
        assertNotSame(editor, screens.editeur)
        assertFalse(screens.hasStale)
        layout(shell)
        assertEquals(emptyList<String>(), Looks.find(shell, Looks.ORANGE))
    }

    /** Thème changé depuis l'onglet Robot : l'éditeur, caché dans l'onglet Recettes, est refait et garde son texte. */
    @Test fun aHiddenEditorIsRebuiltWithItsText() {
        val (shell, screens) = phone()
        shell.select(Tab.RECIPES)
        show(shell, Route.EDITEUR)
        layout(shell)
        val editor = screens.editeur
        find(editor.view, EditText::class.java) { it.contentDescription == "Titre" }!!.setText("Soupe pas encore enregistrée")
        shell.select(Tab.ROBOT)
        assertTrue(Ui.setLook("", "light"))
        shell.restyle()
        assertNotSame(editor, screens.editeur)
        assertFalse(screens.hasStale)
        shell.select(Tab.RECIPES)
        assertEquals(Route.EDITEUR, shell.current)
        layout(shell)
        val title = find(screens.editeur.view, EditText::class.java) { it.contentDescription == "Titre" }!!
        assertEquals("Soupe pas encore enregistrée", title.text.toString())
        assertEquals(emptyList<String>(), Looks.find(shell, Looks.themeColours(Palette.DARK) - Looks.themeColours(Palette.LIGHT)))
    }

    @Test fun anOpenSheetWaitsUntilItIsClosed() {
        val (shell, screens) = phone()
        show(shell, Route.ROBOT)
        layout(shell)
        click(shell, "Oublier")
        val old = screens.robot
        assertTrue(find(old.view, ConfirmSheet::class.java)!!.showing)

        assertTrue(Ui.setLook("#B48CFF"))
        shell.restyle()
        assertSame(old, screens.robot)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertSame(old, screens.robot)
        // Feuille fermée par le retour : refait à la seconde suivante, feuille fermée.
        assertTrue(shell.back())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        assertNotSame(old, screens.robot)
        assertFalse(find(screens.robot.view, ConfirmSheet::class.java)!!.showing)
        layout(shell)
        assertEquals(emptyList<String>(), Looks.find(shell, Looks.ORANGE))
    }

    @Test fun theLiveScreenIsRebuiltWithItsRunningJobAndTabs() {
        val (shell, screens) = phone()
        show(shell, Route.MAIN)
        show(shell, Route.ETAPES)
        layout(shell)
        click(shell, "Ingrédients")
        val main = screens.main
        val etapes = screens.etapes

        assertTrue(Ui.setLook("#3DDCB0"))
        shell.restyle()
        assertNotSame(main, screens.main)
        assertNotSame(etapes, screens.etapes)
        assertEquals(main.state, screens.main.state)
        assertEquals(etapes.state, screens.etapes.state)
        layout(shell)
        // Toujours l'onglet Ingrédients, la cuisson en cours affichée.
        val ingredients = find(shell, TextView::class.java) { it.text.toString() == "Ingrédients" }!!
        assertTrue(ingredients.isSelected)
        assertEquals(emptyList<String>(), Looks.find(shell, Looks.ORANGE))
        assertEquals(Route.ETAPES, shell.current)
    }
}
