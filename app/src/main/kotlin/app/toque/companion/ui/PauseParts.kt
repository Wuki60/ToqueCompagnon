package app.toque.companion.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr

/**
 * Carte « En pause » commune aux écrans Pause et Reprise : pastille de la cause, temps restant,
 * explication, compte à rebours avant l'annulation automatique.
 */
class PausedCard(c: Context) : LinearLayout(c) {
    val chip = Ui.chip(c, "", Icon.Kind.PAUSE, Ui.WARN, Ui.WARN_SOFT)
    private val time = Ui.tabular(Ui.text(c, "", 32f, Ui.TEXT, Ui.LIGHT))
    private val message = Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 21f)
    private val cancelLine = Ui.horizontal(c)
    private val cancelDivider = Ui.divider(c)
    private val cancelText = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 19f)

    init {
        orientation = VERTICAL
        background = Ui.round(Ui.SURFACE, 28f, Ui.WARN_LINE, 1f)
        Ui.pad(this, 20, 20, 20, 20)
        addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP))
        val row = Ui.baselineRow(c)
        row.addView(Ui.ellipsize(Ui.text(c, tr("En pause"), 32f)), Ui.lp(0, Ui.WRAP, 1f, r = 8))
        row.addView(time)
        row.addView(Ui.text(c, tr("restant"), 13f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, l = 6))
        addView(row, Ui.lp(t = 12))
        addView(message, Ui.lp(t = 12))
        addView(cancelDivider, Ui.lp(Ui.MATCH, maxOf(1, Ui.dp(1)), t = 12))
        cancelLine.addView(Icon(c, Icon.Kind.CLOCK, Ui.TEXT2), LayoutParams(Ui.dp(18), Ui.dp(18)).apply { rightMargin = Ui.dp(8) })
        cancelLine.addView(cancelText, Ui.lp(0, Ui.WRAP, 1f))
        addView(cancelLine, Ui.lp(t = 12))
    }

    fun setChip(icon: Icon.Kind, label: String, iconSize: Int) {
        (chip.lead as Icon).set(icon)
        chip.lead!!.layoutParams = (chip.lead!!.layoutParams as LayoutParams).apply { width = Ui.dp(iconSize); height = Ui.dp(iconSize) }
        Ui.set(chip.label, label)
    }

    /** [autoCancelMs] : temps avant que le robot annule de lui-même, null s'il n'y en a pas. */
    fun set(remainingMs: Long, message: String, autoCancelMs: Long?) {
        Ui.set(time, Ui.clock(remainingMs))
        Ui.set(this.message, message)
        Ui.show(cancelLine, autoCancelMs != null)
        Ui.show(cancelDivider, autoCancelMs != null)
        if (autoCancelMs != null) {
            val clock = Ui.shortClock(autoCancelMs)
            val t = tr("Annulation automatique dans %s", clock)
            val b = SpannableStringBuilder(t)
            val i = t.lastIndexOf(clock)
            if (i >= 0) b.setSpan(ForegroundColorSpan(Ui.TEXT), i, i + clock.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (cancelText.text.toString() != t) cancelText.text = b
        }
    }
}

/** Carte « POUR REPRENDRE » : titre de section et lignes ajoutées par l'écran. */
fun resumeCard(c: Context): LinearLayout {
    val card = Ui.card(c)
    Ui.pad(card, 16, 16, 16, 16)
    card.addView(Ui.overline(c, tr("Pour reprendre")))
    return card
}

/** Ligne numérotée d'une carte « POUR REPRENDRE ». */
fun numberedLine(c: Context, number: Int, text: TextView): LinearLayout {
    val row = Ui.horizontal(c, android.view.Gravity.TOP)
    val marker = StepMarker(c)
    marker.set(StepStatus.TODO, number)
    row.addView(marker, LayoutParams(Ui.dp(22), Ui.dp(22)).apply { rightMargin = Ui.dp(12) })
    row.addView(text, Ui.lp(0, Ui.WRAP, 1f))
    return row
}

private typealias LayoutParams = LinearLayout.LayoutParams
