package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.ui.BackBar
import app.toque.companion.ui.ConfirmSheet
import app.toque.companion.ui.EmptyState
import app.toque.companion.ui.FlowRow
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Icon
import app.toque.companion.ui.IngredientCard
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.PictureSource
import app.toque.companion.ui.RecipeActions
import app.toque.companion.ui.Screen
import app.toque.companion.ui.SegmentTabs
import app.toque.companion.ui.StepMarker
import app.toque.companion.ui.StepStatus
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Thumbnail
import app.toque.companion.ui.Ui
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.Portions

/** Envoi de la fiche au robot. */
enum class SendStatus {
    /** « Envoyer au robot » possible. */
    READY,
    /** Demande en cours : bouton « Envoi… » inactif. */
    SENDING,
    /** Envoi impossible pour l'instant (robot occupé, hors ligne…) : bouton inactif, [FicheState.sendNote] dit pourquoi. */
    BLOCKED,
}

/** Fiche d'une recette du catalogue (maquette « Fiche recette : envoyer au robot »). */
data class FicheState(
    val id: String,
    val title: String,
    /** Recette créée sur le robot (« Ma recette ») ; sinon [category] en sous-titre. */
    val personal: Boolean = false,
    val category: String = "",
    val stepCount: Int,
    /** Favori sur le robot : un bouton étoile quand [canFavorite], sinon une simple étiquette. */
    val favorite: Boolean = false,
    val picture: Boolean = false,
    val durationMin: Int? = null,
    val servings: Int? = null,
    /** Difficulté, telle que le robot l'affiche (« Facile »), ou null. */
    val difficulty: String? = null,
    val ingredients: List<IngredientItem> = emptyList(),
    val steps: List<FicheStep> = emptyList(),
    /** Détail en cours de chargement (titre et vignette déjà connus par la carte). */
    val loading: Boolean = false,
    /** Le détail n'a pas pu être chargé ; null sinon. */
    val error: String? = null,
    val send: SendStatus = SendStatus.READY,
    /** Remplace la note au-dessus du bouton (« Une autre recette attend déjà sur le robot. »), ou null. */
    val sendNote: String? = null,
    /** Ce que compte [servings] quand ce ne sont pas des personnes (« pots ») ; vide sinon. */
    val servingUnit: String = "",
    /**
     * Nombre de personnes de la recette elle-même : le réglage − / + s'affiche (de [minServings] à
     * [maxServings]) et [servings] est le nombre choisi sur le robot. Null : pas de réglage (recette
     * sans nombre de personnes, robot plus ancien).
     */
    val baseServings: Int? = null,
    val minServings: Int = 0,
    val maxServings: Int = 0,
    /** Poids estimé des ingrédients, en grammes (avertissement du bol trop rempli). */
    val totalGrams: Int = 0,
    val bowlTooFull: Boolean = false,
    /**
     * Les quantités, pesées et étapes affichées ne sont pas encore celles de [servings] (le téléphone
     * n'a pas pu les calculer) : estompées jusqu'à la réponse du robot.
     */
    val quantitiesPending: Boolean = false,
    /** Recette guidée en cours sur le robot avec cette recette : il refuserait, le réglage est inactif. */
    val servingsLocked: Boolean = false,
    /** Le robot enregistre les favoris choisis ici (state.can.favorite) ; un robot plus ancien, non. */
    val canFavorite: Boolean = false,
    /** Le robot enregistre les recettes écrites ici : « Modifier » (une recette à soi) ou « Ma version » (Lidl). */
    val canWrite: Boolean = false,
) {
    /** Comme sur le robot : quitter le nombre de la recette se confirme d'abord ; y revenir ou changer encore, non. */
    fun servingsNeedConfirm(to: Int): Boolean = baseServings != null && servings == baseServings && to != baseServings
}

/** Une étape de la fiche : texte et réglages (« 3 min · 100 °C · vitesse 1 », « Pesée », « À la main »). */
data class FicheStep(val text: String, val detail: String = "")

/** « Ma recette · 6 étapes » ou « Soupes · 6 étapes ». */
internal fun recipeSubtitle(personal: Boolean, category: String, stepCount: Int): String =
    listOfNotNull(if (personal) tr("Ma recette") else category.takeIf { it.isNotBlank() },
        if (stepCount > 0) tr("%d étapes", stepCount) else null).joinToString(" · ")

class FicheScreen(c: Context, private val actions: RecipeActions, private val pictures: PictureSource, private val nav: Navigator) :
    Screen<FicheState>(c) {
    override val tab = Tab.RECIPES
    private lateinit var bar: BackBar
    private lateinit var scroll: android.widget.ScrollView
    /** Marque « Favori » d'un robot plus ancien : un simple repère, pas un bouton. */
    private val favorite = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
    /** Étoile du robot qui enregistre les favoris choisis ici : un bouton, pleine ou creuse. */
    private lateinit var star: View
    private lateinit var starIcon: Icon
    /** « Ajouter à la liste » et « Partager les ingrédients », sous les ingrédients. */
    private lateinit var listRow: View
    private lateinit var addButton: Ui.Button
    private lateinit var shareButton: Ui.Button
    private val thumb = Thumbnail(c, 24f, 48f)
    private val facts = FlowRow(c, hGap = 8, vGap = 8)
    // Les trois pastilles sont créées une fois et mises à jour (le nombre change à chaque appui).
    private val durationFact = fact(Icon.Kind.CLOCK, " ")
    private val peopleFact = fact(Icon.Kind.PEOPLE, " ")
    private val difficultyFact = fact(Icon.Kind.STAR, " ")
    private var peopleChanged: Boolean? = null
    private lateinit var tabs: SegmentTabs
    private val ingredients = IngredientCard(c)
    private val steps = Ui.card(c)
    private val stepRows = ArrayList<StepRow>()
    private val status = EmptyState(c, Icon.Kind.WARNING, tr("Réessayer")) { actions.retry() }
    private val note = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
    private val noteIcon = Icon(c, Icon.Kind.SCREEN, Ui.TEXT2)
    private lateinit var send: Ui.Button
    private var showSteps = false
    private var shownId: String? = null
    // Nombre de personnes (au-dessus des ingrédients), comme sur la page de la recette du robot.
    private val servingsBox = Ui.vertical(c)
    private lateinit var minus: View
    private lateinit var plus: View
    private val count = Ui.tabular(Ui.text(c, "", 17f, Ui.TEXT, Ui.MEDIUM))
    private val notice = Ui.vertical(c)
    private val noticeText = Ui.text(c, "", 14f, Ui.TEXT, lineHeight = 20f)
    private val noticeBowl = Ui.text(c, "", 14f, Ui.WARN, lineHeight = 20f)
    private lateinit var back: Ui.Button
    private lateinit var confirm: ConfirmSheet
    private var confirmTo = 0
    /** « Modifier » ou « Ma version », sous les pastilles. */
    private lateinit var edit: Ui.Button
    private lateinit var copySheet: ConfirmSheet

    override fun build(): View {
        val root = Ui.vertical(c)
        val column = Ui.vertical(c)
        bar = BackBar(c, tr("Retour au catalogue")) { nav.back() }
        // Petite étiquette sans fond plein ni effet d'appui, comme l'étoile des cartes du catalogue.
        favorite.addView(Icon(c, Icon.Kind.STAR_FILLED, Ui.ACCENT_INK).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(14)).apply { rightMargin = Ui.dp(4) })
        favorite.addView(Ui.text(c, tr("Favori"), 12f, Ui.TEXT2).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        favorite.setPadding(Ui.dp(8), Ui.dp(3), Ui.dp(10), Ui.dp(3))
        favorite.background = Ui.round(0, 12f, Ui.STROKE, 1f)
        favorite.contentDescription = tr("Favori sur le robot")
        favorite.isClickable = false
        favorite.isFocusable = false
        bar.addView(favorite, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.dp(10) })
        star = Ui.iconButton(c, Icon.Kind.STAR, tr("Ajouter aux favoris"), visible = 44, iconSize = 22) {
            val s = state
            if (s != null && s.canFavorite) actions.setFavorite(s.id, !s.favorite)
        }
        starIcon = (star as FrameLayout).getChildAt(0) as Icon
        bar.addView(star, LinearLayout.LayoutParams(Ui.dp(Ui.TOUCH), Ui.dp(Ui.TOUCH)).apply { leftMargin = Ui.dp(6) })
        // Le nom de la recette reste affiché : barre fixée en haut, la fiche défile dessous.
        root.addView(bar)
        column.addView(thumb, Ui.lp(Ui.MATCH, Ui.dp(120), l = 20, t = 6, r = 20))
        facts.addView(durationFact)
        facts.addView(peopleFact)
        facts.addView(difficultyFact)
        column.addView(facts, Ui.lp(l = 20, t = 12, r = 20))
        edit = Ui.button(c, " ", Ui.Style.OUTLINE, Icon.Kind.EDIT, height = 44, textSize = 15f, iconSize = 18) {
            val s = state
            if (s != null && s.canWrite && !s.loading && s.error == null) {
                if (s.personal) actions.editRecipe(s.id) else copySheet.show()
            }
        }
        Ui.ellipsize(edit.label, 2)
        edit.visibility = View.GONE
        column.addView(edit, Ui.lp(Ui.WRAP, Ui.WRAP, l = 20, t = 10, r = 20))
        tabs = SegmentTabs(c, listOf(tr("Ingrédients"), tr("Étapes"))) { select(it == 1) }
        column.addView(tabs, Ui.lp(l = 20, t = 14, r = 20))
        buildServings()
        column.addView(servingsBox, Ui.lp(l = 20, t = 12, r = 20))
        column.addView(ingredients, Ui.lp(l = 20, t = 12, r = 20))
        val listButtons = FlowRow(c, hGap = 8, vGap = 4)
        addButton = Ui.button(c, tr("Ajouter à la liste"), Ui.Style.OUTLINE, Icon.Kind.BASKET, height = 44, textSize = 14f, iconSize = 18) {
            state?.let { actions.addToList(it.id) }
        }
        Ui.ellipsize(addButton.label, 2)
        listButtons.addView(addButton)
        shareButton = Ui.button(c, tr("Partager les ingrédients"), Ui.Style.GHOST, Icon.Kind.SHARE, height = 44, textSize = 14f, iconSize = 18) {
            state?.let { actions.shareIngredients(it.id) }
        }
        Ui.ellipsize(shareButton.label, 2)
        listButtons.addView(shareButton)
        listRow = listButtons
        column.addView(listButtons, Ui.lp(l = 20, t = 8, r = 20))
        Ui.pad(steps, 16, 14, 16, 14)
        column.addView(steps, Ui.lp(l = 20, t = 12, r = 20))
        column.addView(status, Ui.lp(l = 20, t = 12, r = 20))
        column.addView(Ui.spacer(c, h = 12))
        scroll = Ui.scroll(c, column)
        root.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        // Bas fixe : note et bouton d'envoi.
        val bottom = Ui.vertical(c)
        Ui.pad(bottom, 20, 10, 20, 12)
        val noteRow = Ui.horizontal(c, Gravity.TOP)
        noteRow.addView(noteIcon, LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)).apply { rightMargin = Ui.dp(8); topMargin = Ui.dp(1) })
        noteRow.addView(note, Ui.lp(0, Ui.WRAP, 1f))
        bottom.addView(noteRow)
        send = Ui.button(c, tr("Envoyer au robot"), Ui.Style.PRIMARY, Icon.Kind.SEND, height = 56, textSize = 17f, iconSize = 22) {
            val s = state
            if (s != null && s.send == SendStatus.READY && !s.loading) actions.sendToRobot(s.id)
        }
        (send.icon?.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = Ui.dp(10)
        bottom.addView(send, Ui.lp(t = 10))
        root.addView(bottom, Ui.lp())
        select(false)
        val frame = FrameLayout(c)
        frame.addView(root, Ui.flp())
        confirm = ConfirmSheet(c, tr("Changer le nombre de personnes ?"),
            tr("Toque recalcule les quantités et les pesées, mais pas les temps, les vitesses ni les températures, ni les nombres écrits dans les étapes (œufs, cuillerées). Certaines recettes s'adaptent mal : vapeur (gardez au moins 500 g d'eau dans le bol), pâtes à pétrir, pâtisserie, cuissons longues. Surveillez la cuisson et le remplissage du bol."),
            tr("Adapter"), Ui.Style.PRIMARY) { state?.let { actions.setServings(it.id, confirmTo) } }
        frame.addView(confirm, Ui.flp())
        // Comme le bouton « Ma version » de la page de la recette du robot.
        copySheet = ConfirmSheet(c, tr("Ma version"),
            tr("Toque crée une copie de cette recette dans Mes recettes, telle qu'elle est affichée, pour que vous la modifiiez. La recette Lidl reste telle quelle."),
            tr("Créer ma version"), Ui.Style.PRIMARY) { state?.let { actions.myVersion(it.id) } }
        frame.addView(copySheet, Ui.flp())
        return frame
    }

    /**
     * − « 4 pers. » + sur toute la largeur, de la famille des onglets du dessus : conteneur arrondi
     * sur la surface, − et + ronds (44 dp visibles, 48 au toucher) aux deux bouts, le nombre au
     * centre ; puis la notice quand le nombre n'est plus celui de la recette.
     */
    private fun buildServings() {
        val stepper = Ui.horizontal(c)
        stepper.background = Ui.round(Ui.SURFACE, 26f)
        stepper.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2))
        minus = Ui.iconButton(c, Icon.Kind.MINUS, tr("Moins de personnes"), visible = 44, iconSize = 20, style = Ui.Style.SECONDARY) { step(-1) }
        plus = Ui.iconButton(c, Icon.Kind.PLUS, tr("Plus de personnes"), visible = 44, iconSize = 20, style = Ui.Style.SECONDARY) { step(1) }
        count.gravity = Gravity.CENTER
        Ui.ellipsize(count, 2)
        stepper.addView(minus)
        stepper.addView(count, Ui.lp(0, Ui.WRAP, 1f, l = 8, r = 8))
        stepper.addView(plus)
        servingsBox.addView(stepper, Ui.lp())

        notice.background = Ui.round(Ui.ACCENT_DIM, 16f)
        Ui.pad(notice, 14, 10, 14, 6)
        notice.addView(noticeText)
        notice.addView(Ui.text(c, tr("Certaines recettes s'adaptent mal (vapeur, pâtes à pétrir, pâtisserie)."), 14f, Ui.WARN, lineHeight = 20f),
            Ui.lp(t = 6))
        notice.addView(noticeBowl, Ui.lp(t = 6))
        // Libellé posé par bindServings (un libellé vide ne crée pas le texte du bouton).
        back = Ui.button(c, " ", Ui.Style.GHOST, height = 44, textSize = 14f) {
            val s = state
            if (s?.baseServings != null && !s.servingsLocked) actions.setServings(s.id, s.baseServings)
        }
        notice.addView(back, Ui.lp(Ui.WRAP, Ui.WRAP, t = 2))
        servingsBox.addView(notice, Ui.lp(t = 10))
    }

    private fun step(delta: Int) {
        val s = state ?: return
        val base = s.baseServings ?: return
        val n = s.servings ?: base
        val to = n + delta
        if (s.servingsLocked || s.loading || to !in s.minServings..s.maxServings) return
        if (s.servingsNeedConfirm(to)) {
            confirmTo = to
            confirm.show()
        } else actions.setServings(s.id, to)
    }

    private fun people(n: Int, unit: String) = Portions.label(n, unit)

    override fun onBack(): Boolean {
        if (copySheet.showing) {
            copySheet.hide()
            return true
        }
        if (!confirm.showing) return false
        confirm.hide()
        return true
    }

    override fun onHide() {
        confirm.hide()
        copySheet.hide()
    }

    /** Couleurs changées : le nouvel écran garde la recette et l'onglet choisis (sans revenir en haut). */
    override fun carry(old: Screen<*>) {
        val o = old as FicheScreen
        shownId = o.shownId
        showSteps = o.showSteps
    }

    private fun select(stepsTab: Boolean) {
        showSteps = stepsTab
        tabs.select(if (stepsTab) 1 else 0)
        val s = state ?: return
        val ready = !s.loading && s.error == null
        Ui.show(ingredients, ready && !stepsTab && s.ingredients.isNotEmpty())
        Ui.show(listRow, ready && !stepsTab && s.ingredients.isNotEmpty())
        Ui.show(servingsBox, ready && !stepsTab && s.baseServings != null && s.servings != null)
        Ui.show(steps, ready && stepsTab && s.steps.isNotEmpty())
        val empty = ready && (if (stepsTab) s.steps.isEmpty() else s.ingredients.isEmpty())
        Ui.show(status, !ready || empty)
        when {
            s.loading -> status.set(tr("Chargement de la recette…"), "", loading = true)
            s.error != null -> status.set(tr("Recette indisponible"), s.error, withButton = true)
            empty -> status.set(if (stepsTab) tr("Pas d'étapes") else tr("Pas d'ingrédients"), "")
        }
    }

    override fun bind(s: FicheState, old: FicheState?) {
        // Autre recette : revenir à l'onglet Ingrédients et en haut de la fiche.
        if (s.id != shownId) {
            shownId = s.id
            showSteps = false
            scroll.scrollTo(0, 0)
        }
        Ui.set(bar.title, s.title)
        Ui.set(bar.subtitle, recipeSubtitle(s.personal, s.category, s.stepCount))
        // Robot qui enregistre les favoris : l'étoile-bouton ; sinon l'étiquette d'avant, pour un favori.
        Ui.show(favorite, s.favorite && !s.canFavorite)
        Ui.show(star, s.canFavorite)
        starIcon.set(if (s.favorite) Icon.Kind.STAR_FILLED else Icon.Kind.STAR)
        starIcon.color = if (s.favorite) Ui.ACCENT_INK else Ui.TEXT
        star.contentDescription = if (s.favorite) tr("Retirer des favoris") else tr("Ajouter aux favoris")
        thumb.bind(s.id, s.title, s.picture, pictures, 360)

        bindFacts(s)
        Ui.show(edit, s.canWrite && !s.loading && s.error == null)
        Ui.set(edit.label, if (s.personal) tr("Modifier") else tr("Ma version"))

        // Lignes réutilisées : un appui sur − / + ne change que des textes.
        ingredients.set(s.ingredients)
        while (stepRows.size < s.steps.size) stepRows += StepRow(c).also { steps.addView(it.root, Ui.lp(t = if (stepRows.isEmpty()) 0 else 14)) }
        while (stepRows.size > s.steps.size) steps.removeView(stepRows.removeAt(stepRows.size - 1).root)
        s.steps.forEachIndexed { i, step -> stepRows[i].bind(step, i + 1) }
        // Quantités d'un autre nombre, en attendant celles du robot.
        val alpha = if (s.quantitiesPending) PENDING_ALPHA else 1f
        if (ingredients.alpha != alpha) ingredients.alpha = alpha
        if (steps.alpha != alpha) steps.alpha = alpha

        bindServings(s)
        select(showSteps)

        val blocked = s.send == SendStatus.BLOCKED
        Ui.set(note, s.sendNote ?: tr("Elle attendra sur l'écran du robot. Le lancement se fait là-bas."))
        Ui.setColor(note, if (blocked) Ui.WARN else Ui.TEXT2)
        noteIcon.set(if (blocked) Icon.Kind.WARNING else Icon.Kind.SCREEN)
        noteIcon.color = if (blocked) Ui.WARN else Ui.TEXT2
        Ui.set(send.label, if (s.send == SendStatus.SENDING) tr("Envoi…") else tr("Envoyer au robot"))
        Ui.setButtonEnabled(send, s.send == SendStatus.READY && !s.loading && s.error == null)
    }

    private fun bindServings(s: FicheState) {
        val base = s.baseServings ?: return
        val n = s.servings ?: base
        val changed = n != base
        Ui.set(count, people(n, s.servingUnit))
        Ui.setColor(count, if (changed) Ui.ACCENT_INK else Ui.TEXT)
        Ui.setButtonEnabled(minus, !s.servingsLocked && n - 1 >= s.minServings)
        Ui.setButtonEnabled(plus, !s.servingsLocked && n + 1 <= s.maxServings)
        Ui.show(notice, changed)
        if (!changed) return
        Ui.set(noticeText, tr("Quantités et pesées recalculées pour %s (recette : %s). Temps et températures ne changent pas : surveillez la cuisson et goûtez l'assaisonnement.",
            people(n, s.servingUnit), people(base, s.servingUnit)))
        // Poids d'un autre nombre tant que les quantités sont en attente : pas d'avertissement.
        Ui.show(noticeBowl, s.bowlTooFull && !s.quantitiesPending)
        if (s.bowlTooFull) Ui.set(noticeBowl, tr("Environ %s kg d'ingrédients : vérifiez que le bol n'est pas trop rempli (2,5 L de liquide au maximum) ou cuisinez en deux fois.",
            String.format(I18n.locale, "%.1f", s.totalGrams / 1000.0)))
        Ui.set(back.label, tr("Revenir à %s", people(base, s.servingUnit)))
        Ui.setButtonEnabled(back, !s.servingsLocked)
    }

    /** Durée, nombre de personnes (en couleur quand il n'est plus celui de la recette), difficulté. */
    private fun bindFacts(s: FicheState) {
        val duration = s.durationMin?.takeIf { it > 0 }
        Ui.show(durationFact, duration != null)
        if (duration != null) Ui.set(durationFact.label, Fmt.duration(duration * 60))
        val n = s.servings?.takeIf { it > 0 }
        Ui.show(peopleFact, n != null)
        if (n != null) {
            Ui.set(peopleFact.label, people(n, s.servingUnit))
            val changed = s.baseServings != null && n != s.baseServings
            if (peopleChanged != changed) {
                peopleChanged = changed
                val color = if (changed) Ui.ACCENT_INK else Ui.TEXT2
                Ui.setColor(peopleFact.label, color)
                (peopleFact.lead as? Icon)?.color = color
                peopleFact.background = Ui.round(if (changed) Ui.ACCENT_DIM else Ui.SURFACE2, 16f)
            }
        }
        val difficulty = s.difficulty?.takeIf { it.isNotBlank() }
        Ui.show(difficultyFact, difficulty != null)
        if (difficulty != null) Ui.set(difficultyFact.label, difficulty)
        Ui.show(facts, duration != null || n != null || difficulty != null)
    }

    private fun fact(icon: Icon.Kind, label: String): Ui.Chip =
        Ui.chip(c, label, icon, Ui.TEXT2, Ui.SURFACE2, size = 13f, height = 32, iconSize = 16, padH = 12).apply {
            setPadding(Ui.dp(10), paddingTop, Ui.dp(12), paddingBottom)
            (lead?.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = Ui.dp(6)
        }

    companion object {
        /** Opacité des quantités en attente de celles du robot. */
        const val PENDING_ALPHA = 0.4f
    }

    /** Une étape : numéro dans un cercle, texte, réglages en gris. */
    private class StepRow(c: Context) {
        val root = Ui.horizontal(c, Gravity.TOP)
        private val marker = StepMarker(c)
        private val text: TextView = Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 21f)
        private val detail: TextView = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)

        init {
            root.addView(marker, LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)).apply { rightMargin = Ui.dp(12) })
            val col = Ui.vertical(c)
            col.addView(text)
            col.addView(detail, Ui.lp(t = 2))
            root.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        }

        fun bind(s: FicheStep, number: Int) {
            marker.set(StepStatus.TODO, number)
            Ui.set(text, s.text)
            Ui.set(detail, s.detail)
            Ui.show(detail, s.detail.isNotEmpty())
        }
    }
}
