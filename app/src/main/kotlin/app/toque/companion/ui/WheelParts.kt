package app.toque.companion.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.companion.flows.AppearanceFlow
import app.toque.core.i18n.tr
import app.toque.core.look.ColourWheel

/**
 * La roue des couleurs, comme celle du robot (mêmes couleurs, même garde : [ColourWheel]) : l'angle est
 * la teinte, la distance au centre l'intensité ; une poignée de [HANDLE] dp qu'on fait glisser ; au milieu,
 * un disque de la couleur avec une icône foncée. Carrée : l'anneau prend la largeur donnée, jusqu'à
 * [MAX_RING] dp. Son image est calculée hors du fil principal, une fois par taille ([Ring]).
 */
class ColourWheelView(c: Context) : View(c) {
    var color: Int = Ui.ACCENT
        private set
    var hue = 0.0
        private set
    var intensity = 1.0
        private set
    /** Nouvelle couleur choisie au doigt. */
    var onPick: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = Icon(c, Icon.Kind.POT, 0)
    private var dragging = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Diamètre de l'anneau (px) pour la taille de la vue. */
    val ring: Int get() = maxOf(0, minOf(width, height) - Ui.dp(HANDLE))

    /** Montre [c], la poignée à sa place sur la roue (un raccourci, la couleur du téléphone). */
    fun show(c: Int) {
        val s = ColourWheel.place(c)
        hue = s.hue
        intensity = s.intensity
        set(c or (0xFF shl 24), false)
    }

    /** La poignée à la teinte [h] et à l'intensité [t] (bornée). */
    fun pick(h: Double, t: Double) {
        hue = ((h % 360.0) + 360.0) % 360.0
        intensity = Math.min(1.0, Math.max(0.0, t))
        set(ColourWheel.colour(hue, intensity), true)
    }

    private fun set(c: Int, notify: Boolean) {
        val changed = c != color
        color = c
        contentDescription = tr("Roue des couleurs") + " " + AppearanceFlow.hexOf(c)
        invalidate()
        if (notify && changed) onPick?.invoke(c)
    }

    /** Côté le plus grand permis (px) : la feuille le baisse pour que tout tienne sur un petit écran. */
    var limit = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = minOf(Ui.dp(MAX_RING + HANDLE), limit)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) max else minOf(w, max)
        setMeasuredDimension(if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) w else side, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ring > 0) Ring.get(ring) { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val d = ring
        if (d <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val half = d / 2f
        val bmp = Ring.ready(d)
        if (bmp != null) canvas.drawBitmap(bmp, cx - half, cy - half, paint)
        else {
            stroke.color = Ui.SURFACE3
            stroke.strokeWidth = (half * (ColourWheel.RING_OUTER - ColourWheel.RING_INNER)).toFloat()
            canvas.drawCircle(cx, cy, (half * (ColourWheel.RING_OUTER + ColourWheel.RING_INNER) / 2).toFloat(), stroke)
        }
        paint.color = color
        canvas.drawCircle(cx, cy, (half * ColourWheel.RING_INNER).toFloat() - Ui.dpf(6f), paint)
        // L'icône foncée du milieu, comme les pastilles de l'accueil du robot.
        val side = (d * ColourWheel.RING_INNER * 0.6).toInt()
        icon.color = Accent.onAccent(color)
        icon.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
        icon.layout(0, 0, side, side)
        canvas.save()
        canvas.translate(cx - side / 2f, cy - side / 2f)
        icon.draw(canvas)
        canvas.restore()
        // Poignée : rond de la couleur, bordé de blanc puis d'un trait foncé.
        val a = Math.toRadians(hue)
        val r = ColourWheel.radiusOf(intensity) * half
        val hx = cx + (Math.cos(a) * r).toFloat()
        val hy = cy + (Math.sin(a) * r).toFloat()
        val hr = Ui.dpf(HANDLE / 2f)
        paint.color = 0x55000000
        canvas.drawCircle(hx, hy + Ui.dpf(1.5f), hr + Ui.dpf(0.5f), paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(hx, hy, hr, paint)
        paint.color = color
        canvas.drawCircle(hx, hy, hr - Ui.dpf(4f), paint)
        stroke.color = 0x99000000.toInt()
        stroke.strokeWidth = Ui.dpf(1f)
        canvas.drawCircle(hx, hy, hr, stroke)
    }

    /**
     * Le doigt se pose n'importe où sur l'anneau ou un peu au-delà (pas besoin d'attraper la poignée),
     * puis glisse partout ; le défilement de la feuille ne le prend pas. Le disque du milieu ne réagit pas.
     */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dx = (e.x - width / 2f).toDouble()
        val dy = (e.y - height / 2f).toDouble()
        val half = ring / 2.0
        if (half <= 0) return false
        val dist = Math.sqrt(dx * dx + dy * dy)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dist < half * ColourWheel.RING_INNER * 0.8 || dist > half + Ui.dpf(HANDLE / 2f)) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!dragging) return false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                dragging = false
                if (was && e.actionMasked == MotionEvent.ACTION_UP) performClick()
                return was
            }
            else -> return dragging
        }
        val s = ColourWheel.spotAt(dx, dy, half)
        pick(s.hue, s.intensity)
        return true
    }

    /** Fin d'un glissé : comptée comme un toucher (lecteurs d'écran). */
    override fun performClick(): Boolean = super.performClick()

    /**
     * L'image de l'anneau : celle de la taille demandée en dernier, calculée sur un fil à part. Lue et
     * rendue sur le fil principal.
     */
    object Ring {
        private var size = 0
        private var bitmap: Bitmap? = null
        /** Taille demandée en dernier : la seule gardée (une image qui arrive après pour une autre taille est jetée). */
        private var wanted = 0
        private val working = HashSet<Int>()
        private val waiting = ArrayList<Pair<Int, () -> Unit>>()
        private var warmed = false

        /** Essais d'écran : l'image est faite sur le fil appelant (la capture suit aussitôt la mise en page). */
        @Volatile var inline = false

        fun ready(d: Int): Bitmap? = if (size == d) bitmap else null

        /**
         * Prépare la table des couleurs de la roue (une fois par processus, quelques dizaines de
         * millisecondes) sur un fil à part, dès que l'onglet Robot est construit : sans cela, c'est le
         * premier toucher de « Roue des couleurs » qui l'attendrait.
         */
        fun warm() {
            if (warmed || inline) return
            warmed = true
            Thread({ ColourWheel.colour(0.0, 1.0) }, "roue-table").start()
        }

        /** Prépare l'image de [d] px (rien si elle est là) ; [done] sur le fil principal quand elle y est. */
        fun get(d: Int, done: () -> Unit) {
            if (size == d && bitmap != null) return
            wanted = d
            if (inline) {
                bitmap = Bitmap.createBitmap(ColourWheel.ringPixels(d), d, d, Bitmap.Config.ARGB_8888)
                size = d
                done()
                return
            }
            waiting += d to done
            if (!working.add(d)) return
            val main = Handler(Looper.getMainLooper())
            Thread({
                val bmp = Bitmap.createBitmap(ColourWheel.ringPixels(d), d, d, Bitmap.Config.ARGB_8888)
                main.post {
                    working.remove(d)
                    if (wanted == d) {
                        bitmap = bmp
                        size = d
                    }
                    // Seulement ceux qui attendaient cette taille : les autres attendent encore la leur.
                    val calls = waiting.filter { it.first == d }
                    waiting.removeAll(calls)
                    for (f in calls) f.second()
                }
            }, "roue-couleurs").start()
        }
    }

    companion object {
        /** Poignée (dp) : plus large que le doigt. */
        const val HANDLE = 44
        /** Anneau le plus grand (dp). */
        const val MAX_RING = 300
    }
}

/**
 * La roue dans une feuille (fond assombri, carte en bas qui défile si l'écran est petit) : la roue, un
 * aperçu sous elle (pastille de l'accueil du robot, bouton principal, puce choisie, texte en couleur,
 * barre), les dix couleurs du robot en raccourcis, « Annuler » et « Appliquer ». Rien ne change avant
 * « Appliquer » ([onApply]).
 */
class WheelSheet(c: Context, private val onApply: (Int) -> Unit) : FrameLayout(c) {
    val wheel = ColourWheelView(c)
    private val badgeFill = GradientDrawable()
    private val badgeIcon = Icon(c, Icon.Kind.BOOK, 0)
    private val buttonFill = GradientDrawable()
    private val buttonIcon = Icon(c, Icon.Kind.PLAY, 0)
    private val buttonText = Ui.ellipsize(Ui.text(c, tr("Lancer"), 15f, 0, Ui.MEDIUM))
    private val chipFill = GradientDrawable()
    private val chipText = Ui.ellipsize(Ui.text(c, tr("Favoris"), 14f, 0, Ui.MEDIUM))
    private val inkText = Ui.ellipsize(Ui.text(c, tr("Texte en couleur"), 15f, 0, Ui.MEDIUM))
    private val progress = ProgressLine(c).apply { progress = 0.62f }
    private val swatches = ArrayList<Swatch>()
    private val apply: Ui.Button

    init {
        setBackgroundColor(Ui.SCRIM)
        isClickable = true
        setOnClickListener { hide() }
        val card = Ui.vertical(c)
        card.background = Ui.round(Ui.SURFACE, 28f, Ui.STROKE, 1f)
        card.isClickable = true
        Ui.pad(card, 20, 18, 20, 16)
        card.addView(Ui.text(c, tr("Roue des couleurs"), 20f, Ui.TEXT, Ui.MEDIUM, lineHeight = 25f))
        card.addView(Ui.text(c, tr("Faites glisser la poignée sur l'anneau."), 14f, Ui.TEXT2, lineHeight = 19f), Ui.lp(t = 4))
        wheel.onPick = { paint(it) }
        card.addView(wheel, Ui.lp(Ui.MATCH, Ui.WRAP, t = 6))

        // Aperçu : la pastille d'une tuile du robot, puis bouton, puce, texte et barre.
        val preview = Ui.horizontal(c)
        val badge = FrameLayout(c)
        badgeFill.cornerRadius = Ui.dpf(24f)
        badge.background = badgeFill
        badge.addView(badgeIcon, Ui.flp(Ui.dp(24), Ui.dp(24), Gravity.CENTER))
        preview.addView(badge, LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)).apply { rightMargin = Ui.dp(12) })
        val col = Ui.vertical(c)
        val top = Ui.horizontal(c)
        val button = Ui.horizontal(c, Gravity.CENTER)
        button.setPadding(Ui.dp(14), 0, Ui.dp(16), 0)
        buttonFill.cornerRadius = Ui.dpf(18f)
        button.background = buttonFill
        button.addView(buttonIcon, LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { rightMargin = Ui.dp(6) })
        button.addView(buttonText)
        top.addView(button, LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(36)).apply { rightMargin = Ui.dp(8) })
        chipText.gravity = Gravity.CENTER
        chipText.setPadding(Ui.dp(12), 0, Ui.dp(12), 0)
        chipFill.cornerRadius = Ui.dpf(16f)
        chipText.background = chipFill
        top.addView(chipText, LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(32)))
        col.addView(top)
        col.addView(inkText, Ui.lp(t = 6))
        col.addView(progress, Ui.lp(Ui.MATCH, Ui.dp(6), t = 6))
        preview.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        card.addView(preview, Ui.lp(t = 8))

        // Les dix couleurs du robot : un toucher y met la poignée.
        val colours = FlowRow(c, hGap = 2, vGap = 2)
        AppearanceFlow.COLOURS.forEachIndexed { i, color ->
            val sw = Swatch(c, color)
            sw.contentDescription = tr("Couleur %d", i + 1)
            sw.isClickable = true
            sw.isFocusable = true
            sw.background = Ui.pressable(Ui.round(0, 24f), 24f)
            sw.setOnClickListener {
                wheel.show(color)
                paint(color)
            }
            sw.layoutParams = ViewGroup.LayoutParams(Ui.dp(Ui.TOUCH), Ui.dp(Ui.TOUCH))
            swatches += sw
            colours.addView(sw)
        }
        card.addView(colours, Ui.lp(t = 8))

        val buttons = Ui.horizontal(c)
        buttons.addView(Ui.button(c, tr("Annuler"), Ui.Style.SECONDARY, height = 52) { hide() }.also { Ui.ellipsize(it.label, 2) },
            Ui.lp(0, Ui.WRAP, 1f))
        apply = Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, height = 52) {
            hide()
            onApply(wheel.color)
        }.also { Ui.ellipsize(it.label, 2) }
        buttons.addView(apply, Ui.lp(0, Ui.WRAP, 1f, l = 10))
        card.addView(buttons, Ui.lp(t = 12))

        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
        }
        scroll.addView(card)
        addView(scroll, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { setMargins(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12)) })
        visibility = View.GONE
    }

    /**
     * Sur un écran trop bas pour tout montrer, la roue rapetisse (jusqu'à [MIN_SIDE] dp) pour que l'aperçu,
     * les raccourcis et les boutons restent visibles sans défiler ; au-delà, la carte défile.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        wheel.limit = Int.MAX_VALUE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val scroll = getChildAt(0)
        val card = (scroll as ViewGroup).getChildAt(0)
        val over = card.measuredHeight - (MeasureSpec.getSize(heightMeasureSpec) - Ui.dp(24))
        if (over > 0 && wheel.measuredHeight > Ui.dp(MIN_SIDE)) {
            wheel.limit = maxOf(Ui.dp(MIN_SIDE), wheel.measuredHeight - over)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    val showing: Boolean get() = visibility == View.VISIBLE

    companion object {
        /** Côté le plus petit de la roue (dp), poignée comprise. */
        const val MIN_SIDE = 220
    }

    /** Ouvre la feuille sur [color] (celle du téléphone). */
    fun show(color: Int) {
        wheel.show(color)
        paint(color)
        Ui.show(this, true)
    }

    fun hide() = Ui.show(this, false)

    /** L'aperçu, les raccourcis et « Appliquer » dans la couleur [color], sur le thème du téléphone. */
    private fun paint(color: Int) {
        val s = Accent.look(color, Palette.of(Ui.theme))
        badgeFill.setColor(s.accent)
        badgeIcon.color = s.onAccent
        buttonFill.setColor(s.accent)
        buttonIcon.color = s.onAccent
        buttonText.setTextColor(s.onAccent)
        chipFill.setColor(s.accent)
        chipText.setTextColor(s.onAccent)
        inkText.setTextColor(s.ink)
        progress.color = s.accent
        apply.background = Ui.pressable(Ui.round(s.accent, 26f), 26f)
        apply.label.setTextColor(s.onAccent)
        for (sw in swatches) sw.chosen = sw.color == color
    }
}
