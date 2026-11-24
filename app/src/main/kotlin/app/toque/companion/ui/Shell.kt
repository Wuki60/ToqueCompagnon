package app.toque.companion.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import java.util.EnumMap

/** Navigation entre écrans, donnée aux écrans (ils n'en connaissent pas d'autre). */
interface Navigator {
    /** Écran affiché. */
    val current: Route

    /** Affiche l'onglet [tab] (le haut de sa pile) ; sur l'onglet déjà affiché, revient à sa racine. */
    fun select(tab: Tab)

    /** Ouvre [route] par-dessus l'écran courant de son onglet (retour possible). */
    fun open(route: Route)

    /**
     * Remplace l'écran du haut de la pile de l'onglet de [route] par [route] (Fiche → Envoyée :
     * le retour revient alors au catalogue, pas à la fiche).
     */
    fun replace(route: Route)

    /**
     * Choisit l'écran racine de l'onglet « En direct » d'après l'état du robot (repos, recette,
     * pesée, pause…). Les écrans ouverts par-dessus (Étapes) restent ouverts.
     */
    fun showLive(route: Route)

    /** Retour : ferme l'écran du dessus, ou revient à « En direct ». false s'il n'y a plus rien à fermer. */
    fun back(): Boolean

    /**
     * Appairage : tant que [visible], l'écran Appairage occupe tout l'écran, sans la barre
     * d'onglets (téléphone pas encore appairé, ou oublié par le robot). false revient aux onglets.
     */
    fun showPairing(visible: Boolean)
}

/**
 * Coquille de l'appli : écran courant au-dessus, barre d'onglets en bas, une pile d'écrans par
 * onglet. Les écrans viennent du registre [Screens].
 */
class Shell(c: Context) : LinearLayout(c), Navigator {
    lateinit var screens: Screens
    /** Écran courant ; sur un grand écran, sa largeur est limitée et centrée (voir [CappedFrame]). */
    private val content = CappedFrame(c, CONTENT_MAX_DP)
    /** Grand écran, onglet Recettes : le catalogue reste à gauche de la fiche ouverte. */
    private val side = CappedFrame(c, SIDE_MAX_DP)
    private var sideLine = vline(c)
    private val body = LinearLayout(c)
    private var sideShown: Screen<*>? = null
    /** Grand écran (tablette, téléphone à l'horizontale) : menu des onglets sur le côté, deux colonnes pour les recettes. */
    var wide = false
        private set
    private var bar = BottomBar(c) { select(it) }
    private var barLine = Ui.divider(c, Ui.SURFACE2)
    private var pairing = false
    private val stacks = EnumMap<Tab, ArrayList<Route>>(Tab::class.java)
    private var tab = Tab.LIVE
    private var shown: Screen<*>? = null
    /** Message bref au-dessus des onglets (refus du robot…), voir [notice]. */
    private val noticeView = Ui.text(c, "", 14f, Ui.TEXT, lineHeight = 19f).apply {
        background = Ui.round(Ui.SURFACE3, 18f, Ui.STROKE, 1f)
        setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12))
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val hideNotice = Runnable { noticeView.visibility = View.GONE }

    init {
        setBackgroundColor(Ui.BG)
        body.orientation = HORIZONTAL
        body.addView(side, LayoutParams(0, Ui.MATCH, 0.42f))
        body.addView(sideLine, LayoutParams(maxOf(1, Ui.dp(1)), Ui.MATCH))
        body.addView(content, LayoutParams(0, Ui.MATCH, 0.58f))
        side.visibility = View.GONE
        sideLine.visibility = View.GONE
        content.addView(noticeView, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply {
            leftMargin = Ui.dp(16); rightMargin = Ui.dp(16); bottomMargin = Ui.dp(84)
        })
        for (route in Route.values()) if (route.root) stacks.getOrPut(route.tab) { arrayListOf(route) }
        arrange()
    }

    /**
     * Téléphone en hauteur : l'écran, puis la barre d'onglets en bas. Grand écran : le menu des onglets sur
     * le côté gauche, puis l'écran (et, pour les recettes, le catalogue à côté de la fiche).
     */
    private fun arrange() {
        removeAllViews()
        bar = BottomBar(context, vertical = wide) { select(it) }
        barLine = if (wide) vline(context) else Ui.divider(context, Ui.SURFACE2)
        content.capped = wide
        side.capped = wide
        (noticeView.layoutParams as FrameLayout.LayoutParams).bottomMargin = Ui.dp(if (wide) 16 else 84)
        if (wide) {
            orientation = HORIZONTAL
            addView(bar, LayoutParams(Ui.WRAP, Ui.MATCH))
            addView(barLine, LayoutParams(maxOf(1, Ui.dp(1)), Ui.MATCH))
            addView(body, LayoutParams(0, Ui.MATCH, 1f))
        } else {
            orientation = VERTICAL
            addView(body, LayoutParams(Ui.MATCH, 0, 1f))
            addView(barLine)
            addView(bar, Ui.lp())
        }
        bar.select(tab)
        Ui.show(bar, !current.fullScreen)
        Ui.show(barLine, !current.fullScreen)
    }

    /** Taille de l'écran changée (rotation, fenêtre, tablette) : [w] = au moins 600 dp de large. */
    fun setWide(w: Boolean) {
        if (w == wide) return
        wide = w
        arrange()
        refresh()
    }
    override val current: Route get() = if (pairing) Route.APPAIRAGE else stacks.getValue(tab).last()

    override fun showPairing(visible: Boolean) {
        if (visible == pairing) return
        pairing = visible
        refresh()
    }

    override fun select(tab: Tab) {
        val stack = stacks.getValue(tab)
        if (tab == this.tab) while (stack.size > 1) stack.removeAt(stack.size - 1)
        this.tab = tab
        refresh()
    }

    override fun open(route: Route) {
        val stack = stacks.getValue(route.tab)
        if (stack.last() != route) stack += route
        tab = route.tab
        refresh()
    }

    override fun replace(route: Route) {
        val stack = stacks.getValue(route.tab)
        stack.removeAll { it == route }
        if (stack.size > 1) stack[stack.size - 1] = route else stack += route
        tab = route.tab
        refresh()
    }

    /** Appelé à chaque changement de l'écran affiché (journal à recharger quand il paraît…). */
    var onShown: ((Route) -> Unit)? = null

    override fun showLive(route: Route) {
        require(route.tab == Tab.LIVE) { "$route n'est pas un écran « En direct »" }
        val stack = stacks.getValue(Tab.LIVE)
        if (stack[0] == route) return
        stack.removeAll { it == route }
        stack[0] = route
        if (tab == Tab.LIVE) refresh()
    }

    override fun back(): Boolean {
        (sheet as? ChoiceSheet)?.let {
            it.answer(false)
            return true
        }
        (sheet as? ConfirmSheet)?.let {
            it.cancel()
            return true
        }
        if (shown?.onBack() == true) return true
        if (pairing) return false
        val stack = stacks.getValue(tab)
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            refresh()
            return true
        }
        if (tab != Tab.LIVE) {
            select(Tab.LIVE)
            return true
        }
        return false
    }

    /** Affiche l'écran du haut de la pile de l'onglet courant. */
    fun refresh() {
        if (screens.hasStale) rebuildStale(swap = false)
        val next = screens[current]
        bar.select(tab)
        Ui.show(bar, !current.fullScreen)
        Ui.show(barLine, !current.fullScreen)
        updateSide()
        if (next === shown) return
        shown?.let {
            it.onHide()
            content.removeView(it.view)
        }
        shown = next
        (next.view.parent as? FrameLayout)?.removeView(next.view)
        content.addView(next.view, 0, Ui.flp())
        next.onShow()
        onShown?.invoke(current)
    }

    /**
     * Grand écran, onglet Recettes, fiche (ou recette envoyée) ouverte : le catalogue reste affiché à gauche,
     * comme sur une tablette ; toucher une autre carte change la fiche à droite. Sinon, une seule colonne.
     */
    private fun updateSide() {
        val want = if (wide && !pairing && tab == Tab.RECIPES && current in SPLIT) screens[Route.RECETTES] else null
        if (want === sideShown) return
        sideShown?.let {
            it.onHide()
            side.removeView(it.view)
        }
        sideShown = want
        if (want != null) {
            (want.view.parent as? ViewGroup)?.removeView(want.view)
            side.addView(want.view, Ui.flp())
            want.onShow()
        }
        Ui.show(side, want != null)
        Ui.show(sideLine, want != null)
    }

    /**
     * Les couleurs de [Ui] ont changé (thème ou couleur du téléphone) : la coquille et sa barre d'onglets
     * les prennent tout de suite, les écrans sont refaits avec elles ([Screens.rebuildStale]). Seul l'écran
     * affiché, s'il tient un travail en cours (éditeur, feuille de confirmation, champ en saisie), le garde :
     * il est refait dès qu'il ne le tient plus (contrôle chaque seconde) ou qu'il est quitté.
     */
    fun restyle() {
        // La coquille elle-même : fond, barre d'onglets refaite, message.
        setBackgroundColor(Ui.BG)
        arrange()
        val sl = vline(context)
        body.removeView(sideLine)
        body.addView(sl, 1, LayoutParams(maxOf(1, Ui.dp(1)), Ui.MATCH))
        sl.visibility = sideLine.visibility
        sideLine = sl
        noticeView.background = Ui.round(Ui.SURFACE3, 18f, Ui.STROKE, 1f)
        noticeView.setTextColor(Ui.TEXT)
        bar.select(tab)
        screens.markStale()
        rebuildStale(swap = true)
    }

    /** Question posée par-dessus l'écran (apparence), ou null. */
    private var sheet: View? = null

    /** Pose [v] (une [ChoiceSheet]) par-dessus l'écran affiché ; le retour y répond « non ». */
    fun showSheet(v: View) {
        hideSheet()
        sheet = v
        content.addView(v, Ui.flp())
    }

    fun hideSheet() {
        sheet?.let { content.removeView(it) }
        sheet = null
    }

    val sheetShown: Boolean get() = sheet != null

    /**
     * Rien ne serait dérangé par une question : un écran calme ([QUIET] : repos, catalogue, fiche,
     * journal, onglet Robot, diagnostic), sans feuille ouverte ni champ en saisie, pas d'appairage.
     * Jamais sur l'éditeur, un écran de cuisson, de pesée, de pause, ni la recette qui attend.
     */
    fun quiet(): Boolean {
        if (pairing || sheet != null || current !in QUIET) return false
        val s = shown ?: return false
        return s === screens[current] && !holdsWork(s)
    }

    /** Écran qui tient un travail en cours : voir [Screen.keepsWork], plus une feuille ouverte ou un champ en saisie. */
    private fun holdsWork(screen: Screen<*>): Boolean {
        if (screen.keepsWork) return true
        if (!screen.isBuilt) return false
        fun busy(v: View): Boolean {
            if (v.visibility != View.VISIBLE) return false
            if (v is ConfirmSheet && v.showing) return true
            if (v is WheelSheet && v.showing) return true
            if (v is android.widget.EditText && v.hasFocus()) return true
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (busy(v.getChildAt(i))) return true
            return false
        }
        return busy(screen.view)
    }

    private val retryStale = Runnable { rebuildStale(swap = true) }
    /** Fil principal, même quand la coquille n'est pas (encore) dans une fenêtre. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Refait les écrans périmés : tout de suite ceux qui ne sont pas ouverts, et ceux qui le sont sans
     * travail en cours. [swap] : l'écran affiché, refait, prend la place de l'ancien sans passer par
     * [onShown] (rien n'est relu). Ce qui reste est réessayé une seconde plus tard.
     */
    private fun rebuildStale(swap: Boolean) {
        mainHandler.removeCallbacks(retryStale)
        // Un écran caché est refait tout de suite (il reprend son état et le texte de ses champs) ;
        // l'écran affiché attend s'il tient un travail en cours.
        val done = screens.rebuildStale { route, screen -> route != current || !holdsWork(screen) }
        // Le catalogue affiché à gauche, refait, prend la place de l'ancien.
        if (sideShown != null && sideShown !== screens[Route.RECETTES]) {
            sideShown?.let { it.onHide(); side.removeView(it.view) }
            sideShown = null
            updateSide()
        }
        if (swap && current in done) {
            val next = screens[current]
            shown?.let {
                it.onHide()
                content.removeView(it.view)
            }
            shown = next
            (next.view.parent as? FrameLayout)?.removeView(next.view)
            content.addView(next.view, 0, Ui.flp())
            next.onShow()
        }
        if (screens.hasStale && !closed) mainHandler.postDelayed(retryStale, 1_000)
    }

    private var closed = false

    /** Activité détruite : plus de nouvel essai pour les écrans à refaire. */
    fun close() {
        closed = true
        mainHandler.removeCallbacks(retryStale)
    }

    /** Montre [text] quelques secondes au-dessus de la barre d'actions (refus du robot, erreur réseau). */
    fun notice(text: String, durationMs: Long = 5_000) {
        removeCallbacks(hideNotice)
        noticeView.text = text
        noticeView.visibility = View.VISIBLE
        postDelayed(hideNotice, durationMs)
    }

    companion object {
        /** Largeur maximale de l'écran et du catalogue de gauche sur un grand écran (dp). */
        const val CONTENT_MAX_DP = 760
        const val SIDE_MAX_DP = 560
        /** Écrans de l'onglet Recettes affichés à droite du catalogue sur un grand écran. */
        val SPLIT: Set<Route> = java.util.EnumSet.of(Route.FICHE, Route.ENVOYEE)

        private fun vline(c: Context) = View(c).apply { setBackgroundColor(Ui.SURFACE2) }

        /** Écrans où une question peut se poser (voir [quiet]). */
        val QUIET: Set<Route> = java.util.EnumSet.of(Route.REPOS, Route.RECETTES, Route.FICHE, Route.COURSES, Route.JOURNAL, Route.ROBOT, Route.DIAGNOSTIC)
    }

    /** Texte du message affiché, null s'il est caché (tests). */
    val noticeText: String? get() = if (noticeView.visibility == View.VISIBLE) noticeView.text.toString() else null
}

/**
 * Barre d'onglets : icône 24 dp et libellé 12 sp, de la couleur d'accent pour l'onglet affiché. [vertical] :
 * menu sur le côté (grand écran), les onglets les uns sous les autres.
 */
class BottomBar(c: Context, vertical: Boolean = false, onSelect: (Tab) -> Unit) : LinearLayout(c) {
    private class Item(val root: LinearLayout, val icon: Icon, val label: TextView)
    private val items = EnumMap<Tab, Item>(Tab::class.java)

    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        if (vertical) {
            Ui.pad(this, 8, 20, 8, 12)
            minimumWidth = Ui.dp(88)
        } else Ui.pad(this, 12, 8, 12, 14)
        for (t in Tab.values()) {
            val (kind, label) = when (t) {
                Tab.LIVE -> Icon.Kind.POT to tr("En direct")
                Tab.RECIPES -> Icon.Kind.BOOK to tr("Recettes")
                Tab.SHOP -> Icon.Kind.BASKET to tr("Courses")
                Tab.LOG -> Icon.Kind.LIST to tr("Journal")
                Tab.ROBOT -> Icon.Kind.SLIDERS to tr("Robot")
            }
            val root = Ui.vertical(c, Gravity.CENTER)
            root.minimumHeight = Ui.dp(Ui.TOUCH)
            val icon = Icon(c, kind, Ui.TEXT2)
            icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.addView(icon, LayoutParams(Ui.dp(24), Ui.dp(24)))
            val text = Ui.ellipsize(Ui.text(c, label, 12f, Ui.TEXT2, Ui.MEDIUM))
            root.addView(text, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
            root.isClickable = true
            root.isFocusable = true
            root.background = Ui.pressable(Ui.round(0, 24f), 24f)
            root.setOnClickListener { onSelect(t) }
            if (vertical) addView(root, LayoutParams(Ui.dp(76), Ui.dp(68)).apply { bottomMargin = Ui.dp(6) })
            else addView(root, LayoutParams(0, Ui.WRAP, 1f))
            items[t] = Item(root, icon, text)
        }
    }

    fun select(tab: Tab) {
        for ((t, item) in items) {
            val on = t == tab
            item.icon.color = if (on) Ui.ACCENT_INK else Ui.TEXT2
            item.label.setTextColor(if (on) Ui.ACCENT_INK else Ui.TEXT2)
            item.root.isSelected = on
        }
    }
}

/**
 * Cadre dont le contenu ne dépasse pas [maxDp] de large quand [capped] : au-delà, il est centré (marges
 * égales), pour que les écrans pensés pour un téléphone restent lisibles sur une tablette.
 */
class CappedFrame(c: Context, private val maxDp: Int) : FrameLayout(c) {
    var capped = false
        set(v) {
            if (v == field) return
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val pad = if (capped) maxOf(0, (w - Ui.dp(maxDp)) / 2) else 0
        if (paddingLeft != pad || paddingRight != pad) setPadding(pad, paddingTop, pad, paddingBottom)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
