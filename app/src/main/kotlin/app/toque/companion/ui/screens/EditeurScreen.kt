package app.toque.companion.ui.screens

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.companion.ui.BackBar
import app.toque.companion.ui.ConfirmSheet
import app.toque.companion.ui.EditActions
import app.toque.companion.ui.EmptyState
import app.toque.companion.ui.FlowRow
import app.toque.companion.ui.Icon
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Screen
import app.toque.companion.ui.StepMarker
import app.toque.companion.ui.StepStatus
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr

/** Ce que l'éditeur montre : la recette, une proposition de brouillon, un chargement, une erreur. */
enum class EditPhase { LOADING, OFFER, EDITING, ERROR }

/** Message du haut de l'éditeur : refus du robot, version dépassée (deux choix), robot injoignable. */
data class EditBanner(val kind: Kind, val title: String, val text: String) {
    enum class Kind { REFUSED, STALE, OFFLINE }
}

/** Une ligne d'ingrédient ; [header] : la première d'un groupe titré (le titre s'affiche au-dessus). */
data class EditLine(val group: String, val text: String, val header: Boolean = false)

/**
 * Une étape : texte, pesée (0 : aucune), résumé du réglage du robot, à vérifier sur le robot, refus du robot ;
 * [settingNote] la note sous le réglage chez un robot qui prend les réglages (null : celle d'avant, vide :
 * aucune), [typed] un réglage tapé ici, à confirmer sur le robot.
 */
data class EditStepRow(val text: String, val grams: Int = 0, val machine: String? = null, val review: Boolean = false, val refused: String? = null,
                       val settingNote: String? = null, val typed: Boolean = false)

/** Éditeur d'une recette perso sur le téléphone (maquette « La recette »). */
data class EditeurState(
    /** « Nouvelle recette », « Modifier la recette », « Ma version ». */
    val heading: String = "",
    val phase: EditPhase = EditPhase.LOADING,
    /** Texte du chargement, de l'erreur, ou de la proposition de brouillon. */
    val message: String? = null,
    val title: String = "",
    val servings: Int = 4,
    val minutes: Int = 0,
    val difficulty: Int = 1,
    val lines: List<EditLine> = emptyList(),
    val steps: List<EditStepRow> = emptyList(),
    val saving: Boolean = false,
    val banner: EditBanner? = null,
    /** Champ refusé par le robot (title, servings, totalMinutes, difficulty, content, ingredients) et son message. */
    val problemField: String? = null,
    val problemText: String? = null,
    /** « Brouillon gardé sur le téléphone », « Enregistrée sur le robot »… */
    val note: String = "",
    /** Ligne d'ingrédient à mettre sous le doigt (une ligne ajoutée), une fois par [focusSeq]. */
    val focusLine: Int = -1,
    val focusSeq: Int = 0,
    val maxServings: Int = 20,
    val maxMinutes: Int = 600,
    /** Des changements que le robot n'a pas encore enregistrés (quitter demande d'abord). */
    val dirty: Boolean = false,
)

/** Champ de saisie du compagnon : 17 sp, fond de carte, 48 dp au moins. */
internal fun editField(c: Context, hint: String, multiLine: Boolean = false, number: Boolean = false): EditText = EditText(c).apply {
    this.hint = hint
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    setTextColor(Ui.TEXT)
    setHintTextColor(Ui.TEXT3)
    typeface = Ui.REGULAR
    background = Ui.round(Ui.SURFACE, 16f, Ui.STROKE, 1f)
    setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12))
    minHeight = Ui.dp(Ui.TOUCH)
    Ui.tintField(this)
    if (multiLine) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 3
        gravity = Gravity.TOP or Gravity.START
    } else {
        setSingleLine()
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
}

/** Rappel à chaque texte tapé, sauf pendant [quiet] (texte posé par le rendu). */
internal fun onTyped(e: EditText, quiet: () -> Boolean, f: (String) -> Unit) = e.addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a1: Int) {}
    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
    override fun afterTextChanged(s: Editable?) {
        if (!quiet()) f(s?.toString() ?: "")
    }
})

/** Guillemets français tenus à leur mot (espaces insécables), comme sur le robot. */
internal fun nbsp(t: String): String = t.replace("« ", "«\u00A0").replace(" »", "\u00A0»")

internal fun hideKeyboard(v: View) {
    (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
}

/** « − valeur + » : boutons ronds de 48 dp, la valeur au centre. */
internal class Stepper(c: Context, minusLabel: String, plusLabel: String, onMinus: () -> Unit, onPlus: () -> Unit) : LinearLayout(c) {
    val minus: View = Ui.iconButton(c, Icon.Kind.MINUS, minusLabel, visible = 44, iconSize = 20, style = Ui.Style.SECONDARY) { onMinus() }
    val plus: View = Ui.iconButton(c, Icon.Kind.PLUS, plusLabel, visible = 44, iconSize = 20, style = Ui.Style.SECONDARY) { onPlus() }
    val value: TextView = Ui.tabular(Ui.text(c, "", 17f, Ui.TEXT, Ui.MEDIUM)).apply { gravity = Gravity.CENTER }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.round(Ui.SURFACE, 26f)
        setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2))
        addView(minus)
        addView(value, LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.dp(2); rightMargin = Ui.dp(2) })
        value.minWidth = Ui.dp(56)
        addView(plus)
    }

    fun set(text: String, minusOk: Boolean, plusOk: Boolean) {
        Ui.set(value, text)
        Ui.setButtonEnabled(minus, minusOk)
        Ui.setButtonEnabled(plus, plusOk)
    }
}

class EditeurScreen(c: Context, private val actions: EditActions, private val nav: Navigator) : Screen<EditeurState>(c) {
    override val tab = Tab.RECIPES
    private lateinit var bar: BackBar
    private lateinit var scroll: ScrollView
    private val bannerBox = Ui.vertical(c)
    private val bannerTitle = Ui.text(c, "", 15f, Ui.TEXT, Ui.MEDIUM, lineHeight = 20f)
    private val bannerText = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 20f)
    private val bannerIcon = Icon(c, Icon.Kind.WARNING, Ui.WARN)
    private val staleRow = FlowRow(c, hGap = 8, vGap = 8)
    private val offerBox = Ui.vertical(c)
    private val offerText = Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 21f)
    private val status = EmptyState(c, Icon.Kind.WARNING, tr("Réessayer")) { actions.retryLoad() }
    private val form = Ui.vertical(c)
    private lateinit var titleField: EditText
    private val titleProblem = problemText()
    private lateinit var people: Stepper
    private lateinit var time: Stepper
    private val metaProblem = problemText()
    private val levels = ArrayList<TextView>()
    private val linesBox = Ui.vertical(c)
    private val lineRows = ArrayList<LineRow>()
    private val noLines = Ui.text(c, tr("Aucun ingrédient pour l'instant."), 15f, Ui.TEXT3)
    private val linesProblem = problemText()
    private val stepsBox = Ui.vertical(c)
    private val stepRows = ArrayList<StepRow>()
    private val noSteps = Ui.text(c, tr("Aucune étape pour l'instant."), 15f, Ui.TEXT3)
    private val note = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f), 2)
    private lateinit var save: Ui.Button
    private lateinit var bottom: LinearLayout
    private lateinit var leaveSheet: ConfirmSheet
    /** Texte posé par le rendu : pas un changement de l'utilisateur. */
    private var binding = false
    private var focusedSeq = 0

    private fun problemText() = Ui.text(c, "", 14f, Ui.WARN, lineHeight = 19f).apply { visibility = View.GONE }

    override fun build(): View {
        val root = Ui.vertical(c)
        bar = BackBar(c, tr("Retour")) { back() }
        root.addView(bar)

        val column = Ui.vertical(c)
        // Message du robot (refus, version dépassée, hors ligne).
        bannerBox.background = Ui.round(Ui.WARN_SOFT, 18f, Ui.WARN_LINE, 1f)
        Ui.pad(bannerBox, 14, 12, 14, 12)
        val head = Ui.horizontal(c, Gravity.TOP)
        head.addView(bannerIcon, LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { rightMargin = Ui.dp(10); topMargin = Ui.dp(1) })
        val texts = Ui.vertical(c)
        texts.addView(bannerTitle)
        texts.addView(bannerText, Ui.lp(t = 4))
        head.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        bannerBox.addView(head)
        staleRow.addView(Ui.button(c, tr("Reprendre la version du robot"), Ui.Style.SECONDARY, height = 44, textSize = 14f) { actions.reloadFromRobot() }
            .also { Ui.ellipsize(it.label, 2) })
        staleRow.addView(Ui.button(c, tr("Garder mon brouillon"), Ui.Style.OUTLINE, height = 44, textSize = 14f) { actions.keepDraft() }
            .also { Ui.ellipsize(it.label, 2) })
        bannerBox.addView(staleRow, Ui.lp(t = 10))
        column.addView(bannerBox, Ui.lp(l = 20, t = 6, r = 20))

        // Brouillon trouvé à l'ouverture.
        offerBox.background = Ui.round(Ui.SURFACE, 20f)
        Ui.pad(offerBox, 16, 16, 16, 16)
        offerBox.addView(Ui.text(c, tr("Un brouillon est gardé sur le téléphone"), 17f, Ui.TEXT, Ui.MEDIUM, lineHeight = 22f))
        offerBox.addView(offerText, Ui.lp(t = 6))
        offerBox.addView(Ui.button(c, tr("Reprendre le brouillon"), Ui.Style.PRIMARY, Icon.Kind.EDIT, height = 52, textSize = 16f) { actions.resumeDraft() }
            .also { Ui.ellipsize(it.label, 2) }, Ui.lp(t = 14))
        offerBox.addView(Ui.button(c, tr("Recommencer"), Ui.Style.OUTLINE, height = 48, textSize = 15f) { actions.discardDraft() }
            .also { Ui.ellipsize(it.label, 2) }, Ui.lp(t = 8))
        column.addView(offerBox, Ui.lp(l = 20, t = 10, r = 20))
        column.addView(status, Ui.lp(l = 20, t = 12, r = 20))

        buildForm()
        column.addView(form, Ui.lp(l = 20, t = 4, r = 20))
        column.addView(Ui.spacer(c, h = 16))
        scroll = Ui.scroll(c, column)
        root.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        bottom = Ui.vertical(c)
        Ui.pad(bottom, 20, 8, 20, 12)
        bottom.addView(note)
        save = Ui.button(c, tr("Enregistrer sur le robot"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 56, textSize = 17f, iconSize = 22) {
            hideKeyboard(it)
            actions.save()
        }
        Ui.ellipsize(save.label, 2)
        bottom.addView(save, Ui.lp(t = 8))
        root.addView(bottom, Ui.lp())

        val frame = FrameLayout(c)
        frame.addView(root, Ui.flp())
        leaveSheet = ConfirmSheet(c, tr("Quitter sans enregistrer ?"),
            tr("Le brouillon reste sur le téléphone : il vous sera proposé en rouvrant la recette."), tr("Quitter"),
            Ui.Style.PRIMARY, cancel = tr("Rester")) { actions.leave() }
        frame.addView(leaveSheet, Ui.flp())
        return frame
    }

    private fun label(t: String) = Ui.overline(c, t)

    private fun buildForm() {
        form.addView(label(tr("Titre")), Ui.lp(t = 12))
        titleField = editField(c, tr("Ex. : Soupe de potiron"))
        titleField.imeOptions = EditorInfo.IME_ACTION_DONE
        titleField.contentDescription = tr("Titre")
        titleField.setOnEditorActionListener { v, _, _ -> hideKeyboard(v); v.clearFocus(); true }
        onTyped(titleField, { binding }) { actions.setTitle(it) }
        form.addView(titleField, Ui.lp(t = 6))
        form.addView(titleProblem, Ui.lp(t = 6))

        // Personnes et durée : côte à côte quand la place le permet.
        val meta = FlowRow(c, hGap = 12, vGap = 12)
        people = Stepper(c, tr("Moins de personnes"), tr("Plus de personnes"), { stepPeople(-1) }, { stepPeople(1) })
        time = Stepper(c, tr("Moins de temps"), tr("Plus de temps"), { stepTime(-1) }, { stepTime(1) })
        meta.addView(block(tr("Personnes"), people))
        meta.addView(block(tr("Durée totale"), time))
        form.addView(meta, Ui.lp(t = 16))
        form.addView(metaProblem, Ui.lp(t = 6))
        val levelRow = FlowRow(c, hGap = 8, vGap = 8)
        for ((level, name) in listOf(1 to tr("Facile"), 2 to tr("Moyen"), 3 to tr("Difficile"))) {
            val chip = Ui.toggleChip(c, name, false) { hideKeyboard(form); actions.setDifficulty(level) }
            levels += chip
            levelRow.addView(chip)
        }
        form.addView(levelRow, Ui.lp(t = 12))

        form.addView(label(tr("Ingrédients")), Ui.lp(t = 20))
        form.addView(Ui.text(c, nbsp(tr("Une ligne chacun, la quantité d'abord : « 500 g de farine ». Les quantités suivent le nombre de personnes.")),
            14f, Ui.TEXT2, lineHeight = 19f), Ui.lp(t = 4))
        form.addView(linesBox, Ui.lp(t = 6))
        form.addView(noLines, Ui.lp(t = 6))
        form.addView(linesProblem, Ui.lp(t = 6))
        form.addView(Ui.button(c, tr("Ajouter un ingrédient"), Ui.Style.GHOST, Icon.Kind.PLUS, height = 44, textSize = 15f, iconSize = 18) {
            actions.addIngredient()
        }.also { Ui.ellipsize(it.label, 2) }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))

        form.addView(label(tr("Étapes")), Ui.lp(t = 20))
        form.addView(stepsBox, Ui.lp(t = 4))
        form.addView(noSteps, Ui.lp(t = 6))
        form.addView(Ui.button(c, tr("Ajouter une étape"), Ui.Style.GHOST, Icon.Kind.PLUS, height = 44, textSize = 15f, iconSize = 18) {
            hideKeyboard(form)
            actions.addStep()
        }.also { Ui.ellipsize(it.label, 2) }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
    }

    private fun block(title: String, v: View): View {
        val b = Ui.vertical(c)
        b.addView(label(title))
        b.addView(v, Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
        return b
    }

    /** Comme le robot : un nombre déjà hors des bornes reste tant qu'on n'y touche pas, puis y revient. */
    private fun stepPeople(delta: Int) {
        val s = state ?: return
        hideKeyboard(form)
        actions.setServings((s.servings + delta).coerceIn(1, s.maxServings))
    }

    /** Pas de 5 min jusqu'à une heure, puis de 15, de 0 à 600 min, comme le robot. */
    private fun stepTime(delta: Int) {
        val s = state ?: return
        hideKeyboard(form)
        val m = s.minutes
        actions.setMinutes(if (delta < 0) (m - (if (m <= 60) 5 else 15)).coerceAtLeast(0) else (m + (if (m < 60) 5 else 15)).coerceAtMost(s.maxMinutes))
    }

    private fun back() {
        val s = state
        hideKeyboard(form)
        if (s != null && s.dirty && s.phase == EditPhase.EDITING) leaveSheet.show() else actions.leave()
    }

    override fun onBack(): Boolean {
        if (leaveSheet.showing) {
            leaveSheet.hide()
            return true
        }
        back()
        return true
    }

    override fun onHide() {
        leaveSheet.hide()
        hideKeyboard(form)
    }

    /** Texte en cours d'écriture : refaire l'écran le perdrait (curseur, clavier) ; ses couleurs attendent sa fermeture. */
    override val keepsWork: Boolean get() = true

    /** Refait une fois fermé : la ligne déjà mise sous le doigt ne l'est pas une seconde fois. */
    override fun carry(old: Screen<*>) {
        focusedSeq = (old as EditeurScreen).focusedSeq
    }

    override fun bind(s: EditeurState, old: EditeurState?) {
        Ui.set(bar.title, s.heading)
        Ui.set(bar.subtitle, s.title.trim())
        Ui.show(bar.subtitle, s.title.isNotBlank())

        val b = s.banner
        Ui.show(bannerBox, b != null && s.phase == EditPhase.EDITING)
        if (b != null) {
            Ui.set(bannerTitle, b.title)
            Ui.set(bannerText, b.text)
            bannerIcon.set(if (b.kind == EditBanner.Kind.OFFLINE) Icon.Kind.INFO else Icon.Kind.WARNING)
            Ui.show(staleRow, b.kind == EditBanner.Kind.STALE)
        }
        Ui.show(offerBox, s.phase == EditPhase.OFFER)
        if (s.phase == EditPhase.OFFER) Ui.set(offerText, s.message.orEmpty())
        Ui.show(status, s.phase == EditPhase.LOADING || s.phase == EditPhase.ERROR)
        if (s.phase == EditPhase.LOADING) status.set(s.message ?: tr("Chargement…"), "", loading = true)
        if (s.phase == EditPhase.ERROR) status.set(tr("Recette indisponible"), s.message.orEmpty(), withButton = true)
        val editing = s.phase == EditPhase.EDITING
        Ui.show(form, editing)
        Ui.show(bottom, editing)
        if (!editing) return

        binding = true
        if (titleField.text.toString() != s.title) titleField.setText(s.title)
        binding = false
        problem(titleProblem, titleField, s, "title")
        people.set(if (s.servings > 0) s.servings.toString() else "—", s.servings > 1, s.servings < s.maxServings)
        time.set(tr("%d min", s.minutes), s.minutes > 0, s.minutes < s.maxMinutes)
        val metaField = s.problemField in setOf("servings", "totalMinutes", "difficulty")
        Ui.show(metaProblem, metaField)
        if (metaField) Ui.set(metaProblem, s.problemText.orEmpty())
        levels.forEachIndexed { i, chip -> if (chip.isSelected != (s.difficulty == i + 1)) Ui.setToggle(chip, s.difficulty == i + 1) }

        bindLines(s)
        val linesField = s.problemField == "ingredients" || s.problemField == "content"
        Ui.show(linesProblem, linesField)
        if (linesField) Ui.set(linesProblem, s.problemText.orEmpty())
        bindSteps(s)

        Ui.set(note, s.note)
        Ui.show(note, s.note.isNotEmpty())
        Ui.set(save.label, if (s.saving) tr("Enregistrement…") else tr("Enregistrer sur le robot"))
        Ui.setButtonEnabled(save, !s.saving)

        // Champ refusé : il vient sous les yeux.
        if (s.problemField != null && s.problemField != old?.problemField) {
            val target: View = when (s.problemField) {
                "title" -> titleField
                "ingredients", "content" -> linesProblem
                else -> metaProblem
            }
            scroll.post { scroll.smoothScrollTo(0, maxOf(0, target.top - Ui.dp(40))) }
        }
        if (s.banner != null && s.banner != old?.banner && s.problemField == null) scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun problem(t: TextView, field: EditText, s: EditeurState, name: String) {
        val on = s.problemField == name
        Ui.show(t, on)
        if (on) Ui.set(t, s.problemText.orEmpty())
        field.background = Ui.round(Ui.SURFACE, 16f, if (on) Ui.WARN else Ui.STROKE, 1f)
    }

    private fun bindLines(s: EditeurState) {
        while (lineRows.size < s.lines.size) lineRows += LineRow(lineRows.size).also { linesBox.addView(it.root, Ui.lp(t = 6)) }
        while (lineRows.size > s.lines.size) linesBox.removeView(lineRows.removeAt(lineRows.size - 1).root)
        binding = true
        s.lines.forEachIndexed { i, l -> lineRows[i].bind(l) }
        binding = false
        Ui.show(noLines, s.lines.isEmpty())
        if (s.focusSeq != focusedSeq && !takingOver) {
            focusedSeq = s.focusSeq
            lineRows.getOrNull(s.focusLine)?.field?.let { f ->
                f.requestFocus()
                f.post { (c.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(f, InputMethodManager.SHOW_IMPLICIT) }
            }
        }
    }

    private fun bindSteps(s: EditeurState) {
        while (stepRows.size < s.steps.size) stepRows += StepRow(stepRows.size).also { stepsBox.addView(it.root, Ui.lp(t = 12)) }
        while (stepRows.size > s.steps.size) stepsBox.removeView(stepRows.removeAt(stepRows.size - 1).root)
        s.steps.forEachIndexed { i, st -> stepRows[i].bind(st, i == 0, i == s.steps.size - 1) }
        Ui.show(noSteps, s.steps.isEmpty())
    }

    /** Une ligne d'ingrédient : le titre de son groupe s'il commence, le champ, la croix. */
    private inner class LineRow(val index: Int) {
        val root = Ui.vertical(c)
        val group = Ui.ellipsize(Ui.text(c, "", 14f, Ui.ACCENT_INK, Ui.MEDIUM), 2)
        val field = editField(c, tr("Ex. : 500 g de farine"))

        init {
            root.addView(group, Ui.lp(t = 8, b = 4))
            val row = Ui.horizontal(c)
            field.imeOptions = EditorInfo.IME_ACTION_NEXT
            field.contentDescription = tr("Ingrédient %d", index + 1)
            // « Suivant » : la ligne suivante, ou une nouvelle ligne après la dernière.
            field.setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_NEXT) return@setOnEditorActionListener false
                val next = lineRows.getOrNull(index + 1)
                if (next != null && next.root.visibility == View.VISIBLE) next.field.requestFocus() else actions.addIngredient(index)
                true
            }
            onTyped(field, { binding }) { actions.setIngredient(index, it) }
            row.addView(field, Ui.lp(0, Ui.WRAP, 1f))
            row.addView(Ui.iconButton(c, Icon.Kind.CLOSE, tr("Retirer"), visible = 44, iconSize = 18, style = Ui.Style.OUTLINE) {
                actions.removeIngredient(index)
            }, LinearLayout.LayoutParams(Ui.dp(Ui.TOUCH), Ui.dp(Ui.TOUCH)).apply { leftMargin = Ui.dp(6) })
            root.addView(row)
        }

        fun bind(l: EditLine) {
            Ui.show(group, l.header)
            Ui.set(group, l.group)
            if (field.text.toString() != l.text) field.setText(l.text)
        }
    }

    /** Une étape : numéro, texte, pesée, réglage du robot et sa note, refus ; flèches pour la déplacer. */
    private inner class StepRow(val index: Int) {
        val root = Ui.horizontal(c, Gravity.TOP)
        private val marker = StepMarker(c)
        private val text = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 21f), 3)
        private val weigh = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
        private val machine = Ui.text(c, "", 13f, Ui.ACCENT_INK, Ui.MEDIUM, lineHeight = 18f)
        private val machineNote = Ui.text(c, "", 12f, Ui.TEXT2, lineHeight = 17f)
        private val refused = Ui.text(c, "", 13f, Ui.WARN, lineHeight = 18f)
        private val up = Ui.iconButton(c, Icon.Kind.CHEVRON, tr("Monter"), visible = 44, iconSize = 20) { actions.moveStep(index, -1) }
        private val down = Ui.iconButton(c, Icon.Kind.CHEVRON, tr("Descendre"), visible = 44, iconSize = 20) { actions.moveStep(index, 1) }

        private val col = Ui.vertical(c)
        private var refusedShown = false

        init {
            root.addView(marker, LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)).apply { rightMargin = Ui.dp(10); topMargin = Ui.dp(12) })
            Ui.pad(col, 8, 8, 8, 8)
            col.addView(text)
            col.addView(weigh, Ui.lp(t = 4))
            col.addView(machine, Ui.lp(t = 4))
            col.addView(machineNote, Ui.lp(t = 2))
            col.addView(refused, Ui.lp(t = 4))
            col.background = Ui.pressable(Ui.round(Ui.SURFACE, 14f), 14f)
            col.isClickable = true
            col.isFocusable = true
            col.setOnClickListener { hideKeyboard(it); actions.openStep(index) }
            root.addView(col, Ui.lp(0, Ui.WRAP, 1f))
            (up.getChildAt(0)).rotation = -90f
            (down.getChildAt(0)).rotation = 90f
            root.addView(up, LinearLayout.LayoutParams(Ui.dp(Ui.TOUCH), Ui.dp(Ui.TOUCH)).apply { leftMargin = Ui.dp(4) })
            root.addView(down, LinearLayout.LayoutParams(Ui.dp(Ui.TOUCH), Ui.dp(Ui.TOUCH)))
        }

        fun bind(s: EditStepRow, first: Boolean, last: Boolean) {
            marker.set(StepStatus.TODO, index + 1)
            Ui.set(text, s.text.ifBlank { "—" })
            Ui.setColor(text, if (s.text.isBlank()) Ui.TEXT3 else Ui.TEXT)
            Ui.show(weigh, s.grams > 0)
            if (s.grams > 0) Ui.set(weigh, tr("Peser %d g", s.grams))
            Ui.show(machine, s.machine != null)
            if (s.machine != null) Ui.set(machine, s.machine)
            val note = s.settingNote ?: if (s.machine == null) "" else if (s.review) tr("Réglage du robot, modifiable sur le robot ; à vérifier là-bas avant le premier lancement")
                else tr("Réglage du robot, modifiable sur le robot")
            Ui.show(machineNote, note.isNotEmpty())
            Ui.set(machineNote, note)
            Ui.setColor(machineNote, if (s.typed) Ui.ACCENT_INK else Ui.TEXT2)
            Ui.show(refused, s.refused != null)
            if (s.refused != null) Ui.set(refused, s.refused)
            // Étape refusée par le robot : bordée comme le message.
            if (refusedShown != (s.refused != null)) {
                refusedShown = s.refused != null
                col.background = Ui.pressable(Ui.round(Ui.SURFACE, 14f, if (refusedShown) Ui.WARN_LINE else 0, if (refusedShown) 1f else 0f), 14f)
            }
            Ui.setButtonEnabled(up, !first)
            Ui.setButtonEnabled(down, !last)
        }
    }
}
