package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.toque.companion.ui.BackBar
import app.toque.companion.ui.ChipRow
import app.toque.companion.ui.ConfirmSheet
import app.toque.companion.ui.EditActions
import app.toque.companion.ui.Icon
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Screen
import app.toque.companion.ui.SegmentTabs
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.ValueStepper
import app.toque.core.i18n.tr

/**
 * Une étape de la recette écrite sur le téléphone (maquette « Une étape ») : son texte, sa pesée et son
 * réglage du robot. Chez un robot qui prend les réglages ([editable]), la rangée « Réglage du robot »
 * ouvre une feuille pour le choisir ([sheet]) ; sinon le réglage est montré tel quel.
 */
data class EtapeState(
    val number: Int,
    val text: String = "",
    /** Grammes à peser, 0 : aucune pesée. */
    val grams: Int = 0,
    /** Résumé du réglage du robot (« 20 min · 100 °C · vitesse 1 »), null sans réglage. */
    val machine: String? = null,
    val review: Boolean = false,
    /** Refus du robot à l'enregistrement, avec son message. */
    val refused: String? = null,
    /** Étape qui vient d'être ajoutée (laissée vide, elle s'en va au retour). */
    val isNew: Boolean = false,
    val maxGrams: Int = 5000,
    /** Le robot prend les réglages tapés sur le téléphone : la rangée s'ouvre sur la feuille. */
    val editable: Boolean = false,
    /** Note sous le réglage (null : celle d'un robot qui ne prend pas les réglages). */
    val settingNote: String? = null,
    /** Réglage tapé ici, à confirmer sur le robot : la note prend la couleur de l'accent. */
    val typed: Boolean = false,
    /** Le refus du robot porte sur le réglage : la rangée est bordée comme le message. */
    val settingRefused: Boolean = false,
    /** Feuille « Réglage du robot » ouverte, ou null. */
    val sheet: ReglageState? = null,
)

/** Un réglage de la feuille : [key] seconds, tempC, speed ou pulses ; [tempC] ≥ 0 colore la valeur comme une température. */
data class ReglageRow(val key: String, val label: String, val value: String, val note: String = "",
                      val minusOk: Boolean = true, val plusOk: Boolean = true, val tempC: Int = -1)

/**
 * Feuille « Réglage du robot » d'une étape : les genres ([kinds] : clé StepProgram, "" pour aucun, et
 * libellé), le choisi ([selected]), son résumé, ses crans ([rows]), sens ([reverse] 0 normal, 1 inverse,
 * -1 caché ; [reverseDimmed] sans mélange) et accessoire ([whisk] 0 lame, 1 fouet, -1 caché), le rappel de
 * l'éditeur du robot ([hint]), la dernière correction automatique ([message]), [confirm] : un réglage
 * nouveau, à confirmer une fois sur le robot ; [canRestore] : « Reprendre le réglage enregistré ».
 */
data class ReglageState(
    val number: Int,
    val kinds: List<Pair<String, String>>,
    val selected: String,
    val summary: String,
    val rows: List<ReglageRow> = emptyList(),
    val reverse: Int = -1,
    val reverseDimmed: Boolean = false,
    val whisk: Int = -1,
    val hint: String = "",
    val message: String = "",
    val confirm: Boolean = false,
    val canRestore: Boolean = false,
)

class EtapeScreen(c: Context, private val actions: EditActions, private val nav: Navigator) : Screen<EtapeState>(c) {
    override val tab = Tab.RECIPES
    private lateinit var bar: BackBar
    private lateinit var text: EditText
    private lateinit var grams: EditText
    private val refused = Ui.text(c, "", 14f, Ui.WARN, lineHeight = 20f)
    private val refusedBox = Ui.horizontal(c, Gravity.TOP)
    private val machineBox = Ui.vertical(c)
    private val machine = Ui.text(c, "", 17f, Ui.ACCENT_INK, Ui.MEDIUM, lineHeight = 22f)
    private val machineNote = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 20f)
    private val chevron = Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2)
    private lateinit var remove: Ui.Button
    private lateinit var removeSheet: ConfirmSheet
    private lateinit var sheet: SettingSheetView
    private var binding = false
    private var shownNumber = -1
    private var boxLook = ""

    override fun build(): View {
        val root = Ui.vertical(c)
        bar = BackBar(c, tr("Retour à la recette")) { done() }
        root.addView(bar)
        val column = Ui.vertical(c)
        Ui.pad(column, 20, 4, 20, 16)

        refusedBox.background = Ui.round(Ui.WARN_SOFT, 16f, Ui.WARN_LINE, 1f)
        Ui.pad(refusedBox, 14, 12, 14, 12)
        refusedBox.addView(Icon(c, Icon.Kind.WARNING, Ui.WARN), LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { rightMargin = Ui.dp(10); topMargin = Ui.dp(1) })
        refusedBox.addView(refused, Ui.lp(0, Ui.WRAP, 1f))
        column.addView(refusedBox, Ui.lp(b = 12))

        column.addView(Ui.overline(c, tr("Ce qu'il faut faire")))
        text = editField(c, tr("Ex. : Ajouter les oignons et mixer."), multiLine = true)
        text.contentDescription = tr("Ce qu'il faut faire")
        onTyped(text, { binding }) { actions.setStepText(it) }
        column.addView(text, Ui.lp(t = 6))

        column.addView(Ui.overline(c, tr("À peser (facultatif)")), Ui.lp(t = 18))
        val row = Ui.horizontal(c)
        grams = editField(c, "0", number = true)
        grams.imeOptions = EditorInfo.IME_ACTION_DONE
        grams.contentDescription = tr("Grammes à peser")
        grams.setOnEditorActionListener { v, _, _ -> hideKeyboard(v); v.clearFocus(); true }
        onTyped(grams, { binding }) { actions.setStepGrams(it.trim().toIntOrNull() ?: 0) }
        row.addView(grams, LinearLayout.LayoutParams(Ui.dp(140), Ui.WRAP))
        row.addView(Ui.text(c, "g", 17f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        column.addView(row, Ui.lp(t = 6))
        column.addView(Ui.text(c, tr("La balance s'affiche à cette étape et sonne une fois le poids atteint."), 14f, Ui.TEXT2, lineHeight = 19f),
            Ui.lp(t = 6))

        // Réglage du robot : la rangée ouvre la feuille chez un robot qui prend les réglages.
        Ui.pad(machineBox, 14, 12, 14, 12)
        val head = Ui.horizontal(c)
        head.addView(Ui.ellipsize(Ui.overline(c, tr("Réglage du robot")), 2), Ui.lp(0, Ui.WRAP, 1f))
        head.addView(chevron, LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { leftMargin = Ui.dp(8) })
        machineBox.addView(head)
        machineBox.addView(machine, Ui.lp(t = 6))
        machineBox.addView(machineNote, Ui.lp(t = 4))
        machineBox.setOnClickListener { hideKeyboard(it); actions.openSetting() }
        column.addView(machineBox, Ui.lp(t = 18))

        remove = Ui.button(c, tr("Supprimer l'étape"), Ui.Style.GHOST, Icon.Kind.TRASH, height = 44, textSize = 15f, iconSize = 18) {
            hideKeyboard(it)
            removeSheet.show()
        }
        Ui.ellipsize(remove.label, 2)
        column.addView(remove, Ui.lp(Ui.WRAP, Ui.WRAP, t = 18))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))

        val bottom = Ui.vertical(c)
        Ui.pad(bottom, 20, 8, 20, 12)
        bottom.addView(Ui.button(c, tr("OK"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 56, textSize = 17f, iconSize = 22) { done() })
        root.addView(bottom, Ui.lp())

        val frame = FrameLayout(c)
        frame.addView(root, Ui.flp())
        removeSheet = ConfirmSheet(c, tr("Supprimer l'étape ?"), "", tr("Supprimer"), cancel = tr("Rester")) { actions.removeStep() }
        frame.addView(removeSheet, Ui.flp())
        sheet = SettingSheetView(c, actions)
        frame.addView(sheet, Ui.flp())
        return frame
    }

    private fun done() {
        hideKeyboard(text)
        actions.closeStep()
    }

    override fun onBack(): Boolean {
        when {
            sheet.showing -> actions.closeSetting()
            removeSheet.showing -> removeSheet.hide()
            else -> done()
        }
        return true
    }

    override fun onHide() {
        removeSheet.hide()
        hideKeyboard(text)
    }

    /** Texte en cours d'écriture : ses couleurs attendent la fermeture de l'écran (voir EditeurScreen). */
    override val keepsWork: Boolean get() = true

    override fun bind(s: EtapeState, old: EtapeState?) {
        Ui.set(bar.title, tr("Étape %d", s.number))
        Ui.show(bar.subtitle, false)
        binding = true
        if (text.text.toString() != s.text) text.setText(s.text)
        val g = if (s.grams > 0) s.grams.toString() else ""
        if ((grams.text.toString().trim().toIntOrNull() ?: 0) != s.grams) grams.setText(g)
        binding = false
        Ui.show(refusedBox, s.refused != null)
        if (s.refused != null) Ui.set(refused, s.refused)

        Ui.show(machineBox, s.editable || s.machine != null)
        Ui.set(machine, s.machine ?: tr("Aucun réglage"))
        Ui.setColor(machine, if (s.machine != null) Ui.ACCENT_INK else Ui.TEXT3)
        val note = s.settingNote ?: if (s.review) tr("Réglage du robot, modifiable sur le robot ; à vérifier là-bas avant le premier lancement")
            else tr("Réglage du robot, modifiable sur le robot")
        Ui.set(machineNote, note)
        Ui.show(machineNote, note.isNotEmpty())
        Ui.setColor(machineNote, if (s.typed) Ui.ACCENT_INK else Ui.TEXT2)
        Ui.show(chevron, s.editable)
        chevron.rotation = 0f
        val look = "${s.editable}|${s.settingRefused}"
        if (look != boxLook) {
            boxLook = look
            val base = Ui.round(Ui.SURFACE, 18f, if (s.settingRefused) Ui.WARN_LINE else 0, if (s.settingRefused) 1f else 0f)
            machineBox.background = if (s.editable) Ui.pressable(base, 18f) else base
            machineBox.isClickable = s.editable
            machineBox.isFocusable = s.editable
        }
        machineBox.contentDescription = if (s.editable) tr("Réglage du robot") + " : " + (s.machine ?: tr("Aucun réglage")) else null

        Ui.set(removeSheet.body, if (s.machine != null) tr("Son texte et son réglage du robot seront retirés de la recette à l'enregistrement.")
            else tr("Son texte sera retiré de la recette à l'enregistrement."))
        Ui.show(remove, !s.isNew)
        sheet.bind(s.sheet)
        if (s.sheet != null && old?.sheet == null) hideKeyboard(text)
        // Nouvelle étape ouverte : le clavier sur son texte.
        if (s.number != shownNumber || (s.isNew && old?.isNew != true)) {
            shownNumber = s.number
            if (s.isNew && s.text.isEmpty() && !takingOver) text.post {
                text.requestFocus()
                (c.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.showSoftInput(text, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}

/**
 * Carte d'une feuille posée en bas de l'écran : en-tête et barre du bas toujours visibles, le milieu
 * défile quand tout ne tient pas (petit téléphone, grande police).
 */
private class SheetCard(c: Context, val head: View, val middle: ScrollView, val foot: View) : LinearLayout(c) {
    init {
        orientation = VERTICAL
        addView(head, Ui.lp())
        addView(middle, Ui.lp())
        addView(foot, Ui.lp())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = MeasureSpec.makeMeasureSpec(w - paddingLeft - paddingRight, MeasureSpec.EXACTLY)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        head.measure(inner, free)
        foot.measure(inner, free)
        val max = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2 else MeasureSpec.getSize(heightMeasureSpec)
        val room = maxOf(0, max - paddingTop - paddingBottom - head.measuredHeight - foot.measuredHeight)
        middle.measure(inner, MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST))
        setMeasuredDimension(w, paddingTop + paddingBottom + head.measuredHeight + middle.measuredHeight + foot.measuredHeight)
    }
}

/** La feuille « Réglage du robot » d'une étape, posée sur l'écran Étape. Toucher le fond la ferme sans rien changer. */
private class SettingSheetView(c: Context, private val actions: EditActions) : FrameLayout(c) {
    private val title = Ui.text(c, tr("Réglage du robot"), 20f, Ui.TEXT, Ui.MEDIUM, lineHeight = 25f)
    // Les genres sur une ligne qui défile (onze sur le Smart) : les crans restent sous les yeux, même sur un petit téléphone.
    private val chips = ChipRow<String>(c) { actions.pickSetting(it) }
    private val summary = Ui.text(c, "", 17f, Ui.ACCENT_INK, Ui.MEDIUM, lineHeight = 22f)
    private val steppers = linkedMapOf(
        "seconds" to ValueStepper(c, tr("Durée"), tr("Moins de temps"), tr("Plus de temps"), Ui.SURFACE2) { actions.stepSetting("seconds", it) },
        "tempC" to ValueStepper(c, tr("Température"), tr("Baisser la température"), tr("Monter la température"), Ui.SURFACE2) { actions.stepSetting("tempC", it) },
        "speed" to ValueStepper(c, tr("Vitesse"), tr("Baisser la vitesse"), tr("Monter la vitesse"), Ui.SURFACE2) { actions.stepSetting("speed", it) },
        "pulses" to ValueStepper(c, tr("Impulsions"), tr("Moins d'impulsions"), tr("Plus d'impulsions"), Ui.SURFACE2) { actions.stepSetting("pulses", it) },
    )
    private val direction = SegmentTabs(c, listOf(tr("Sens normal"), tr("Inverse")), accent = true) { actions.setSettingReverse(it == 1) }
    private val accessory = SegmentTabs(c, listOf(tr("Lame"), tr("Fouet")), accent = true) { actions.setSettingWhisk(it == 1) }
    private val message = Ui.text(c, "", 14f, Ui.WARN, lineHeight = 19f)
    private val hint = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 19f)
    private val confirmBox = Ui.horizontal(c, Gravity.TOP)
    private val restore = Ui.button(c, tr("Reprendre le réglage enregistré"), Ui.Style.GHOST, Icon.Kind.BACK, height = 44, textSize = 15f, iconSize = 18) {
        actions.restoreSetting()
    }
    private val middle: ScrollView

    init {
        setBackgroundColor(Ui.SCRIM)
        isClickable = true
        setOnClickListener { actions.closeSetting() }
        val head = Ui.vertical(c)
        head.addView(Ui.ellipsize(title, 2))
        head.addView(chips, Ui.lp(t = 10))

        val body = Ui.vertical(c)
        body.addView(summary, Ui.lp(t = 10))
        for (st in steppers.values) body.addView(st.root, Ui.lp(t = 10))
        direction.background = Ui.round(Ui.SURFACE2, 20f)
        accessory.background = Ui.round(Ui.SURFACE2, 20f)
        body.addView(direction, Ui.lp(t = 10))
        body.addView(accessory, Ui.lp(t = 10))
        body.addView(message, Ui.lp(t = 10))
        body.addView(hint, Ui.lp(t = 10))
        confirmBox.background = Ui.round(Ui.ACCENT_DIM, 16f)
        Ui.pad(confirmBox, 12, 10, 12, 10)
        confirmBox.addView(Icon(c, Icon.Kind.INFO, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { rightMargin = Ui.dp(10); topMargin = Ui.dp(1) })
        confirmBox.addView(Ui.text(c, tr("Vous confirmerez ce réglage une fois sur le robot, avant son premier lancement."), 14f, Ui.TEXT, lineHeight = 19f),
            Ui.lp(0, Ui.WRAP, 1f))
        body.addView(confirmBox, Ui.lp(t = 12))
        Ui.ellipsize(restore.label, 2)
        body.addView(restore, Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        body.setPadding(0, 0, 0, Ui.dp(8))
        middle = ScrollView(c).apply { isFillViewport = false; addView(body, Ui.flp(Ui.MATCH, Ui.WRAP)) }

        val foot = Ui.horizontal(c)
        foot.addView(Ui.button(c, tr("Annuler"), Ui.Style.SECONDARY, height = 52) { actions.closeSetting() }.also { Ui.ellipsize(it.label, 2) },
            Ui.lp(0, Ui.WRAP, 1f))
        foot.addView(Ui.button(c, tr("Utiliser ce réglage"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 52, iconSize = 20) { actions.applySetting() }
            .also { Ui.ellipsize(it.label, 2) }, Ui.lp(0, Ui.WRAP, 1.4f, l = 10))
        foot.setPadding(0, Ui.dp(12), 0, 0)

        val card = SheetCard(c, head, middle, foot)
        card.background = Ui.round(Ui.SURFACE, 28f, Ui.STROKE, 1f)
        card.isClickable = true
        Ui.pad(card, 20, 18, 20, 14)
        addView(card, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { setMargins(Ui.dp(12), Ui.dp(24), Ui.dp(12), Ui.dp(12)) })
        visibility = View.GONE
    }

    val showing: Boolean get() = visibility == View.VISIBLE

    fun bind(s: ReglageState?) {
        val was = showing
        Ui.show(this, s != null)
        if (s == null) return
        if (!was) middle.scrollTo(0, 0)
        Ui.set(title, tr("Réglage du robot") + " · " + tr("Étape %d", s.number))
        chips.set(s.kinds, s.selected)
        Ui.set(summary, s.summary)
        Ui.setColor(summary, if (s.selected.isEmpty()) Ui.TEXT3 else Ui.ACCENT_INK)
        val rows = s.rows.associateBy { it.key }
        for ((key, st) in steppers) {
            val r = rows[key]
            Ui.show(st.root, r != null)
            if (r != null) {
                st.set(r.value, if (r.tempC > 0) Ui.tempColor(r.tempC) else if (r.tempC == 0) Ui.TEXT2 else Ui.TEXT, r.note)
                st.enable(r.minusOk, r.plusOk)
            }
        }
        Ui.show(direction, s.reverse >= 0)
        if (s.reverse >= 0) {
            direction.select(s.reverse)
            direction.setState(true, dimmed = s.reverseDimmed)
        }
        Ui.show(accessory, s.whisk >= 0)
        if (s.whisk >= 0) accessory.select(s.whisk)
        Ui.show(message, s.message.isNotEmpty())
        Ui.set(message, s.message)
        Ui.show(hint, s.hint.isNotEmpty())
        Ui.set(hint, s.hint)
        Ui.show(confirmBox, s.confirm)
        Ui.show(restore, s.canRestore)
    }

}
