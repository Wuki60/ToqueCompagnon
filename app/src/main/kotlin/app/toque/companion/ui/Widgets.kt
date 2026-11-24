package app.toque.companion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr

/** Point de couleur (état de la connexion, du robot). */
class Dot(c: Context, color: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    var color: Int
        get() = paint.color
        set(v) { if (v != paint.color) { paint.color = v; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
    }
}

/** Anneau de progression : piste, arc depuis midi dans le sens horaire, bouts arrondis. */
class Ring(c: Context, private val strokeDp: Float) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    var progress = 0f
        set(v) { val p = v.coerceIn(0f, 1f); if (p != field) { field = p; invalidate() } }
    var color = Ui.ACCENT
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val sw = Ui.dpf(strokeDp)
        val size = minOf(width, height).toFloat()
        val rad = (size - sw) / 2f
        val cx = width / 2f
        val cy = height / 2f
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        paint.strokeWidth = sw
        paint.color = Ui.SURFACE3
        canvas.drawOval(r, paint)
        if (progress > 0f) {
            paint.color = color
            canvas.drawArc(r, -90f, 360f * progress, false, paint)
        }
    }
}

/** Barre de progression arrondie (pesée, étape en cours), comme `ProgressLine` du robot. */
class ProgressLine(c: Context) : View(c) {
    var progress = 0f
        set(v) { val p = v.coerceIn(0f, 1f); if (p != field) { field = p; invalidate() } }
    var color = Ui.ACCENT
        set(v) { if (v != field) { field = v; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        paint.color = Ui.SURFACE3
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        if (progress > 0f) {
            paint.color = color
            r.set(0f, 0f, maxOf(h, width * progress), h)
            canvas.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}

/** Avancement d'une recette : un segment par étape (faites, en cours, à venir). */
class SegmentBar(c: Context) : View(c) {
    private var count = 0
    private var current = 0
    private var currentColor = Ui.ACCENT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    /** [current] : index (0…) de l'étape en cours. */
    fun set(count: Int, current: Int, currentColor: Int) {
        if (count == this.count && current == this.current && currentColor == this.currentColor) return
        this.count = count
        this.current = current
        this.currentColor = currentColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 0) return
        val gap = Ui.dpf(6f)
        val w = (width - gap * (count - 1)) / count
        val h = height.toFloat()
        for (i in 0 until count) {
            paint.color = when {
                i < current -> Ui.ACCENT_DONE
                i == current -> currentColor
                else -> Ui.STROKE
            }
            val x = i * (w + gap)
            r.set(x, 0f, x + w, h)
            canvas.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}

enum class StepStatus { DONE, CURRENT, TODO }

/** Repère d'étape de 22 dp : coche (faite), point orange (en cours) ou numéro (à venir). */
class StepMarker(c: Context) : View(c) {
    private var status = StepStatus.TODO
    private var number = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Ui.REGULAR }
    private val path = Path()

    fun set(status: StepStatus, number: Int) {
        if (status == this.status && number == this.number) return
        this.status = status
        this.number = number
        contentDescription = when (status) {
            StepStatus.DONE -> tr("Étape faite")
            StepStatus.CURRENT -> tr("Étape en cours")
            StepStatus.TODO -> null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val sw = Ui.dpf(2f)
        when (status) {
            StepStatus.DONE -> {
                fill.color = Ui.ACCENT_DONE
                canvas.drawCircle(cx, cy, size / 2f, fill)
                // Coche d'Icon.CHECK, sur 14 dp, trait de la maquette (2,8 sur la grille de 24).
                val s = Ui.dpf(14f) / 24f
                canvas.save()
                canvas.translate(cx - 12 * s, cy - 12 * s)
                canvas.scale(s, s)
                stroke.color = Ui.TEXT
                stroke.strokeWidth = 2.8f
                path.reset(); path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f)
                canvas.drawPath(path, stroke)
                canvas.restore()
            }
            StepStatus.CURRENT -> {
                stroke.color = Ui.ACCENT_INK
                stroke.strokeWidth = sw
                canvas.drawCircle(cx, cy, size / 2f - sw / 2f, stroke)
                fill.color = Ui.ACCENT_INK
                canvas.drawCircle(cx, cy, Ui.dpf(5f), fill)
            }
            StepStatus.TODO -> {
                stroke.color = Ui.STROKE
                stroke.strokeWidth = sw
                canvas.drawCircle(cx, cy, size / 2f - sw / 2f, stroke)
                text.color = Ui.TEXT2
                text.textSize = Ui.sp(context, 12f).toFloat()
                val fm = text.fontMetrics
                canvas.drawText(number.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, text)
            }
        }
    }
}

/** État de la liaison avec le robot, affiché en haut de « En direct ». */
enum class Link { CONNECTED, CONNECTING, OFFLINE }

/** En-tête des écrans « En direct » (et des racines des autres onglets) : titre et pastille de connexion. */
class LiveHeader(c: Context, titleText: String = tr("En direct")) : LinearLayout(c) {
    private val title = Ui.text(c, titleText, 26f, Ui.TEXT, Ui.MEDIUM).apply { letterSpacing = -0.2f / 26f }
    private val dot = Dot(c, Ui.OK)
    private val label = Ui.text(c, "", 14f, Ui.TEXT)
    private var link: Link? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        Ui.pad(this, 20, 18, 20, 6)
        addView(Ui.ellipsize(title), Ui.lp(0, Ui.WRAP, 1f, r = 8))
        val pill = Ui.horizontal(c)
        pill.minimumHeight = Ui.dp(32)
        pill.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4))
        pill.background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        pill.addView(dot, LayoutParams(Ui.dp(8), Ui.dp(8)).apply { rightMargin = Ui.dp(8) })
        pill.addView(label)
        addView(pill, Ui.lp(Ui.WRAP, Ui.WRAP))
        set(Link.CONNECTED)
    }

    fun set(l: Link) {
        if (l == link) return
        link = l
        val (text, color) = when (l) {
            Link.CONNECTED -> tr("Connecté") to Ui.OK
            Link.CONNECTING -> tr("Connexion…") to Ui.WARN
            Link.OFFLINE -> tr("Hors ligne") to Ui.DANGER
        }
        label.text = text
        dot.color = color
    }
}

/** En-tête « RECETTE · ÉTAPE n SUR m », lien « Les étapes », titre, segments d'avancement. */
class RecipeHeader(c: Context, onSteps: () -> Unit) : LinearLayout(c) {
    private val overline = Ui.overline(c)
    private val title = Ui.ellipsize(Ui.text(c, "", 24f, Ui.TEXT, Ui.MEDIUM, lineHeight = 29f), 2)
    private val segments = SegmentBar(c)

    init {
        orientation = VERTICAL
        Ui.pad(this, 20, 0, 20, 0)
        val row = Ui.horizontal(c)
        row.addView(Ui.ellipsize(overline), Ui.lp(0, Ui.WRAP, 1f, r = 8))
        val link = Ui.horizontal(c)
        link.minimumHeight = Ui.dp(Ui.TOUCH)
        link.addView(Ui.text(c, tr("Les étapes"), 14f, Ui.ACCENT_INK, Ui.MEDIUM))
        link.addView(Icon(c, Icon.Kind.CHEVRON, Ui.ACCENT_INK), LayoutParams(Ui.dp(18), Ui.dp(18)).apply { leftMargin = Ui.dp(2) })
        link.isClickable = true
        link.isFocusable = true
        link.background = Ui.pressable(Ui.round(0, 24f), 24f)
        link.setOnClickListener { onSteps() }
        // Ligne de 48 dp (zone tactile du lien) ; le titre remonte sous elle, comme dans la maquette
        // où le lien déborde de sa ligne de 14 px.
        row.addView(link, Ui.lp(Ui.WRAP, Ui.WRAP))
        addView(row, Ui.lp(b = -15))
        addView(title)
        addView(segments, Ui.lp(Ui.MATCH, Ui.dp(4), t = 12))
    }

    /** [stepIndex] : index (0…) de l'étape en cours ; [paused] colore le segment en jaune. */
    fun set(recipe: String, stepIndex: Int, stepCount: Int, paused: Boolean) {
        Ui.set(overline, tr("Recette · étape %d sur %d", stepIndex + 1, stepCount))
        Ui.set(title, recipe)
        segments.set(stepCount, stepIndex, if (paused) Ui.WARN else Ui.ACCENT)
    }
}

/** Petite carte de mesure : libellé, grande valeur, ligne secondaire facultative. */
class StatTile(c: Context, label: String, valueSize: Float, valueLine: Float, padH: Int = 12) : LinearLayout(c) {
    val value: TextView = Ui.tabular(Ui.text(c, "", valueSize, Ui.TEXT, lineHeight = valueLine))
    val sub: TextView = Ui.ellipsize(Ui.text(c, "", 12f, Ui.TEXT2), 2)

    init {
        orientation = VERTICAL
        background = Ui.round(Ui.SURFACE, 20f)
        Ui.pad(this, padH, 12, padH, 12)
        addView(Ui.fitText(c, label, 12f, Ui.TEXT2))
        addView(Ui.ellipsize(value), Ui.lp(t = 2))
        addView(sub, Ui.lp(t = 2))
    }

    fun set(v: String, color: Int = Ui.TEXT, s: String? = null) {
        Ui.set(value, v)
        Ui.setColor(value, color)
        Ui.show(sub, s != null)
        if (s != null) Ui.set(sub, s)
    }
}

/** Rangée de cartes de même largeur et de même hauteur (grille de la maquette, espacement 10 dp). */
fun tileRow(c: Context, vararg tiles: View): LinearLayout {
    val row = LinearLayout(c)
    row.orientation = LinearLayout.HORIZONTAL
    row.isBaselineAligned = false
    tiles.forEachIndexed { i, t ->
        row.addView(t, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i > 0) leftMargin = Ui.dp(10) })
    }
    return row
}

/** Barre d'actions du bas : boutons en pilule de 56 dp (pause, +1 min, arrêt…). */
fun actionBar(c: Context, vararg buttons: Pair<View, Float>): LinearLayout {
    val row = Ui.horizontal(c)
    Ui.pad(row, 20, 10, 20, 12)
    buttons.forEachIndexed { i, (b, weight) ->
        row.addView(b, LinearLayout.LayoutParams(0, Ui.WRAP, weight).apply { if (i > 0) leftMargin = Ui.dp(10) })
    }
    return row
}

/** Bouton pause / reprise des barres d'actions : icône seule, style secondaire du robot. */
fun pauseButton(c: Context, onClick: (View) -> Unit): Ui.Button =
    Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PAUSE, description = tr("Mettre en pause"), onClick = onClick)

/** Bouton arrêt : rouge, carré blanc, comme dans la barre machine du robot. */
fun stopButton(c: Context, onClick: (View) -> Unit): Ui.Button =
    Ui.button(c, "", Ui.Style.DANGER, Icon.Kind.STOP, description = tr("Arrêter"), onClick = onClick)

/**
 * Texte avec le bouton de reprise du robot dessiné dans la ligne : « Appuyez sur [▶] … ».
 * [template] contient %s à la place du bouton.
 */
fun withPlayGlyph(c: Context, template: String): CharSequence {
    val i = template.indexOf("%s")
    if (i < 0) return template
    val b = SpannableStringBuilder(template)
    b.replace(i, i + 2, "▶")
    b.setSpan(PlayGlyphSpan(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return b
}

/** Texte lu par les lecteurs d'écran à la place du bouton dessiné. */
fun spokenPlayGlyph(template: String): String = template.replace("%s", tr("le bouton de reprise"))

/** Pilule 30 × 22 dp avec le triangle de lecture, posée sur la ligne de texte. */
class PlayGlyphSpan : ReplacementSpan() {
    private val path = Path()
    private val r = RectF()

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        Ui.dp(30f)

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val fm = paint.fontMetrics
        val cy = y + (fm.ascent + fm.descent) / 2f
        val h = Ui.dpf(22f)
        r.set(x, cy - h / 2f, x + Ui.dpf(30f), cy + h / 2f)
        p.color = Ui.SURFACE3
        canvas.drawRoundRect(r, h / 2f, h / 2f, p)
        val s = Ui.dpf(13f) / 24f
        val ox = r.centerX() - 12 * s
        val oy = cy - 12 * s
        path.reset()
        path.moveTo(ox + 7f * s, oy + 4.5f * s); path.lineTo(ox + 19.5f * s, oy + 12f * s); path.lineTo(ox + 7f * s, oy + 19.5f * s); path.close()
        p.color = Ui.TEXT
        canvas.drawPath(path, p)
    }
}

/** Ligne d'information grise avec une petite icône (« Cette étape se lance sur le robot »). */
fun iconLine(c: Context, icon: Icon.Kind, text: String, color: Int = Ui.TEXT2, iconSize: Int = 16): LinearLayout {
    val row = Ui.horizontal(c)
    row.addView(Icon(c, icon, color), LinearLayout.LayoutParams(Ui.dp(iconSize), Ui.dp(iconSize)).apply { rightMargin = Ui.dp(8) })
    row.addView(Ui.text(c, text, 13f, color, lineHeight = 18f), Ui.lp(0, Ui.WRAP, 1f))
    return row
}

/**
 * Pastille d'icône ronde de 36 dp (programmes, dernière activité) : icône de l'accent sur la surface ;
 * active (programme en cours), pleine de l'accent avec l'icône foncée dessus, comme les pastilles de
 * l'accueil du robot.
 */
class IconBadge(c: Context, kind: Icon.Kind) : FrameLayout(c) {
    val icon = Icon(c, kind, Ui.ACCENT_INK)

    init {
        addView(icon, Ui.flp(Ui.dp(20), Ui.dp(20), Gravity.CENTER))
        setActive(false)
    }

    fun setActive(active: Boolean) {
        background = Ui.round(if (active) Ui.ACCENT else Ui.SURFACE2, 18f)
        icon.color = if (active) Ui.ON_ACCENT else Ui.ACCENT_INK
    }
}

/** Texte d'une ligne « libellé · valeur » où une partie est en gras moyen (« Cuire · 12:40 »). */
fun mixed(vararg parts: Pair<String, Int>): CharSequence {
    val b = SpannableStringBuilder()
    for ((s, style) in parts) {
        val start = b.length
        b.append(s)
        if (style != 0) b.setSpan(style.toSpan(), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return b
}

/** Styles de [mixed] : 1 = moyen (500), 2 = gris secondaire. */
private fun Int.toSpan(): Any = when (this) {
    1 -> MediumSpan()
    else -> android.text.style.ForegroundColorSpan(Ui.TEXT2)
}

/** Police moyenne dans un texte (TypefaceSpan(Typeface) n'existe qu'à partir d'Android 9). */
class MediumSpan : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = Ui.MEDIUM }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = Ui.MEDIUM }
}
