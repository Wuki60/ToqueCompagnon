package app.toque.companion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.flows.LookOffer
import app.toque.companion.flows.RobotFollow
import app.toque.core.i18n.tr

/**
 * Pastille d'une couleur (Apparence, questions) : rond plein de la couleur ; [selected] l'entoure d'un
 * anneau de la couleur du texte, avec une coche dessus. Zone tactile de 48 dp quand elle est un bouton.
 */
class Swatch(c: Context, color: Int, private val sizeDp: Int = 36) : View(c) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = android.graphics.Path()
    var color: Int
        get() = fill.color
        set(v) { if (v != fill.color) { fill.color = v; invalidate() } }
    var chosen = false
        set(v) { if (v != field) { field = v; isSelected = v; invalidate() } }

    /** Taille propre : celle de ses paramètres, sinon la pastille et sa marge (un parent qui ne la borne pas). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lp = layoutParams
        val w = if (lp != null && lp.width > 0) lp.width else Ui.dp(sizeDp + 12)
        val h = if (lp != null && lp.height > 0) lp.height else Ui.dp(sizeDp + 12)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = Ui.dpf(sizeDp / 2f)
        canvas.drawCircle(cx, cy, if (chosen) r - Ui.dpf(5f) else r, fill)
        // Contour discret : une couleur claire reste visible sur le thème clair.
        ring.color = Ui.STROKE
        ring.strokeWidth = Ui.dpf(1f)
        canvas.drawCircle(cx, cy, if (chosen) r - Ui.dpf(5f) else r - Ui.dpf(0.5f), ring)
        if (chosen) {
            ring.color = Ui.TEXT
            ring.strokeWidth = Ui.dpf(2f)
            canvas.drawCircle(cx, cy, r - Ui.dpf(1f), ring)
            check.color = Accent.onAccent(fill.color).let { if (Accent.contrast(it, fill.color) >= 3.0) it else 0xFFFFFFFF.toInt() }
            check.strokeWidth = Ui.dpf(2.2f)
            val s = Ui.dpf(12f) / 24f
            path.reset()
            path.moveTo(cx + (5f - 12f) * s, cy + (12.5f - 12f) * s)
            path.lineTo(cx + (10f - 12f) * s, cy + (17.5f - 12f) * s)
            path.lineTo(cx + (19f - 12f) * s, cy + (7f - 12f) * s)
            canvas.drawPath(path, check)
        }
    }
}

/**
 * Question posée sur l'écran (fond assombri, carte en bas), comme [ConfirmSheet], avec des lignes : une
 * pastille de couleur ou une icône, un texte, et un interrupteur quand il y a un choix à faire.
 */
class ChoiceSheet(c: Context, title: String, body: String, rows: List<Row>, yes: String, no: String,
                  private val onAnswer: (yes: Boolean, picked: List<Boolean>) -> Unit) : FrameLayout(c) {
    /** [swatch] : couleur montrée ; [switchable] : un interrupteur, allumé au départ. */
    class Row(val text: String, val swatch: Int? = null, val icon: Icon.Kind? = null, val switchable: Boolean = false)

    private val switches = ArrayList<TextView?>()

    init {
        setBackgroundColor(Ui.SCRIM)
        isClickable = true
        setOnClickListener { answer(false) }
        val card = Ui.vertical(c)
        card.background = Ui.round(Ui.SURFACE, 28f, Ui.STROKE, 1f)
        card.isClickable = true
        Ui.pad(card, 20, 20, 20, 16)
        card.addView(Ui.text(c, title, 20f, Ui.TEXT, Ui.MEDIUM, lineHeight = 25f))
        if (body.isNotEmpty()) card.addView(Ui.text(c, body, 15f, Ui.TEXT2, lineHeight = 21f), Ui.lp(t = 8))
        for (r in rows) {
            val row = Ui.horizontal(c)
            row.minimumHeight = Ui.dp(52)
            when {
                r.swatch != null -> row.addView(Swatch(c, r.swatch, 28).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(32)).apply { rightMargin = Ui.dp(12) })
                r.icon != null -> row.addView(Icon(c, r.icon, Ui.TEXT2), LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)).apply {
                    leftMargin = Ui.dp(4); rightMargin = Ui.dp(16)
                })
            }
            row.addView(Ui.text(c, r.text, 15f, Ui.TEXT, lineHeight = 20f), Ui.lp(0, Ui.WRAP, 1f, r = 8))
            val sw = if (r.switchable) Ui.switch(c, true, r.text) {} else null
            if (sw != null) row.addView(sw)
            switches += sw
            card.addView(row, Ui.lp(t = 6))
        }
        val buttons = Ui.horizontal(c)
        buttons.addView(Ui.button(c, no, Ui.Style.SECONDARY, height = 52) { answer(false) }.also { Ui.ellipsize(it.label, 2) }, Ui.lp(0, Ui.WRAP, 1f))
        buttons.addView(Ui.button(c, yes, Ui.Style.PRIMARY, height = 52) { answer(true) }.also { Ui.ellipsize(it.label, 2) },
            Ui.lp(0, Ui.WRAP, 1f, l = 10))
        card.addView(buttons, Ui.lp(t = 14))
        addView(card, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { setMargins(Ui.dp(12), 0, Ui.dp(12), Ui.dp(12)) })
    }

    private var answered = false

    /** Réponse (une seule fois) : [yes], et pour chaque ligne si elle est retenue (allumée ou sans interrupteur). */
    fun answer(yes: Boolean) {
        if (answered) return
        answered = true
        onAnswer(yes, switches.map { it == null || it.isSelected })
    }

    companion object {
        /** « Mode clair » / « Mode sombre », comme les Réglages du robot. */
        fun themeName(light: Boolean) = if (light) tr("Mode clair") else tr("Mode sombre")

        /**
         * « Le robot a une autre apparence » : sa couleur (pastille) et/ou son thème (en mots) ; avec les
         * deux, un interrupteur chacun, allumés. [answer] : appliquer ou non, et quoi.
         */
        fun offer(c: Context, o: LookOffer, answer: (take: Boolean, accent: Boolean, theme: Boolean) -> Unit): ChoiceSheet {
            val rows = ArrayList<Row>()
            val both = o.accent != null && o.light != null
            if (o.accent != null) rows += Row(tr("Sa couleur"), swatch = o.accent, switchable = both)
            if (o.light != null) rows += Row(themeName(o.light), icon = Icon.Kind.SCREEN, switchable = both)
            return ChoiceSheet(c, tr("Le robot a une autre apparence"),
                if (both) tr("Prendre la même ici ? Choisissez ce que ce téléphone reprend.") else tr("Prendre la même sur ce téléphone ?"),
                rows, tr("Appliquer"), tr("Garder le mien")) { yes, picked ->
                var i = 0
                val a = o.accent != null && picked[i++]
                val t = o.light != null && picked[i]
                answer(yes, a, t)
            }
        }

        /** « Appliquer aussi au robot ? » pour ce qui vient d'être choisi sur le téléphone. */
        fun follow(c: Context, f: RobotFollow, answer: (yes: Boolean) -> Unit): ChoiceSheet {
            val row = if (f.accent != null) Row(tr("Cette couleur"), swatch = f.accent)
                else Row(themeName(f.light == true), icon = Icon.Kind.SCREEN)
            return ChoiceSheet(c, tr("Appliquer aussi au robot ?"), "", listOf(row), tr("Oui"), tr("Non, seulement le téléphone")) { yes, _ ->
                answer(yes)
            }
        }
    }
}
