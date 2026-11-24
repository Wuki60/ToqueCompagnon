package app.toque.companion.ui

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.toque.companion.CompanionLanguages
import app.toque.companion.R
import app.toque.core.i18n.tr
import app.toque.core.look.ColourWheel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * La roue du téléphone (feuille de Robot › Apparence) : un glissé donne la couleur de la roue sous le
 * doigt, « Appliquer » la rend, « Annuler » ne rend rien ; les raccourcis mettent la poignée sur les dix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WheelTest {
    private lateinit var c: Context

    @Before fun setUp() {
        RuntimeEnvironment.setQualifiers("w360dp-h640dp-port-xhdpi")
        c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Companion)
        Ui.init(c)
        CompanionLanguages.install(c, "fr")
        Ui.setLook("")
        ColourWheelView.Ring.inline = true
    }

    @After fun tearDown() {
        Ui.setLook("")
    }

    private fun layout(v: View) {
        val dm = c.resources.displayMetrics
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, dm.widthPixels, dm.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun click(root: View, label: String) {
        fun find(v: View): View? {
            if (v is TextView && v.text.toString() == label) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var v: View = find(root)!!
        while (!v.isClickable) v = v.parent as View
        v.performClick()
    }

    /** Glisse d'un point (angle, intensité) à un autre ; renvoie la couleur attendue sous le doigt. */
    private fun drag(w: ColourWheelView, from: Pair<Double, Double>, to: Pair<Double, Double>): Int {
        val half = w.ring / 2.0
        fun at(p: Pair<Double, Double>): Pair<Float, Float> {
            val a = Math.toRadians(p.first)
            val r = ColourWheel.radiusOf(p.second) * half
            return (w.width / 2.0 + Math.cos(a) * r).toFloat() to (w.height / 2.0 + Math.sin(a) * r).toFloat()
        }
        val t0 = SystemClock.uptimeMillis()
        val (x0, y0) = at(from)
        val (x1, y1) = at(to)
        assertTrue(w.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x0, y0, 0)))
        for (i in 1..4) {
            val f = i / 4f
            w.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 16L * i, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 0))
        }
        w.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 100, MotionEvent.ACTION_UP, x1, y1, 0))
        val s = ColourWheel.spotAt(x1 - w.width / 2.0, y1 - w.height / 2.0, half)
        return ColourWheel.colour(s.hue, s.intensity)
    }

    @Test fun aDragGivesTheWheelColourAndApplyReturnsIt() {
        val picked = ArrayList<Int>()
        val sheet = WheelSheet(c) { picked += it }
        sheet.show(Ui.ACCENT)
        layout(sheet)
        val w = sheet.wheel
        // Sur un petit téléphone (360 dp), l'anneau prend la largeur de la carte, poignée de 44 dp autour.
        assertTrue("anneau de ${w.ring} px", w.ring >= Ui.dp(200) && w.ring <= Ui.dp(ColourWheelView.MAX_RING))
        assertTrue(ColourWheelView.Ring.ready(w.ring) != null)
        assertEquals(Ui.ACCENT, w.color)
        val want = drag(w, 60.0 to 0.4, 140.0 to 0.95)
        assertEquals(want, w.color)
        assertTrue(ColourWheel.accepts(want))
        assertTrue("rien n'est rendu avant « Appliquer »", picked.isEmpty())
        click(sheet, tr("Appliquer"))
        assertEquals(listOf(want), picked)
        assertFalse(sheet.showing)
    }

    @Test fun cancelReturnsNothing() {
        val picked = ArrayList<Int>()
        val sheet = WheelSheet(c) { picked += it }
        sheet.show(Ui.ACCENT)
        layout(sheet)
        drag(sheet.wheel, 250.0 to 1.0, 300.0 to 0.5)
        assertNotEquals(Ui.ACCENT, sheet.wheel.color)
        click(sheet, tr("Annuler"))
        assertTrue(picked.isEmpty())
        assertFalse(sheet.showing)
        // Un toucher sur le disque du milieu ne change rien.
        sheet.show(Ui.ACCENT)
        layout(sheet)
        val w = sheet.wheel
        val t = SystemClock.uptimeMillis()
        assertFalse(w.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w.width / 2f, w.height / 2f, 0)))
        assertEquals(Ui.ACCENT, w.color)
    }

    @Test fun theTenShortcutsPutTheHandleOnThem() {
        val picked = ArrayList<Int>()
        val sheet = WheelSheet(c) { picked += it }
        sheet.show(Ui.ACCENT)
        layout(sheet)
        fun described(v: View, d: String): View? {
            if (v.contentDescription == d) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) described(v.getChildAt(i), d)?.let { return it }
            return null
        }
        described(sheet, tr("Couleur %d", 6))!!.performClick()
        assertEquals(0xFF4FC3F7.toInt(), sheet.wheel.color)
        assertEquals(ColourWheel.place(0xFF4FC3F7.toInt()).hue, sheet.wheel.hue, 1e-9)
        click(sheet, tr("Appliquer"))
        assertEquals(listOf(0xFF4FC3F7.toInt()), picked)
    }
}
