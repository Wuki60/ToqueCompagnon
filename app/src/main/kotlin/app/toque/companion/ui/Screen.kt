package app.toque.companion.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * Un écran du compagnon : rendu pur d'un état de vue immuable [S].
 *
 * - [build] construit les vues une seule fois ;
 * - [render] reçoit chaque nouvel état (jusqu'à plusieurs fois par seconde) et appelle [bind],
 *   qui met les vues à jour sur place, sans reconstruire l'arbre ;
 * - les actions passent par les rappels donnés au constructeur ([LiveActions], [Navigator]) :
 *   pas de réseau ni d'état global dans un écran.
 */
abstract class Screen<S : Any>(val c: Context) {
    /** Onglet allumé dans la barre du bas quand l'écran est affiché. */
    abstract val tab: Tab

    private val lazyView = lazy { build() }
    val view: View by lazyView

    /** Les vues sont construites. */
    val isBuilt: Boolean get() = lazyView.isInitialized()

    /** Dernier état reçu (null avant le premier [render]). */
    var state: S? = null
        private set

    fun render(s: S) {
        view
        if (s == state) return
        val old = state
        state = s
        bind(s, old)
    }

    protected abstract fun build(): View

    /** Met les vues à jour pour [s] ; [old] est l'état précédent (null la première fois). */
    protected abstract fun bind(s: S, old: S?)

    open fun onShow() {}
    open fun onHide() {}

    /**
     * Un travail en cours que refaire l'écran perdrait (curseur, clavier, saisie) : tant qu'il est affiché,
     * il garde ses couleurs d'avant ; caché, il est refait (il reprend son état et le texte de ses champs).
     */
    open val keepsWork: Boolean get() = false

    /**
     * Ce nouvel écran remplace [old], le même construit avec les couleurs d'avant : il en reprend le
     * dernier état, le défilement et ce que [carry] reprend en plus (onglet choisi…).
     */
    fun takeOver(old: Screen<*>) {
        // Jamais construit : jamais rendu non plus, rien à reprendre.
        if (!old.isBuilt) return
        view
        carry(old)
        @Suppress("UNCHECKED_CAST")
        val s = old.state as S?
        takingOver = true
        try {
            if (s != null) render(s)
        } finally {
            takingOver = false
        }
        Scrolls.copy(old.view, view)
        Fields.copy(old.view, view)
    }

    /** Rendu de [takeOver] : l'écran reprend un état déjà vu, rien n'est à mettre sous le doigt (focus, clavier). */
    protected var takingOver = false
        private set

    /** Reprend de [old] (même classe) ce que l'état de vue ne dit pas ; appelé avant le premier rendu. */
    protected open fun carry(old: Screen<*>) {}

    /** Retour du système : true si l'écran l'a traité lui-même (fermer une feuille, un mode). */
    open fun onBack(): Boolean = false
}

/**
 * Défilement d'un écran refait : les zones qui défilent du nouveau prennent, dans l'ordre, la position
 * de celles de l'ancien, dès leur première mise en page.
 */
internal object Scrolls {
    fun copy(from: View, to: View) {
        val a = collect(from, ArrayList())
        val b = collect(to, ArrayList())
        for (i in 0 until minOf(a.size, b.size)) {
            val src = a[i]
            val dst = b[i]
            if (src.javaClass != dst.javaClass) continue
            when (src) {
                is AbsListView -> {
                    val first = src.firstVisiblePosition
                    val top = src.getChildAt(0)?.top ?: 0
                    if (first > 0 || top != 0) afterLayout(dst) { (it as AbsListView).setSelectionFromTop(first, top) }
                }
                else -> {
                    val x = src.scrollX
                    val y = src.scrollY
                    if (x != 0 || y != 0) afterLayout(dst) { it.scrollTo(x, y) }
                }
            }
        }
    }

    private fun collect(v: View, out: MutableList<View>): MutableList<View> {
        if (v is ScrollView || v is HorizontalScrollView || v is AbsListView) out += v
        if (v is ViewGroup && v !is AbsListView) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
        return out
    }

    private fun afterLayout(v: View, f: (View) -> Unit) {
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                view.removeOnLayoutChangeListener(this)
                f(view)
            }
        })
    }
}

/**
 * Champs d'un écran refait : chacun reprend, dans l'ordre, le texte de celui de l'ancien quand il
 * diffère (texte tapé que l'état de vue n'aurait pas encore), avec la position du curseur.
 */
internal object Fields {
    fun copy(from: View, to: View) {
        val a = collect(from, ArrayList())
        val b = collect(to, ArrayList())
        if (a.size != b.size) return
        for (i in a.indices) {
            val t = a[i].text?.toString() ?: continue
            if (b[i].text?.toString() == t) continue
            b[i].setText(t)
            val sel = a[i].selectionStart
            if (sel in 0..t.length) b[i].setSelection(sel)
        }
    }

    private fun collect(v: View, out: MutableList<android.widget.EditText>): MutableList<android.widget.EditText> {
        if (v is android.widget.EditText) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
        return out
    }
}

/** Les cinq onglets de la barre du bas (ou du menu sur le côté, sur un grand écran). */
enum class Tab { LIVE, RECIPES, SHOP, LOG, ROBOT }
