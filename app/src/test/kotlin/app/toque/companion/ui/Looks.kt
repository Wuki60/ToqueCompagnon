package app.toque.companion.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView

/** Outils des tests de la couleur du robot : retrouver dans un écran les couleurs d'une autre couleur d'accent. */
object Looks {
    /** Les couleurs que l'orange d'origine donne (accent, teintes, texte posé dessus), sans leur opacité sauf ACCENT_DIM. */
    val ORANGE: Set<Int> = setOf(0xFFFF8A3D.toInt(), 0x33FF8A3D, 0xFF78492E.toInt(), 0xFF603D2B.toInt(), 0xFFA15E37.toInt(), 0xFF1A0E05.toInt())

    /**
     * Les couleurs de [colours] trouvées dans [root] (texte, icône, fond plein ou contour lisible,
     * anneau, barre, roue), avec la vue qui les porte. Seules les vues visibles comptent.
     */
    fun find(root: View, colours: Set<Int>): List<String> {
        val out = ArrayList<String>()
        fun name(v: View): String {
            val t = (v as? TextView)?.text?.toString()?.take(30)
            return v.javaClass.simpleName.ifEmpty { v.javaClass.superclass.simpleName } + (if (t != null) " « $t »" else "") +
                (v.contentDescription?.let { " [$it]" } ?: "")
        }
        fun drawable(d: Drawable?, v: View, what: String) {
            when (d) {
                is GradientDrawable -> d.color?.defaultColor?.let { if (it in colours) out += "${name(v)} : $what ${hex(it)}" }
                is InsetDrawable -> drawable(d.drawable, v, what)
                // Le masque d'une onde (blanc) ne se voit pas.
                is LayerDrawable -> for (i in 0 until d.numberOfLayers) if (d.getId(i) != android.R.id.mask) drawable(d.getDrawable(i), v, what)
                else -> {}
            }
        }
        fun visit(v: View) {
            if (v.visibility != View.VISIBLE || v.tag == Ui.FIXED) return
            if (v is TextView && v.currentTextColor in colours) out += "${name(v)} : texte ${hex(v.currentTextColor)}"
            if (v is Icon && v.color in colours) out += "${name(v)} : icône ${hex(v.color)}"
            if (v is Ring && v.color in colours) out += "${name(v)} : anneau ${hex(v.color)}"
            if (v is ProgressLine && v.color in colours) out += "${name(v)} : barre ${hex(v.color)}"
            if (v is Dot && v.color in colours) out += "${name(v)} : point ${hex(v.color)}"
            if (v is ProgressBar) v.indeterminateTintList?.defaultColor?.let { if (it in colours) out += "${name(v)} : roue ${hex(it)}" }
            drawable(v.background, v, "fond")
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        return out
    }

    /** Couleurs propres à un thème (fonds, textes, traits) : retrouvées sur l'autre, elles y sont de trop. */
    fun themeColours(p: Palette): Set<Int> = setOf(p.bg, p.surface, p.surface2, p.surface3, p.stroke, p.text, p.text2, p.text3, p.textMuted,
        p.warnSoft, p.warnLine) - 0xFFFFFFFF.toInt()

    fun hex(c: Int) = if (c ushr 24 == 0xFF) String.format("#%06X", c and 0xFFFFFF) else String.format("#%08X", c)
}
