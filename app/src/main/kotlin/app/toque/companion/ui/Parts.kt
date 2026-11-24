package app.toque.companion.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.toque.companion.ui.screens.IngredientItem
import app.toque.core.i18n.tr

/*
 * Pièces des écrans Recettes, Fiche, Envoyée, Journal, Robot et Appairage.
 */

/** Rangée qui passe à la ligne quand ses enfants ne tiennent pas (pastilles de filtre). */
open class FlowRow(c: Context, private val hGap: Int = 6, private val vGap: Int = 6) : ViewGroup(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0
        var y = 0
        var lineH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == View.GONE) continue
            ch.measure(MeasureSpec.makeMeasureSpec(maxW, if (unbounded) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val gap = if (x > 0) Ui.dp(hGap) else 0
            if (!unbounded && x > 0 && x + gap + ch.measuredWidth > maxW) {
                y += lineH + Ui.dp(vGap)
                x = 0
                lineH = 0
            }
            x += (if (x > 0) Ui.dp(hGap) else 0) + ch.measuredWidth
            widest = maxOf(widest, x)
            lineH = maxOf(lineH, ch.measuredHeight)
        }
        val w = if (unbounded) widest else maxW
        setMeasuredDimension(w + paddingLeft + paddingRight, resolveSize(y + lineH + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == View.GONE) continue
            val gap = if (x > 0) Ui.dp(hGap) else 0
            if (x > 0 && x + gap + ch.measuredWidth > maxW) {
                y += lineH + Ui.dp(vGap)
                x = 0
                lineH = 0
            }
            if (x > 0) x += Ui.dp(hGap)
            ch.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + ch.measuredWidth, paddingTop + y + ch.measuredHeight)
            x += ch.measuredWidth
            lineH = maxOf(lineH, ch.measuredHeight)
        }
    }
}

/**
 * Pastilles de filtre exclusives (Toutes, Récentes… ; Tout, Recettes…) : les [Ui.toggleChip] du
 * robot, à la taille de la maquette (14 sp, 44 dp), passant à la ligne si besoin.
 */
class FilterChips<T>(c: Context, options: List<Pair<T, String>>, onPick: (T) -> Unit) : FlowRow(c) {
    private val chips = options.map { (value, label) ->
        val t = Ui.toggleChip(c, label, false) { onPick(value) }
        t.textSize = 14f
        t.setPadding(Ui.dp(12), 0, Ui.dp(12), 0)
        addView(t)
        value to t
    }
    private var selected: T? = null

    fun select(value: T) {
        if (value == selected) return
        selected = value
        for ((v, t) in chips) Ui.setToggle(t, v == value)
    }
}

/**
 * Pastilles de filtre exclusives sur une seule ligne qui défile à l'horizontale (catalogue : une
 * trentaine de pastilles, comme sur le robot). Mêmes pastilles que [FilterChips] ; la liste peut
 * changer ([set] réutilise les pastilles existantes) et la pastille choisie est ramenée à l'écran.
 */
class ChipRow<T>(c: Context, private val onPick: (T) -> Unit) : HorizontalScrollView(c) {
    private val row = Ui.horizontal(c)
    private var options: List<Pair<T, String>> = emptyList()
    private var selected: T? = null
    private var reveal = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // Les pastilles défilent jusqu'au bord de l'écran, sous la marge.
        clipToPadding = false
        addView(row, LayoutParams(Ui.WRAP, Ui.WRAP))
    }

    /** Pastilles [options] (valeur, texte), [value] choisie. */
    fun set(options: List<Pair<T, String>>, value: T) {
        if (options == this.options && value == selected) return
        if (options != this.options) {
            this.options = options
            for ((i, o) in options.withIndex()) {
                val t = row.getChildAt(i) as? TextView ?: Ui.toggleChip(context, o.second, false) {
                    this.options.getOrNull(i)?.let { onPick(it.first) }
                }.also {
                    it.textSize = 14f
                    it.setPadding(Ui.dp(12), 0, Ui.dp(12), 0)
                    row.addView(it, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { if (i > 0) leftMargin = Ui.dp(GAP) })
                }
                Ui.set(t, o.second)
            }
            while (row.childCount > options.size) row.removeViewAt(row.childCount - 1)
        }
        selected = value
        for ((i, o) in options.withIndex()) {
            val t = row.getChildAt(i) as TextView
            if (t.isSelected != (o.first == value)) Ui.setToggle(t, o.first == value)
        }
        reveal = true
        if (isLaidOut && !isLayoutRequested) revealSelected(smooth = true)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (reveal) revealSelected(smooth = false)
    }

    /** Fait défiler juste assez pour que la pastille choisie soit entière, dans les marges. */
    private fun revealSelected(smooth: Boolean) {
        reveal = false
        val i = options.indexOfFirst { it.first == selected }
        val ch = row.getChildAt(i) ?: return
        val inner = width - paddingLeft - paddingRight
        val x = when {
            ch.left < scrollX -> ch.left
            ch.right > scrollX + inner -> ch.right - inner
            else -> return
        }
        if (smooth) smoothScrollTo(x, 0) else scrollTo(x, 0)
    }

    private companion object {
        const val GAP = 6
    }
}

/**
 * Vignette d'une recette : la photo si elle arrive par [PictureSource], sinon le dégradé et les
 * initiales du robot. [topOnly] arrondit seulement le haut (carte de la grille).
 */
class Thumbnail(c: Context, private val radius: Float, initialsSize: Float, private val topOnly: Boolean = false) : FrameLayout(c) {
    private val initials = Ui.text(c, "", initialsSize, Ui.INITIALS, Ui.LIGHT)
    val image = ImageView(c)
    private var id: String? = null
    private var picture = false
    private var pictures: PictureSource = PictureSource.NONE
    private var widthPx = 0
    /** Demande de photo en cours : annulée quand la vignette change de recette ou quitte l'écran. */
    private var request: PictureSource.Request? = null

    init {
        initials.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(initials, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(image, Ui.flp())
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = Ui.dpf(radius)
                outline.setRoundRect(0, 0, view.width, view.height + if (topOnly) r.toInt() else 0, r)
            }
        }
    }

    /** Affiche la recette [recipeId] ; demande sa photo à [pictures] si [picture]. La même recette : rien ne change (pas de blanc). */
    fun bind(recipeId: String, title: String, picture: Boolean, pictures: PictureSource, widthDp: Int) {
        val changed = recipeId != id
        id = recipeId
        if (changed || background == null) background = Ui.gradient(Ui.placeholderColors(title), radius).apply {
            if (topOnly) Ui.dpf(radius).let { r -> cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        Ui.set(initials, Ui.initials(title))
        if (!changed) {
            // Même recette sans photo et sans demande en cours (la précédente a fini sans photo) : redemander.
            if (this.picture && request == null && image.drawable == null) ask()
            return
        }
        request?.cancel()
        request = null
        image.animate().cancel()
        image.alpha = 1f
        image.setImageDrawable(null)
        this.picture = picture
        this.pictures = pictures
        widthPx = Ui.dp(widthDp)
        if (picture) ask()
    }

    /** Une demande ; finie sans photo, elle est oubliée : le prochain bind ou attach redemande (jamais ce rappel). */
    private fun ask() {
        val rid = id ?: return
        var sync = true
        var ended = false
        val r = pictures.load(rid, widthPx) { bmp: Bitmap? ->
            if (id != rid) return@load
            if (bmp == null) {
                ended = true
                if (!sync) request = null
                return@load
            }
            // Arrivée plus tard : fondu bref ; trouvée en mémoire (pendant l'appel) ou image plus grande : tout de suite.
            val fade = !sync && image.drawable == null
            image.setImageBitmap(bmp)
            if (fade) {
                image.alpha = 0f
                image.animate().alpha(1f).setDuration(FADE_MS).start()
            }
        }
        sync = false
        request = if (ended) null else r
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Revenue à l'écran sans sa photo (demande abandonnée en partant) : la redemander.
        if (picture && request == null && image.drawable == null) ask()
    }

    override fun onDetachedFromWindow() {
        request?.cancel()
        request = null
        super.onDetachedFromWindow()
    }

    companion object {
        const val FADE_MS = 120L
    }
}

/** Barre du haut des écrans ouverts par-dessus : retour (44 dp), titre, sous-titre ; l'écran peut ajouter un élément à droite. */
class BackBar(c: Context, backDescription: String, onBack: () -> Unit) : LinearLayout(c) {
    val title: TextView = Ui.ellipsize(Ui.text(c, "", 20f, Ui.TEXT, Ui.MEDIUM, lineHeight = 25f), 2)
    val subtitle: TextView = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2))

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        Ui.pad(this, 12, 12, 20, 6)
        addView(Ui.iconButton(c, Icon.Kind.BACK, backDescription, visible = 44, iconSize = 22) { onBack() })
        val titles = Ui.vertical(c)
        titles.addView(title)
        titles.addView(subtitle, Ui.lp(t = 2))
        addView(titles, Ui.lp(0, Ui.WRAP, 1f, l = 10))
    }
}

/** Message centré d'une liste vide ou en chargement : icône ou roue, titre, texte, bouton facultatif. */
class EmptyState(c: Context, icon: Icon.Kind, buttonLabel: String? = null, onButton: () -> Unit = {}) : LinearLayout(c) {
    private val icon = Icon(c, icon, Ui.TEXT3)
    private val spinner = ProgressBar(c).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT_INK)
    }
    val title: TextView = Ui.text(c, "", 17f, Ui.TEXT, Ui.MEDIUM, lineHeight = 22f).apply { gravity = Gravity.CENTER }
    val body: TextView = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 20f).apply { gravity = Gravity.CENTER }
    private val button: View? = buttonLabel?.let { Ui.button(c, it, Ui.Style.OUTLINE, height = 44, textSize = 14f) { onButton() } }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        Ui.pad(this, 28, 28, 28, 28)
        this.icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(this.icon, LayoutParams(Ui.dp(32), Ui.dp(32)))
        addView(spinner, LayoutParams(Ui.dp(32), Ui.dp(32)))
        addView(title, Ui.lp(t = 12))
        addView(body, Ui.lp(t = 6))
        if (button != null) addView(button, Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
    }

    /** [loading] remplace l'icône par une roue ; [withButton] montre le bouton (Réessayer). */
    fun set(title: String, body: String, loading: Boolean = false, withButton: Boolean = false) {
        Ui.set(this.title, title)
        Ui.set(this.body, body)
        Ui.show(this.body, body.isNotEmpty())
        Ui.show(icon, !loading)
        Ui.show(spinner, loading)
        button?.let { Ui.show(it, withButton) }
    }
}

/**
 * Feuille de confirmation posée sur l'écran (fond assombri, carte en bas) : titre, texte,
 * « Annuler » et le bouton de l'action. Toucher le fond annule.
 */
class ConfirmSheet(c: Context, title: String, message: String, confirm: String,
                   confirmStyle: Ui.Style = Ui.Style.DANGER, cancel: String = tr("Annuler"), onConfirm: () -> Unit) : FrameLayout(c) {
    /** Le texte sous le titre (changé selon le cas, avant [show]). */
    val body: TextView = Ui.text(c, message, 15f, Ui.TEXT2, lineHeight = 21f)

    init {
        setBackgroundColor(Ui.SCRIM)
        isClickable = true
        setOnClickListener { hide() }
        val card = Ui.vertical(c)
        card.background = Ui.round(Ui.SURFACE, 28f, Ui.STROKE, 1f)
        card.isClickable = true
        Ui.pad(card, 20, 20, 20, 16)
        card.addView(Ui.text(c, title, 20f, Ui.TEXT, Ui.MEDIUM, lineHeight = 25f))
        card.addView(body, Ui.lp(t = 8))
        val row = Ui.horizontal(c)
        row.addView(Ui.button(c, cancel, Ui.Style.SECONDARY, height = 52) { hide() }.also { Ui.ellipsize(it.label, 2) }, Ui.lp(0, Ui.WRAP, 1f))
        row.addView(Ui.button(c, confirm, confirmStyle, height = 52) { hide(); onConfirm() }.also { Ui.ellipsize(it.label, 2) },
            Ui.lp(0, Ui.WRAP, 1f, l = 10))
        card.addView(row, Ui.lp(t = 18))
        addView(card, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { setMargins(Ui.dp(12), 0, Ui.dp(12), Ui.dp(12)) })
        visibility = View.GONE
    }

    val showing: Boolean get() = visibility == View.VISIBLE

    fun show() = Ui.show(this, true)
    fun hide() = Ui.show(this, false)
}

/** Rangée d'une carte : textes à gauche (titre 15 sp, détail 13 sp facultatif), élément à droite. */
fun settingRow(c: Context, title: String, detail: String?, trailing: View?, minHeight: Int): LinearLayout {
    val row = Ui.horizontal(c)
    row.minimumHeight = Ui.dp(minHeight)
    val col = Ui.vertical(c)
    col.addView(Ui.text(c, title, 15f, lineHeight = 20f))
    if (detail != null) col.addView(Ui.text(c, detail, 13f, Ui.TEXT2, lineHeight = 18f), Ui.lp(t = 1))
    row.addView(col, Ui.lp(0, Ui.WRAP, 1f, t = 8, r = 12, b = 8))
    if (trailing != null) row.addView(trailing)
    return row
}

/**
 * Onglets en segments (Ingrédients / Étapes) : même dessin que ceux de l'écran Étapes, pilule
 * claire sous l'onglet choisi.
 *
 * Avec [accent], c'est un choix exclusif de la famille des cartes de réglage (Sens normal | Inverse,
 * Lame | Fouet) : conteneur arrondi sur [Ui.SURFACE], segments de même largeur et de même hauteur
 * (48 dp au moins), le choisi rempli de l'accent avec un texte sombre comme [Ui.toggleChip] ; un
 * libellé trop long passe sur deux lignes, puis s'abrège.
 */
class SegmentTabs(c: Context, labels: List<String>, private val accent: Boolean = false, onPick: (Int) -> Unit) : LinearLayout(c) {
    private val tabs = labels.mapIndexed { i, label ->
        val t = Ui.text(c, label, 15f, if (accent) Ui.TEXT else Ui.TEXT2, Ui.MEDIUM)
        t.gravity = Gravity.CENTER
        t.minHeight = Ui.dp(Ui.TOUCH)
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onPick(i) }
        if (accent) {
            Ui.ellipsize(t, 2)
            t.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(4))
            // Même hauteur pour les deux segments, même si un seul passe à la ligne.
            addView(t, Ui.lp(0, Ui.MATCH, 1f, l = if (i > 0) 4 else 0))
        } else addView(t, Ui.lp(0, Ui.WRAP, 1f, l = if (i > 0) 4 else 0))
        t
    }
    var selected = -1
        private set
    private var on = true
    private var dim = false

    init {
        orientation = HORIZONTAL
        isBaselineAligned = false
        if (accent) {
            background = Ui.round(Ui.SURFACE, 20f)
            setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
        } else {
            background = Ui.round(Ui.SURFACE, 26f)
            setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2))
        }
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        tabs.forEachIndexed { i, t ->
            val sel = i == index
            if (accent) {
                t.background = Ui.pressable(Ui.round(if (sel) Ui.ACCENT else 0, 16f), 16f)
                t.setTextColor(if (sel) Ui.ON_ACCENT else Ui.TEXT)
            } else {
                t.background = Ui.inset(Ui.pressable(Ui.round(if (sel) Ui.SURFACE3 else 0, 22f), 22f), 0f, 2f)
                t.setTextColor(if (sel) Ui.TEXT else Ui.TEXT2)
            }
            t.isSelected = sel
        }
    }

    /**
     * [enabled] faux : segments inactifs (sans effet au toucher, annoncés comme désactivés) et
     * rangée grisée ; [dimmed] : grisée mais encore utilisable (sens de rotation sans mélange).
     */
    fun setState(enabled: Boolean, dimmed: Boolean = false) {
        if (enabled == on && dimmed == dim) return
        on = enabled
        dim = dimmed
        for (t in tabs) t.isEnabled = enabled
        alpha = if (!enabled || dimmed) 0.35f else 1f
    }
}

/**
 * Carte des ingrédients : nom à gauche, quantité grise à droite (vide : rien), traits entre les lignes.
 * Les lignes sont gardées d'un rendu à l'autre : un changement de quantités ne change que des textes.
 */
class IngredientCard(c: Context) : LinearLayout(c) {
    private class Row(val divider: View?, val root: LinearLayout, val name: TextView, val quantity: TextView)
    private val rows = ArrayList<Row>()
    private var shown: List<IngredientItem>? = null

    init {
        orientation = VERTICAL
        background = Ui.round(Ui.SURFACE, 20f)
        Ui.pad(this, 16, 6, 16, 6)
    }

    fun set(items: List<IngredientItem>) {
        if (items == shown) return
        shown = items
        while (rows.size < items.size) {
            val divider = if (rows.isEmpty()) null else Ui.divider(context).also { addView(it) }
            val root = Ui.horizontal(context)
            root.minimumHeight = Ui.dp(40)
            val name = Ui.text(context, "", 15f, lineHeight = 20f)
            val quantity = Ui.tabular(Ui.text(context, "", 15f, Ui.TEXT2))
            root.addView(name, Ui.lp(0, Ui.WRAP, 1f, t = 8, b = 8))
            root.addView(quantity, Ui.lp(Ui.WRAP, Ui.WRAP, l = 12))
            addView(root)
            rows += Row(divider, root, name, quantity)
        }
        while (rows.size > items.size) {
            val r = rows.removeAt(rows.size - 1)
            removeView(r.root)
            r.divider?.let { removeView(it) }
        }
        items.forEachIndexed { i, it ->
            val r = rows[i]
            Ui.set(r.name, it.name)
            Ui.set(r.quantity, it.quantity)
            Ui.show(r.quantity, it.quantity.isNotEmpty())
        }
    }
}

/**
 * Réglage d'une carte de cuisson : libellé, valeur et note, boutons − et + (48 dp) ; [onStep] reçoit −1 ou
 * +1. Fond [Ui.SURFACE] sur la page, [Ui.SURFACE2] dans une feuille posée sur une carte.
 */
class ValueStepper(c: Context, label: String, minusLabel: String, plusLabel: String, background: Int = Ui.SURFACE, onStep: (Int) -> Unit) {
    val root: LinearLayout = Ui.horizontal(c)
    // Valeur (24 sp) et note (13 sp) dans un seul texte : la note passe à la ligne sans être coupée.
    private val value = Ui.ellipsize(Ui.tabular(Ui.text(c, "", 13f, Ui.TEXT2)), 3)
    private val minus = Ui.iconButton(c, Icon.Kind.MINUS, minusLabel, style = Ui.Style.OUTLINE) { onStep(-1) }
    private val plus = Ui.iconButton(c, Icon.Kind.PLUS, plusLabel, style = Ui.Style.OUTLINE) { onStep(1) }

    init {
        root.background = Ui.round(background, 20f)
        Ui.pad(root, 16, 10, 10, 10)
        val col = Ui.vertical(c)
        col.addView(Ui.ellipsize(Ui.text(c, label, 12f, Ui.TEXT2)))
        col.addView(value, Ui.lp(t = 2))
        root.addView(col, Ui.lp(0, Ui.WRAP, 1f, r = 10))
        root.addView(minus)
        root.addView(plus, (plus.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = Ui.dp(10) })
    }

    private var old = 0

    fun set(v: String, color: Int, n: String) {
        val t = android.text.SpannableStringBuilder(v)
        t.setSpan(android.text.style.RelativeSizeSpan(24f / 13f), 0, v.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.setSpan(android.text.style.ForegroundColorSpan(color), 0, v.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (n.isNotEmpty()) t.append("\u2002").append(n)
        if (value.text.toString() != t.toString() || old != color) value.text = t
        old = color
    }

    fun enable(on: Boolean) = enable(on, on)

    fun enable(minusOk: Boolean, plusOk: Boolean) {
        Ui.setButtonEnabled(minus, minusOk)
        Ui.setButtonEnabled(plus, plusOk)
    }
}
