package app.toque.companion.ui.screens

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Icon
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.LivePage
import app.toque.companion.ui.Link
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.ProgressLine
import app.toque.companion.ui.RecipeHeader
import app.toque.companion.ui.Route
import app.toque.companion.ui.Screen
import app.toque.companion.ui.StepMarker
import app.toque.companion.ui.StepStatus
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.iconLine
import app.toque.companion.ui.mixed
import app.toque.core.i18n.tr

/** Recette guidée, étape de pesée : le robot attend le cuisinier (maquette « À vous de jouer : pesée »). */
data class PeseeState(
    val link: Link = Link.CONNECTED,
    val recipe: String,
    val stepIndex: Int,
    val stepCount: Int,
    /** « Ajoutez 500 g d'eau ». */
    val instruction: String,
    /** Poids sur la balance depuis la dernière remise à zéro. */
    val weightG: Int,
    val targetG: Int,
    /** Ingrédients pesés à cette étape (faits, puis celui en cours). */
    val items: List<WeighItem>,
    /** Étape suivante : nom et réglages, ou null. */
    val nextAction: String? = null,
    val nextSettings: String = "",
)

/** Ingrédient d'une pesée : [done] avec son poids final, ou en cours ([weightG] sur [targetG]). */
data class WeighItem(val name: String, val done: Boolean, val weightG: Int, val targetG: Int? = null)

class PeseeScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<PeseeState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private lateinit var header: RecipeHeader
    private val instruction = Ui.text(c, "", 20f, Ui.TEXT, Ui.MEDIUM, lineHeight = 26f)
    private val weight = Ui.tabular(Ui.text(c, "", 64f, Ui.TEXT, Ui.LIGHT).apply { letterSpacing = -1.5f / 64f })
    private val left = Ui.tabular(Ui.text(c, "", 15f, Ui.TEXT2))
    private val bar = ProgressLine(c)
    private val target = Ui.tabular(Ui.text(c, "", 12f, Ui.TEXT2))
    private val itemsCard = Ui.card(c)
    private val itemRows = ArrayList<ItemRow>()
    private val nextCard = Ui.card(c)
    private val nextTitle = Ui.text(c, "", 15f)
    private val nextSettings = Ui.text(c, "", 13f, Ui.TEXT2)

    override fun build(): View {
        page = LivePage(c)
        header = RecipeHeader(c) { nav.open(Route.ETAPES) }
        page.body.addView(header)

        val waiting = Ui.chip(c, tr("Le robot vous attend"), color = Ui.WARN, bg = Ui.WARN_SOFT, dot = Ui.WARN)
        page.body.addView(waiting, Ui.lp(Ui.WRAP, Ui.WRAP, l = 20, t = 14, r = 20))

        val card = Ui.card(c, 28f)
        Ui.pad(card, 20, 20, 20, 20)
        card.addView(instruction)
        val row = Ui.baselineRow(c)
        row.addView(weight)
        row.addView(Ui.text(c, tr("g"), 24f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, l = 6))
        row.addView(Ui.spacer(c, weight = 1f))
        row.addView(left, Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
        card.addView(row, Ui.lp(t = 14))
        card.addView(bar, Ui.lp(Ui.MATCH, Ui.dp(10), t = 14))
        val scale = Ui.horizontal(c)
        scale.addView(Ui.text(c, "0", 12f, Ui.TEXT2), Ui.lp(0, Ui.WRAP, 1f))
        scale.addView(target)
        card.addView(scale, Ui.lp(t = 6))
        card.addView(Ui.text(c, tr("Le robot sonne quand la quantité est atteinte."), 13f, Ui.TEXT2, lineHeight = 18f), Ui.lp(t = 14))
        card.addView(Ui.button(c, tr("Remettre la balance à zéro"), Ui.Style.OUTLINE, Icon.Kind.SCALE, height = 48, iconSize = 20) {
            actions.tare()
        }, Ui.lp(t = 14))
        page.body.addView(card, Ui.lp(l = 20, t = 12, r = 20))

        Ui.pad(itemsCard, 16, 6, 16, 6)
        page.body.addView(itemsCard, Ui.lp(l = 20, t = 12, r = 20))

        Ui.pad(nextCard, 16, 14, 16, 14)
        val head = Ui.horizontal(c)
        head.addView(nextTitle, Ui.lp(0, Ui.WRAP, 1f, r = 8))
        head.addView(nextSettings)
        nextCard.addView(head)
        nextCard.addView(iconLine(c, Icon.Kind.SCREEN, tr("Cette étape se lance sur le robot")), Ui.lp(t = 8))
        page.body.addView(nextCard, Ui.lp(l = 20, t = 12, r = 20))
        return page.root
    }

    override fun bind(s: PeseeState, old: PeseeState?) {
        page.header?.set(s.link)
        header.set(s.recipe, s.stepIndex, s.stepCount, paused = false)
        Ui.set(instruction, s.instruction)
        Ui.set(weight, "${s.weightG}")
        val rest = s.targetG - s.weightG
        Ui.set(left, if (rest >= 0) tr("encore %d g", rest) else tr("%d g de trop", -rest))
        bar.progress = if (s.targetG > 0) s.weightG.toFloat() / s.targetG else 0f
        Ui.set(target, Fmt.grams(s.targetG))

        while (itemRows.size < s.items.size) {
            val r = ItemRow(c)
            if (itemRows.isNotEmpty()) itemsCard.addView(r.divider)
            itemsCard.addView(r.root)
            itemRows += r
        }
        while (itemRows.size > s.items.size) {
            val r = itemRows.removeAt(itemRows.size - 1)
            itemsCard.removeView(r.root)
            itemsCard.removeView(r.divider)
        }
        s.items.forEachIndexed { i, it -> itemRows[i].bind(it) }
        Ui.show(itemsCard, s.items.isNotEmpty())

        Ui.show(nextCard, s.nextAction != null)
        if (s.nextAction != null) {
            nextTitle.text = mixed(tr("Ensuite") to 2, " · " to 0, s.nextAction to 1)
            Ui.set(nextSettings, s.nextSettings)
        }
    }

    private class ItemRow(c: Context) {
        val divider = Ui.divider(c)
        val root: LinearLayout = Ui.horizontal(c)
        private val marker = StepMarker(c)
        private val name: TextView = Ui.ellipsize(Ui.text(c, "", 15f), 2)
        private val value: TextView = Ui.tabular(Ui.text(c, "", 15f))

        init {
            root.minimumHeight = Ui.dp(44)
            root.addView(marker, LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)).apply { rightMargin = Ui.dp(12) })
            root.addView(name, Ui.lp(0, Ui.WRAP, 1f, t = 6, r = 12, b = 6))
            root.addView(value)
        }

        fun bind(w: WeighItem) {
            marker.set(if (w.done) StepStatus.DONE else StepStatus.CURRENT, 0)
            Ui.set(name, w.name)
            name.typeface = if (w.done) Ui.REGULAR else Ui.MEDIUM
            Ui.setColor(name, if (w.done) Ui.TEXT2 else Ui.TEXT)
            Ui.set(value, if (w.done || w.targetG == null) Fmt.grams(w.weightG) else tr("%d sur %d g", w.weightG, w.targetG))
            Ui.setColor(value, if (w.done) Ui.TEXT2 else Ui.TEXT)
        }
    }
}
