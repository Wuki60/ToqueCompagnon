package app.toque.companion.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Boîte à outils du téléphone, portée de celle du robot (app/…/ui/Ui.kt) : mêmes couleurs, mêmes
 * formes. Les tailles sont en dp (1 px de la maquette = 1 dp), les textes en sp (ils suivent la
 * taille de police du téléphone).
 */
object Ui {
    // Couleurs du thème choisi sur le téléphone (Palette.DARK ou Palette.LIGHT, celles du robot) et de son
    // accent (voir [setLook]). Jamais figées : un écran les lit quand il se construit ou se met à jour.
    private var palette = Palette.DARK
    /** Thème clair. */
    val light: Boolean get() = palette.light
    var BG = palette.bg
        private set
    var SURFACE = palette.surface
        private set
    var SURFACE2 = palette.surface2
        private set
    var SURFACE3 = palette.surface3
        private set
    var STROKE = palette.stroke
        private set
    var TEXT = palette.text
        private set
    var TEXT2 = palette.text2
        private set
    var TEXT3 = palette.text3
        private set
    /** Texte secondaire atténué (détail d'une étape faite, bouton inactif). */
    var TEXT_MUTED = palette.textMuted
        private set
    var COOL = palette.cool
        private set
    var OK = palette.ok
        private set
    var WARN = palette.warn
        private set
    var DANGER = palette.danger
        private set
    /** Texte posé sur un fond [COOL] ou [WARN]. */
    var ON_COOL = palette.onCool
        private set
    var ON_WARN = palette.onWarn
        private set
    var SCRIM = palette.scrim
        private set
    /** Onde d'un appui. */
    var RIPPLE = palette.ripple
        private set
    /** Fond jaune discret des pastilles d'attente ou de pause. */
    var WARN_SOFT = palette.warnSoft
        private set
    /** Contour jaune d'une carte en pause. */
    var WARN_LINE = palette.warnLine
        private set
    /** Couleur des initiales sur une vignette sans photo. */
    var INITIALS = palette.initials
        private set

    // Ce qui ne change pas avec le thème : posé sur une photo ou sur l'image de la caméra, toujours sombre.
    /** Voile sombre posé sur une photo (pastille « Perso », rond de l'étoile). */
    const val PHOTO_VEIL = 0xB3000000.toInt()
    /** Texte ou icône sur ce voile : clair dans les deux thèmes. */
    const val ON_PHOTO = 0xFFF3F5F9.toInt()
    /** Marque (tag) des vues qui gardent leurs couleurs quel que soit le thème (photo, caméra). */
    const val FIXED = "couleurs fixes"
    /** Zone de la caméra (lecture du QR code) : sombre dans les deux thèmes, comme une image. */
    const val CAMERA_BG = 0xFF131D2E.toInt()
    /** Texte gris sur [CAMERA_BG]. */
    const val CAMERA_TEXT = 0xFF9CA8BC.toInt()

    // Accent : la couleur choisie (voir [setLook]), l'orange d'origine sinon. Ses teintes sont calculées
    // (Accent.look) ; avec l'orange sur le thème sombre, ce sont exactement celles de la maquette.
    private var shades = Accent.look(null, palette)
    /** L'accent en fond (bouton principal, puce choisie, pastille pleine, anneau, barre). */
    var ACCENT = shades.accent
        private set
    /**
     * L'accent en texte ou en icône sur une surface : l'accent lui-même sur le thème sombre, une
     * version plus foncée de la même teinte sur le clair (l'accent, clair, s'y lirait mal).
     */
    var ACCENT_INK = shades.ink
        private set
    /** Accent transparent à 20 % (fond d'une note). */
    var ACCENT_DIM = shades.dim
        private set
    /** Étape faite : accent éteint. */
    var ACCENT_DONE = shades.done
        private set
    /** Fond discret de l'accent (élément en cours, pastille « En attente »). */
    var ACCENT_SOFT = shades.soft
        private set
    /** Contour d'une carte en cours. */
    var ACCENT_LINE = shades.line
        private set
    /** Texte ou icône posé sur l'accent. */
    var ON_ACCENT = shades.onAccent
        private set

    /** Couleur choisie (« #RRGGBB »), vide pour l'orange d'origine. */
    var accentHex = ""
        private set
    /** « light » ou « dark ». */
    val theme: String get() = if (light) "light" else "dark"
    /** Augmente à chaque changement de couleurs : les écrans construits avant sont à refaire. */
    var lookSeq = 0
        private set

    /** Dégradés des vignettes sans photo. */
    private var placeholders = palette.placeholders

    /**
     * Couleur et thème du téléphone : [accent] « #RRGGBB » (vide ou illisible : l'orange d'origine),
     * [theme] « light » ou le sombre. Une couleur trop sombre pour le thème sombre est éclaircie
     * (Accent.readable). Renvoie true si les couleurs ont changé.
     */
    fun setLook(accent: String, theme: String = ""): Boolean {
        val parsed = Accent.parse(accent)
        val p = Palette.of(theme)
        val sh = Accent.look(parsed, p)
        accentHex = if (parsed != null) accent.uppercase(java.util.Locale.ROOT) else ""
        if (p == palette && sh == shades) return false
        palette = p
        shades = sh
        BG = p.bg; SURFACE = p.surface; SURFACE2 = p.surface2; SURFACE3 = p.surface3; STROKE = p.stroke
        TEXT = p.text; TEXT2 = p.text2; TEXT3 = p.text3; TEXT_MUTED = p.textMuted
        COOL = p.cool; OK = p.ok; WARN = p.warn; DANGER = p.danger; ON_COOL = p.onCool; ON_WARN = p.onWarn
        SCRIM = p.scrim; RIPPLE = p.ripple; WARN_SOFT = p.warnSoft; WARN_LINE = p.warnLine; INITIALS = p.initials
        placeholders = p.placeholders
        ACCENT = sh.accent; ACCENT_INK = sh.ink; ACCENT_DIM = sh.dim; ACCENT_DONE = sh.done
        ACCENT_SOFT = sh.soft; ACCENT_LINE = sh.line; ON_ACCENT = sh.onAccent
        lookSeq++
        return true
    }

    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Densité de l'écran (px par dp) ; [init] la lit au démarrage de l'activité. */
    var density = 1f
        private set

    fun init(c: Context) {
        density = c.resources.displayMetrics.density
    }

    fun dp(v: Float): Int = Math.round(v * density)
    fun dp(v: Int): Int = Math.round(v * density)
    fun dpf(v: Float): Float = v * density

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** Plus petite zone tactile (dp). */
    const val TOUCH = 48

    // ---- Formes -------------------------------------------------------------------------------

    fun round(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radius)
            if (strokeWidth > 0) setStroke(maxOf(1, dp(strokeWidth)), strokeColor)
        }

    /** Contour en tirets (bouton inactif « Reprise sur le robot »). */
    fun dashed(color: Int, radius: Float, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radius)
            setStroke(maxOf(1, dp(1)), strokeColor, dpf(4f), dpf(3f))
        }

    fun pressable(base: Drawable, radius: Float, ripple: Int = RIPPLE): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), base, round(Color.WHITE, radius))

    /**
     * Fond dessiné plus petit que la vue : la zone tactile garde 48 dp alors que la forme visible
     * garde la taille de la maquette (44 dp, par exemple).
     */
    fun inset(d: Drawable, h: Float, v: Float): Drawable = NoPaddingInset(d, dp(h), dp(v))

    /** InsetDrawable qui ne change pas le padding de la vue (le padding reste celui du code). */
    private class NoPaddingInset(d: Drawable, h: Int, v: Int) : InsetDrawable(d, h, v, h, v) {
        override fun getPadding(padding: android.graphics.Rect): Boolean {
            padding.set(0, 0, 0, 0)
            return false
        }
    }

    // ---- Textes -------------------------------------------------------------------------------

    /** Texte en sp ; [lineHeight] (sp) fixe l'interligne des textes sur plusieurs lignes. */
    fun text(c: Context, s: CharSequence = "", size: Float = 15f, color: Int = TEXT, face: Typeface = REGULAR,
             lineHeight: Float = 0f): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            if (lineHeight > 0f) setLineHeightCompat(this, sp(c, lineHeight))
        }

    /**
     * Hauteur de ligne : TextView.setLineHeight n'existe qu'à partir d'Android 9 (API 28) ; avant,
     * le même résultat par l'interligne (comme TextViewCompat d'AndroidX).
     */
    fun setLineHeightCompat(t: TextView, px: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 28) t.lineHeight = px
        else {
            val font = t.paint.getFontMetricsInt(null)
            if (px != font) t.setLineSpacing((px - font).toFloat(), 1f)
        }
    }

    /**
     * Texte d'une seule ligne, rapetissé de sa taille jusqu'à [minScale] fois cette taille pour
     * tenir dans sa largeur (comme `Ui.fitText` du robot) ; points de suspension au-delà.
     */
    fun fitText(c: Context, s: String, size: Float, color: Int = TEXT, face: Typeface = REGULAR, minScale: Float = 0.75f): TextView =
        object : TextView(c) {
            private val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, c.resources.displayMetrics)

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val avail = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                if (View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED && avail > 0) {
                    val p = android.graphics.Paint(paint)
                    var px = base
                    p.textSize = px
                    val str = text.toString()
                    while (px > base * minScale && p.measureText(str) > avail) { px -= 1f; p.textSize = px }
                    if (px != textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }

    /**
     * Curseur, poignées et sélection d'un champ dans la couleur choisie (Android 10 et plus ; avant,
     * ceux du thème Android, l'orange d'origine). Avec l'orange sur le sombre, rien ne change.
     */
    fun tintField(e: android.widget.EditText) {
        if (ACCENT_INK == Accent.DEFAULT) return
        e.highlightColor = (ACCENT and 0x00FFFFFF) or 0x66000000
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val ink = ACCENT_INK
        e.textCursorDrawable = GradientDrawable().apply { setColor(ink); setSize(maxOf(1, dp(2)), 1) }
        e.textSelectHandle?.mutate()?.let { it.setTint(ink); e.setTextSelectHandle(it) }
        e.textSelectHandleLeft?.mutate()?.let { it.setTint(ink); e.setTextSelectHandleLeft(it) }
        e.textSelectHandleRight?.mutate()?.let { it.setTint(ink); e.setTextSelectHandleRight(it) }
    }

    /** Chiffres à chasse fixe (compteurs qui changent plusieurs fois par seconde). */
    fun tabular(t: TextView): TextView {
        t.fontFeatureSettings = "tnum"
        return t
    }

    /** Petit titre de section en capitales espacées (« RECETTE · ÉTAPE 5 SUR 6 »). */
    fun overline(c: Context, s: String = ""): TextView =
        text(c, s, 12f, TEXT2, MEDIUM).apply { letterSpacing = 0.8f / 12f; isAllCaps = true }

    fun sp(c: Context, v: Float): Int =
        Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.resources.displayMetrics))

    fun ellipsize(t: TextView, lines: Int = 1): TextView {
        t.maxLines = lines
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    // ---- Mise en page -------------------------------------------------------------------------

    fun vertical(c: Context, gravity: Int = Gravity.START): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setGravity(gravity)
    }

    fun horizontal(c: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setGravity(gravity)
        isBaselineAligned = false
    }

    /** Rangée alignée sur la ligne de base des textes (grande valeur et unité, « 372 g »). */
    fun baselineRow(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        isBaselineAligned = true
    }

    /** Paramètres de LinearLayout ; largeur et hauteur en px (MATCH, WRAP ou dp(…)), marges en dp. */
    fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
           l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    fun flp(w: Int = MATCH, h: Int = MATCH, gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h, gravity)

    fun pad(v: View, l: Int, t: Int, r: Int, b: Int): View {
        v.setPadding(dp(l), dp(t), dp(r), dp(b))
        return v
    }

    /**
     * Zone qui défile sous une barre fixe : un court fondu en haut (le contenu qui glisse sous la
     * barre ne paraît pas coupé net), aucun en bas.
     */
    fun scroll(c: Context, child: View): ScrollView = object : ScrollView(c) {
        override fun getBottomFadingEdgeStrength(): Float = 0f
    }.apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        topFade(this)
        addView(child)
    }

    /** Fondu du haut d'une zone qui défile (ScrollView, grille), même longueur partout. */
    fun topFade(v: View) {
        v.isVerticalFadingEdgeEnabled = true
        v.setFadingEdgeLength(dp(FADE))
    }

    /** Longueur du fondu en haut des zones qui défilent (dp). */
    const val FADE = 20

    fun spacer(c: Context, w: Int = 0, h: Int = 0, weight: Float = 0f): View =
        View(c).apply { layoutParams = LinearLayout.LayoutParams(if (w == 0 && weight > 0) 0 else dp(w), dp(h), weight) }

    /** Trait de séparation (1 dp) dans une carte. */
    fun divider(c: Context, color: Int = SURFACE3): View = View(c).apply {
        background = ColorDrawable(color)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1)))
    }

    /** Carte : surface arrondie (rayon 20 dp, 28 dp pour la carte principale d'un écran). */
    fun card(c: Context, radius: Float = 20f, color: Int = SURFACE, stroke: Int = 0): LinearLayout = vertical(c).apply {
        background = round(color, radius, stroke, if (stroke != 0) 1f else 0f)
    }

    fun show(v: View, visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        if (v.visibility != vis) v.visibility = vis
    }

    /** Change un texte seulement s'il a changé (mises à jour plusieurs fois par seconde). */
    fun set(t: TextView, s: CharSequence) {
        if (t.text.toString() != s.toString()) t.text = s
    }

    fun setColor(t: TextView, color: Int) {
        if (t.currentTextColor != color) t.setTextColor(color)
    }

    // ---- Boutons (mêmes styles que le robot) --------------------------------------------------

    enum class Style { PRIMARY, SECONDARY, DANGER, GHOST, COOL, OUTLINE }

    fun colors(style: Style): Pair<Int, Int> = when (style) {
        Style.PRIMARY -> ACCENT to ON_ACCENT
        Style.SECONDARY -> SURFACE3 to TEXT
        Style.DANGER -> DANGER to Color.WHITE
        Style.GHOST -> Color.TRANSPARENT to TEXT2
        Style.COOL -> COOL to ON_COOL
        Style.OUTLINE -> SURFACE2 to TEXT
    }

    fun buttonBackground(style: Style, radius: Float, insetV: Float = 0f): Drawable {
        val (bg, _) = colors(style)
        val base = when (style) {
            Style.GHOST -> round(Color.TRANSPARENT, radius, STROKE, 1f)
            Style.OUTLINE -> round(SURFACE2, radius, STROKE, 1f)
            else -> round(bg, radius)
        }
        val d = pressable(base, radius)
        return if (insetV > 0f) inset(d, 0f, insetV) else d
    }

    /**
     * Bouton arrondi avec icône facultative, comme `Ui.button` du robot. [height] est la hauteur
     * visible (dp) ; la zone tactile fait au moins 48 dp. Un bouton sans texte reçoit [description]
     * pour les lecteurs d'écran.
     */
    fun button(c: Context, label: String, style: Style = Style.SECONDARY, icon: Icon.Kind? = null,
               height: Int = 56, textSize: Float = 15f, iconSize: Int = 24, description: String? = null,
               onClick: (View) -> Unit): Button {
        val b = Button(c, style, height)
        if (icon != null) {
            b.icon = Icon(c, icon, colors(style).second)
            b.addView(b.icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply {
                if (label.isNotEmpty()) rightMargin = dp(8)
            })
        }
        b.label = text(c, label, textSize, colors(style).second, MEDIUM).apply { gravity = Gravity.CENTER }
        if (label.isNotEmpty()) b.addView(b.label, LinearLayout.LayoutParams(WRAP, WRAP))
        if (description != null) b.contentDescription = description
        b.setOnClickListener { onClick(it) }
        return b
    }

    /** Bouton de [button] : garde son icône et son libellé pour les mises à jour sur place. */
    class Button(c: Context, style: Style, val visibleHeight: Int) : LinearLayout(c) {
        var icon: Icon? = null
        lateinit var label: TextView
        private val insetV = maxOf(0f, (TOUCH - visibleHeight) / 2f)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(maxOf(TOUCH, visibleHeight))
            setPadding(dp(16), 0, dp(16), 0)
            background = buttonBackground(style, visibleHeight / 2f, insetV)
            isClickable = true
            isFocusable = true
        }

        fun setStyle(style: Style) {
            background = buttonBackground(style, visibleHeight / 2f, insetV)
            val fg = colors(style).second
            icon?.color = fg
            label.setTextColor(fg)
        }
    }

    fun setButtonEnabled(b: View, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.35f
    }

    /** Bouton rond d'icône seule (−, +, retour) : forme de [visible] dp, zone tactile de 48 dp. */
    fun iconButton(c: Context, kind: Icon.Kind, description: String, visible: Int = 48, iconSize: Int = 22,
                   style: Style = Style.OUTLINE, onClick: (View) -> Unit): FrameLayout {
        val f = FrameLayout(c)
        val ins = maxOf(0f, (TOUCH - visible) / 2f)
        f.background = inset(buttonBackground(style, visible / 2f), ins, ins)
        f.addView(Icon(c, kind, colors(style).second), flp(dp(iconSize), dp(iconSize), Gravity.CENTER))
        f.contentDescription = description
        f.isClickable = true
        f.isFocusable = true
        f.setOnClickListener { onClick(it) }
        f.layoutParams = LinearLayout.LayoutParams(dp(maxOf(TOUCH, visible)), dp(maxOf(TOUCH, visible)))
        return f
    }

    // ---- Pastilles ------------------------------------------------------------------------------

    /** Pastille d'information (icône ou point + texte), comme `Ui.chip` du robot. */
    fun chip(c: Context, label: String, icon: Icon.Kind? = null, color: Int = TEXT2, bg: Int = SURFACE2,
             size: Float = 14f, height: Int = 32, dot: Int = 0, iconSize: Int = 18, padH: Int = 14): Chip {
        val row = Chip(c)
        row.minimumHeight = dp(height)
        row.setPadding(dp(padH), dp(3), dp(padH), dp(3))
        row.background = round(bg, height / 2f)
        if (dot != 0) {
            row.lead = Dot(c, dot)
            row.addView(row.lead, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        } else if (icon != null) {
            row.lead = Icon(c, icon, color)
            row.addView(row.lead, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { rightMargin = dp(8) })
        }
        row.label = text(c, label, size, color, MEDIUM)
        row.addView(row.label, LinearLayout.LayoutParams(WRAP, WRAP))
        return row
    }

    class Chip(c: Context) : LinearLayout(c) {
        var lead: View? = null
        lateinit var label: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    /** Puce sélectionnable, comme `Ui.toggleChip` du robot ; zone tactile de 48 dp. */
    fun toggleChip(c: Context, label: String, selected: Boolean, height: Int = 44, onClick: () -> Unit): TextView {
        val t = text(c, label, 15f, TEXT, MEDIUM)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(maxOf(TOUCH, height))
        t.setPadding(dp(16), 0, dp(16), 0)
        setToggle(t, selected, height)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun setToggle(t: TextView, selected: Boolean, height: Int = 44) {
        val ins = maxOf(0f, (TOUCH - height) / 2f)
        t.background = inset(pressable(if (selected) round(ACCENT, height / 2f) else round(SURFACE2, height / 2f, STROKE, 1f), height / 2f), 0f, ins)
        t.setTextColor(if (selected) ON_ACCENT else TEXT)
        t.isSelected = selected
    }

    /** Couleur liée à une température (comme le robot) : froid bleu, tiède jaune, chaud orange, très chaud rouge. */
    /** Couleur d'une température du bol ; non mesurée (« — ») : discrète. */
    fun bowlColor(c: Int?): Int = if (c == null) TEXT2 else tempColor(c)

    fun tempColor(c: Int): Int = when {
        c >= 90 -> DANGER
        c >= 60 -> ACCENT_INK
        c >= 40 -> WARN
        else -> COOL
    }

    fun clock(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    // ---- Vignettes de recette (mêmes couleurs et initiales que le robot) ---------------------

    /** Deux couleurs stables pour la vignette d'une recette sans photo (app/…/ui/Ui.kt). */
    fun placeholderColors(seed: String): IntArray {
        val p = placeholders
        val i = Math.abs(seed.hashCode()) % p.size
        return intArrayOf(p[i], p[(i + 3) % p.size])
    }

    /** Initiales d'une vignette sans photo : « VP » pour « Velouté de potimarron ». */
    fun initials(title: String): String {
        val words = title.split(' ', '-', '\'').filter { it.length > 2 }
        return words.take(2).joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { title.take(1).uppercase() }
    }

    /** Dégradé en diagonale (haut gauche vers bas droite) ; [radius] en dp. */
    fun gradient(colors: IntArray, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dpf(radius) }


    // ---- Interrupteur ---------------------------------------------------------------------------

    /**
     * Interrupteur « Oui / Non » des réglages du robot : pilule orange (oui) ou grise (non),
     * 64 × 44 dp visibles, 48 dp au toucher. Le toucher bascule l'affichage tout de suite et
     * appelle [onChange] ; l'état reçu ensuite par `bind` (voir [setSwitch]) fait foi.
     */
    fun switch(c: Context, checked: Boolean, description: String, onChange: (Boolean) -> Unit): TextView {
        val t = text(c, "", 15f, TEXT, MEDIUM)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(TOUCH)
        t.contentDescription = description
        t.isClickable = true
        t.isFocusable = true
        t.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = host.isSelected
            }
        }
        setSwitch(t, checked)
        t.setOnClickListener {
            val on = !t.isSelected
            setSwitch(t, on)
            onChange(on)
        }
        t.layoutParams = LinearLayout.LayoutParams(dp(64), WRAP)
        return t
    }

    fun setSwitch(t: TextView, checked: Boolean) {
        if (t.isSelected == checked && t.background != null) return
        t.isSelected = checked
        t.text = if (checked) app.toque.core.i18n.tr("Oui") else app.toque.core.i18n.tr("Non")
        t.setTextColor(if (checked) ON_ACCENT else TEXT2)
        t.background = inset(pressable(round(if (checked) ACCENT else SURFACE3, 22f), 22f), 0f, 2f)
    }

    /** Compte à rebours court, sans zéro devant les minutes (« 9:12 »). */
    fun shortClock(ms: Long): String {
        val s = (ms + 999) / 1000
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}
