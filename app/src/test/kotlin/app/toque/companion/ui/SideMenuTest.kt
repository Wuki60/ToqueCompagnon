package app.toque.companion.ui

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.toque.companion.CompanionLanguages
import app.toque.companion.R
import app.toque.core.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Menu des onglets sur le côté (grand écran). Un téléphone à l'horizontale est « grand » par sa largeur,
 * mais il lui reste peu de hauteur sous la barre d'état et celle des gestes : tous les onglets doivent y
 * tenir en entier, le dernier (Robot) compris.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SideMenuTest {
    private class Tab(val root: ViewGroup) {
        val icon: View = root.getChildAt(0)
        val label: TextView = root.getChildAt(1) as TextView
        val labelled: Boolean get() = label.visibility == View.VISIBLE
    }

    /** Le menu tel que la coquille le pose : hauteur imposée ([heightDp]), largeur libre. */
    private fun menu(heightDp: Int, fontScale: Float = 1f, language: String = "fr"): Pair<BottomBar, List<Tab>> {
        RuntimeEnvironment.setQualifiers("w844dp-h390dp-land-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        val c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Companion)
        Ui.init(c)
        CompanionLanguages.install(c, language)
        val bar = BottomBar(c, vertical = true) {}
        val h = Ui.dp(heightDp)
        bar.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(844), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        bar.layout(0, 0, bar.measuredWidth, h)
        return bar to (0 until bar.childCount).map { Tab(bar.getChildAt(it) as ViewGroup) }
    }

    @After fun reset() {
        RuntimeEnvironment.setFontScale(1f)
        I18n.install(emptyList())
        I18n.select("fr")
    }

    /**
     * 800 : tablette. 390 : tout l'écran d'un téléphone à l'horizontale. 360 à 300 : ce qu'il en reste sous
     * les barres du système (un Galaxy S23 Ultra fait 384 dp de haut). En dessous : fenêtre réduite.
     */
    private val heights = listOf(800, 402, 390, 360, 336, 300, 270, 250, 220, 180, 140)

    @Test fun everyTabIsWholeOnScreenWhateverTheHeight() {
        val problems = ArrayList<String>()
        for (language in listOf("fr", "de", "uk")) for (font in listOf(1f, 1.3f, 2f)) for (heightDp in heights) {
            val (bar, tabs) = menu(heightDp, font, language)
            val where = "$language, police ×$font, $heightDp dp"
            assertEquals(where, 5, tabs.size)
            var floor = 0
            for ((i, tab) in tabs.withIndex()) {
                val root = tab.root
                if (root.top < floor) problems += "$where : l'onglet $i chevauche le précédent (${root.top} < $floor)"
                if (root.bottom > bar.height) problems += "$where : l'onglet $i dépasse du menu de ${root.bottom - bar.height} px"
                if (root.right > bar.width) problems += "$where : l'onglet $i dépasse en largeur"
                if (root.height < Ui.dp(24)) problems += "$where : l'onglet $i ne fait plus que ${root.height} px de haut"
                if (tab.icon.top < 0 || tab.icon.bottom > root.height) problems += "$where : l'icône de l'onglet $i sort de l'onglet"
                if (tab.labelled) {
                    val l = tab.label.layout
                    if (tab.label.bottom > root.height) problems += "$where : le libellé de l'onglet $i sort de l'onglet"
                    if (l == null || l.height > tab.label.height) problems += "$where : le libellé de l'onglet $i est coupé"
                }
                floor = root.bottom
            }
        }
        assertTrue("Menu sur le côté :\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** Sur une tablette, rien ne change : onglets de 68 dp, le premier à 20 dp du haut, tous avec leur libellé. */
    @Test fun aTabletKeepsTheRoomyMenu() {
        val (_, tabs) = menu(800)
        assertEquals(Ui.dp(20), tabs.first().root.top)
        for (tab in tabs) {
            assertEquals(Ui.dp(68), tab.root.height)
            assertTrue(tab.labelled)
        }
        assertEquals(Ui.dp(6), tabs[1].root.top - tabs[0].root.bottom)
    }

    /** Téléphone à l'horizontale, barres du système déduites : onglets serrés, mais libellés gardés et cibles d'au moins 48 dp. */
    @Test fun aPhoneInLandscapeKeepsLabelsAndTouchTargets() {
        for (heightDp in listOf(360, 336, 300)) {
            val (_, tabs) = menu(heightDp)
            for (tab in tabs) {
                assertTrue("$heightDp dp : libellé caché", tab.labelled)
                assertTrue("$heightDp dp : onglet de ${tab.root.height} px", tab.root.height >= Ui.dp(Ui.TOUCH))
            }
        }
    }

    /** Fenêtre très basse : plutôt que de couper le dernier onglet, il ne reste que les icônes ; TalkBack lit toujours le nom. */
    @Test fun aVeryLowWindowKeepsTheIconsOnly() {
        val (_, tabs) = menu(220)
        for (tab in tabs) {
            assertTrue("libellé resté affiché", !tab.labelled)
            assertEquals(tab.label.text.toString(), tab.root.contentDescription?.toString())
        }
        val (_, roomy) = menu(360)
        for (tab in roomy) assertEquals(null, tab.root.contentDescription)
    }
}
