package app.toque.companion.ui.screens

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.ui.Dot
import app.toque.companion.ui.Icon
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.LivePage
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.ProgressLine
import app.toque.companion.ui.Screen
import app.toque.companion.ui.StepMarker
import app.toque.companion.ui.StepStatus
import app.toque.companion.ui.StopRecipe
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.iconLine
import app.toque.companion.ui.mixed
import app.toque.core.i18n.tr

/** Toutes les étapes de la recette suivie (maquette « Étapes de la recette »). */
data class EtapesState(
    val recipe: String,
    val servings: Int,
    val steps: List<EtapeItem>,
    /** Onglet « Ingrédients » (absent de la maquette) : nom et quantité. */
    val ingredients: List<IngredientItem> = emptyList(),
    /** Barre du bas : l'étape en cours sur le robot, null s'il n'y en a pas. */
    val now: NowBar? = null,
    /**
     * Écran racine de « En direct » (le robot attend le cuisinier sur une étape qui n'est pas une
     * pesée) : pas de bouton retour, il n'y a rien derrière.
     */
    val root: Boolean = false,
    /** « Arrêter la recette » proposé (robot qui le sait faire) ; [canStopRecipe] : utilisable maintenant. */
    val stopRecipeOffered: Boolean = false,
    val canStopRecipe: Boolean = false,
)

data class EtapeItem(
    val title: String,
    /** « À la main », « Pesée · 21 g », « 3 min · 100 °C · vitesse 1 »… */
    val detail: String,
    val status: StepStatus,
    /** Avertissement en jaune (« Contenu chaud : à confirmer sur le robot »), ou null. */
    val warning: String? = null,
    /** Étape de cuisson en cours : avancement, temps restant, température du bol. */
    val progress: StepProgress? = null,
    /**
     * Étape en cours qui attend le cuisinier : ce qu'il faut faire, et que cela se fait sur le robot
     * (« À faire à la main, puis « Suivant » sur le robot »…). La carte montre alors la pastille
     * « Le robot vous attend » et le texte entier de l'étape.
     */
    val waiting: String? = null,
)

data class StepProgress(val fraction: Float, val remainingMs: Long, val tempC: Int?)

data class IngredientItem(val name: String, val quantity: String)

/**
 * Barre du bas : le programme de l'étape en cours ; avec [end], le programme arrêté que le robot
 * affiche encore (« Arrêté », « Pause trop longue : programme annulé »…), fermé par OK.
 */
data class NowBar(val action: String, val remainingMs: Long, val paused: Boolean = false,
                  val end: String? = null, val canOk: Boolean = false)

class EtapesScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<EtapesState>(c) {
    override val tab = Tab.LIVE
    private val title = Ui.ellipsize(Ui.text(c, "", 20f, Ui.TEXT, Ui.MEDIUM, lineHeight = 25f))
    private val subtitle = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2))
    private lateinit var stepsTab: TextView
    private lateinit var ingredientsTab: TextView
    private val stepList = Ui.vertical(c)
    private val stepRows = ArrayList<StepRow>()
    private val ingredientCard = Ui.card(c)
    private val nowBar = Ui.horizontal(c)
    private val nowDot = Dot(c, Ui.ACCENT_INK)
    private val nowText = Ui.ellipsize(Ui.tabular(Ui.text(c, "", 15f)))
    private lateinit var nowPause: Ui.Button
    private lateinit var nowStop: Ui.Button
    private lateinit var nowOk: Ui.Button
    private var showIngredients = false
    private var ingredientsShown: List<IngredientItem>? = null
    private lateinit var back: View
    private val stopRecipe = StopRecipe(c) { actions.stopRecipe() }

    override fun build(): View {
        val page = LivePage(c, withHeader = false)
        // Barre du haut : retour (44 dp visibles, 48 dp au toucher), titre et sous-titre.
        val top = Ui.horizontal(c)
        Ui.pad(top, 12, 12, 20, 6)
        back = Ui.iconButton(c, Icon.Kind.BACK, tr("Retour au suivi en direct"), visible = 44, iconSize = 22) { nav.back() }
        top.addView(back)
        val titles = Ui.vertical(c)
        titles.addView(title)
        titles.addView(subtitle, Ui.lp(t = 2))
        top.addView(titles, Ui.lp(0, Ui.WRAP, 1f, l = 10))
        titlesBox = titles
        // Titre fixé en haut ; les onglets et les étapes défilent dessous.
        page.top.addView(top)

        // Onglets Étapes / Ingrédients.
        val seg = Ui.horizontal(c)
        seg.background = Ui.round(Ui.SURFACE, 26f)
        seg.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2))
        stepsTab = segment(tr("Étapes")) { select(false) }
        ingredientsTab = segment(tr("Ingrédients")) { select(true) }
        seg.addView(stepsTab, Ui.lp(0, Ui.WRAP, 1f))
        seg.addView(ingredientsTab, Ui.lp(0, Ui.WRAP, 1f, l = 4))
        page.body.addView(seg, Ui.lp(l = 20, t = 6, r = 20))

        page.body.addView(stepList, Ui.lp(l = 20, t = 20, r = 20))
        Ui.pad(ingredientCard, 16, 2, 16, 2)
        page.body.addView(ingredientCard, Ui.lp(l = 20, t = 20, r = 20))
        // Arrêter toute la recette : en bas de la liste, loin du Stop rouge de la barre.
        page.body.addView(stopRecipe.button, Ui.lp(Ui.WRAP, Ui.WRAP, l = 20, t = 18, r = 20).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Barre de l'étape en cours, fixée en bas.
        nowBar.minimumHeight = Ui.dp(60)
        nowBar.setPadding(Ui.dp(18), Ui.dp(4), Ui.dp(8), Ui.dp(4))
        nowBar.background = Ui.round(Ui.SURFACE, 30f, Ui.STROKE, 1f)
        nowBar.addView(nowDot, LinearLayout.LayoutParams(Ui.dp(8), Ui.dp(8)).apply { rightMargin = Ui.dp(10) })
        nowBar.addView(nowText, Ui.lp(0, Ui.WRAP, 1f, r = 10))
        nowPause = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PAUSE, height = 44, iconSize = 20, description = tr("Mettre en pause")) {
            if (state?.now?.paused == true) actions.resume() else actions.pause()
        }
        nowPause.setPadding(0, 0, 0, 0)
        nowBar.addView(nowPause, LinearLayout.LayoutParams(Ui.dp(58), Ui.WRAP).apply { rightMargin = Ui.dp(10) })
        nowStop = Ui.button(c, "", Ui.Style.DANGER, Icon.Kind.STOP, height = 44, iconSize = 20, description = tr("Arrêter")) { actions.stop() }
        nowStop.setPadding(0, 0, 0, 0)
        nowBar.addView(nowStop, LinearLayout.LayoutParams(Ui.dp(58), Ui.WRAP))
        // Étape arrêtée encore affichée sur le robot : OK la ferme des deux côtés.
        nowOk = Ui.button(c, "OK", Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 44, iconSize = 20) { actions.clearEnded() }
        nowOk.setPadding(Ui.dp(14), 0, Ui.dp(16), 0)
        nowBar.addView(nowOk, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        val barBox = Ui.vertical(c)
        barBox.addView(nowBar, Ui.lp(l = 20, r = 20, b = 12))
        page.bar(barBox)
        select(false)
        val frame = android.widget.FrameLayout(c)
        frame.addView(page.root, Ui.flp())
        frame.addView(stopRecipe.sheet, Ui.flp())
        return frame
    }

    override fun onBack(): Boolean {
        if (!stopRecipe.sheet.showing) return false
        stopRecipe.sheet.hide()
        return true
    }

    override fun onHide() = stopRecipe.sheet.hide()

    private fun segment(label: String, onClick: () -> Unit): TextView {
        val t = Ui.text(c, label, 15f, Ui.TEXT2, Ui.MEDIUM)
        t.gravity = Gravity.CENTER
        t.minHeight = Ui.dp(Ui.TOUCH)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /** Couleurs changées : le nouvel écran garde l'onglet choisi (étapes ou ingrédients). */
    override fun carry(old: Screen<*>) {
        showIngredients = (old as EtapesScreen).showIngredients
    }

    private fun select(ingredients: Boolean) {
        showIngredients = ingredients
        for ((t, on) in listOf(stepsTab to !ingredients, ingredientsTab to ingredients)) {
            t.background = Ui.inset(Ui.pressable(Ui.round(if (on) Ui.SURFACE3 else 0, 22f), 22f), 0f, 2f)
            t.setTextColor(if (on) Ui.TEXT else Ui.TEXT2)
            t.isSelected = on
        }
        Ui.show(stepList, !ingredients)
        Ui.show(ingredientCard, ingredients && !state?.ingredients.isNullOrEmpty())
    }

    private lateinit var titlesBox: View

    override fun bind(s: EtapesState, old: EtapesState?) {
        Ui.show(back, !s.root)
        (titlesBox.layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(if (s.root) 8 else 10)
        Ui.set(title, s.recipe)
        Ui.set(subtitle, tr("%d étapes · %d personnes", s.steps.size, s.servings))
        // Lignes réutilisées : seules les lignes en trop ou en moins sont créées ou retirées.
        while (stepRows.size < s.steps.size) stepRows += StepRow(c).also { stepList.addView(it.root) }
        while (stepRows.size > s.steps.size) stepList.removeView(stepRows.removeAt(stepRows.size - 1).root)
        s.steps.forEachIndexed { i, item -> stepRows[i].bind(item, i + 1, last = i == s.steps.size - 1) }
        if (s.ingredients != ingredientsShown) {
            ingredientsShown = s.ingredients
            ingredientCard.removeAllViews()
            s.ingredients.forEachIndexed { i, ing ->
                if (i > 0) ingredientCard.addView(Ui.divider(c))
                val row = Ui.horizontal(c)
                row.minimumHeight = Ui.dp(44)
                row.addView(Ui.text(c, ing.name, 15f, lineHeight = 20f), Ui.lp(0, Ui.WRAP, 1f, t = 8, r = 12, b = 8))
                row.addView(Ui.tabular(Ui.text(c, ing.quantity, 15f, Ui.TEXT2)))
                ingredientCard.addView(row)
            }
        }
        select(showIngredients)
        stopRecipe.bind(s.stopRecipeOffered, s.canStopRecipe, cooking = s.now != null && s.now.end == null)
        val now = s.now
        Ui.show(nowBar, now != null)
        if (now != null) {
            val end = now.end
            Ui.show(nowPause, end == null)
            Ui.show(nowStop, end == null)
            Ui.show(nowOk, end != null)
            if (end != null) {
                nowDot.color = Ui.DANGER
                nowText.maxLines = 2
                val t = SpannableStringBuilder(mixed(now.action to 1, " · " to 0))
                val at = t.length
                t.append(end)
                t.setSpan(ForegroundColorSpan(Ui.DANGER), at, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                nowText.text = t
                Ui.setButtonEnabled(nowOk, now.canOk)
            } else {
                nowDot.color = if (now.paused) Ui.WARN else Ui.ACCENT_INK
                nowText.maxLines = 1
                nowText.text = SpannableStringBuilder(mixed(now.action to 1, " · " to 0)).append(Ui.clock(now.remainingMs))
                nowPause.icon?.set(if (now.paused) Icon.Kind.PLAY else Icon.Kind.PAUSE)
                nowPause.contentDescription = if (now.paused) tr("Reprendre") else tr("Mettre en pause")
            }
        }
    }

    /** Une étape : repère et trait à gauche, texte (ou carte de l'étape en cours) à droite. */
    private class StepRow(val c: Context) {
        val root = Ui.horizontal(c, Gravity.TOP)
        private val marker = StepMarker(c)
        private val line = View(c).apply { setBackgroundColor(Ui.SURFACE3) }
        private val plain = Ui.vertical(c)
        private val title = Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 20f)
        private val detail = Ui.text(c, "", 13f, Ui.TEXT2)
        private val warning = Ui.horizontal(c)
        private val warningText: TextView
        private val card = Ui.card(c, 20f, Ui.SURFACE, Ui.ACCENT_LINE)
        private val cardTitle = Ui.ellipsize(Ui.text(c, "", 17f, Ui.TEXT, Ui.MEDIUM))
        private val cardDetail = Ui.text(c, "", 13f, Ui.TEXT2).apply { gravity = Gravity.END }
        private val bar = ProgressLine(c)
        private val timeRow = Ui.baselineRow(c)
        private val remaining = Ui.tabular(Ui.text(c, "", 15f))
        private val temp = Ui.tabular(Ui.text(c, "", 15f))
        private val body = android.widget.FrameLayout(c)
        private val waitChip = Ui.chip(c, tr("Le robot vous attend"), color = Ui.WARN, bg = Ui.WARN_SOFT, dot = Ui.WARN)
        private val waitLine = Ui.horizontal(c, Gravity.TOP)
        private val waitText = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)

        init {
            val left = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
            left.addView(marker, LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)))
            left.addView(line, Ui.lp(Ui.dp(2), 0, 1f, t = 4, b = 4))
            root.addView(left, LinearLayout.LayoutParams(Ui.dp(22), Ui.MATCH).apply { rightMargin = Ui.dp(12) })

            plain.addView(title)
            plain.addView(detail, Ui.lp(t = 2))
            warning.addView(Icon(c, Icon.Kind.THERMO, Ui.WARN), LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)).apply { rightMargin = Ui.dp(6) })
            warningText = Ui.text(c, "", 13f, Ui.WARN, lineHeight = 18f)
            warning.addView(warningText, Ui.lp(0, Ui.WRAP, 1f))
            plain.addView(warning, Ui.lp(t = 4))

            Ui.pad(card, 16, 14, 16, 14)
            card.addView(waitChip, Ui.lp(Ui.WRAP, Ui.WRAP, b = 10))
            val head = Ui.horizontal(c)
            head.addView(cardTitle, Ui.lp(0, Ui.WRAP, 1f, r = 8))
            head.addView(cardDetail, Ui.lp(Ui.WRAP, Ui.WRAP))
            card.addView(head)
            card.addView(bar, Ui.lp(Ui.MATCH, Ui.dp(6), t = 10))
            timeRow.addView(remaining, Ui.lp(0, Ui.WRAP, 1f))
            timeRow.addView(temp, Ui.lp(Ui.WRAP, Ui.WRAP))
            card.addView(timeRow, Ui.lp(t = 10))
            waitLine.addView(Icon(c, Icon.Kind.SCREEN, Ui.TEXT2), LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)).apply { rightMargin = Ui.dp(8); topMargin = Ui.dp(1) })
            waitLine.addView(waitText, Ui.lp(0, Ui.WRAP, 1f))
            card.addView(waitLine, Ui.lp(t = 10))

            body.addView(plain, Ui.flp(Ui.MATCH, Ui.WRAP))
            body.addView(card, Ui.flp(Ui.MATCH, Ui.WRAP))
            root.addView(body, Ui.lp(0, Ui.WRAP, 1f))
        }

        fun bind(s: EtapeItem, number: Int, last: Boolean) {
            marker.set(s.status, number)
            Ui.show(line, !last)
            body.setPadding(0, 0, 0, if (last) 0 else Ui.dp(16))
            val current = s.status == StepStatus.CURRENT
            Ui.show(plain, !current)
            Ui.show(card, current)
            if (current) {
                val w = s.waiting
                Ui.show(waitChip, w != null)
                Ui.show(waitLine, w != null)
                if (w != null) Ui.set(waitText, w)
                // En attente, le texte entier de l'étape ; pendant la cuisson, une ligne (inchangé).
                cardTitle.maxLines = if (w != null) 8 else 1
                Ui.set(cardTitle, s.title)
                Ui.set(cardDetail, s.detail)
                val p = s.progress
                Ui.show(bar, p != null)
                Ui.show(timeRow, p != null)
                if (p != null) {
                    bar.progress = p.fraction
                    val t = SpannableStringBuilder(Ui.clock(p.remainingMs))
                    t.setSpan(RelativeSizeSpan(22f / 15f), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    t.append(" ").append(tr("restant"))
                    remaining.text = t
                    // Température du bol ; « — » quand la base ne la mesure pas (Smart hors chauffe).
                    Ui.set(temp, if (p.tempC != null) tr("%d °C", p.tempC) else "—")
                    Ui.setColor(temp, Ui.bowlColor(p.tempC))
                }
            } else {
                val done = s.status == StepStatus.DONE
                Ui.set(title, s.title)
                title.typeface = if (done) Ui.REGULAR else Ui.MEDIUM
                Ui.setColor(title, if (done) Ui.TEXT2 else Ui.TEXT)
                Ui.set(detail, s.detail)
                Ui.setColor(detail, if (done) Ui.TEXT_MUTED else Ui.TEXT2)
                Ui.show(warning, s.warning != null)
                if (s.warning != null) Ui.set(warningText, s.warning)
            }
        }
    }
}
