package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.toque.companion.ui.BackBar
import app.toque.companion.ui.EmptyState
import app.toque.companion.ui.Icon
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.OwnStepActions
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr

/**
 * Le réglage du propriétaire d'une étape d'une recette reçue (Mealie), choisi sur le téléphone sans copier la
 * recette, comme « Mon réglage » sur la page de la recette du robot : le texte de l'étape se lit, seul son
 * réglage du robot se choisit (même feuille que l'écran Étape). Le robot juge et le fera vérifier sur place.
 */
data class MonReglageState(
    /** Numéro de l'étape (à partir de 1). */
    val number: Int,
    /** Titre de la recette, sous celui de l'écran. */
    val recipe: String = "",
    val text: String = "",
    /** Résumé du réglage qui partira (celui du propriétaire s'il y en a un), null : aucun. */
    val machine: String? = null,
    /** Auteur du réglage du propriétaire : « robot », « phone », vide sans réglage à soi. */
    val own: String = "",
    /** À vérifier sur le robot avant son lancement. */
    val review: Boolean = false,
    /** Résumé du réglage de la recette elle-même (ce que « Revenir au réglage de la recette » remet), null : aucun. */
    val recipeSetting: String? = null,
    /** Lecture de l'étape sur le robot en cours. */
    val loading: Boolean = false,
    /** L'étape n'a pas pu être lue ; null sinon. */
    val error: String? = null,
    /** Demande en route vers le robot : boutons inactifs. */
    val sending: Boolean = false,
    /** Refus du robot (ou recette changée sur le robot), avec son message ; null sinon. */
    val refused: String? = null,
    /** Le refus porte sur le réglage : la rangée est bordée comme le message. */
    val settingRefused: Boolean = false,
    /** Le robot ne répond pas : rien ne peut partir. */
    val offline: Boolean = false,
    /** Feuille « Réglage du robot » ouverte, ou null. */
    val sheet: ReglageState? = null,
)

class MonReglageScreen(c: Context, private val actions: OwnStepActions, private val nav: Navigator) : Screen<MonReglageState>(c) {
    override val tab = Tab.RECIPES
    private lateinit var bar: BackBar
    private val body = Ui.vertical(c)
    private val status = EmptyState(c, Icon.Kind.WARNING, tr("Réessayer")) { actions.retryOwnStep() }
    private val refused = Ui.text(c, "", 14f, Ui.WARN, lineHeight = 20f)
    private val refusedBox = Ui.horizontal(c, Gravity.TOP)
    private val text = Ui.text(c, "", 17f, Ui.TEXT, lineHeight = 24f)
    private val machineBox = Ui.vertical(c)
    private val machine = Ui.text(c, "", 17f, Ui.ACCENT_INK, Ui.MEDIUM, lineHeight = 22f)
    private val machineNote = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 20f)
    private val chevron = Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2)
    private val recipeLine = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 20f)
    private lateinit var reset: Ui.Button
    private lateinit var sheet: SettingSheetView
    private var boxLook = ""

    override fun build(): View {
        val root = Ui.vertical(c)
        bar = BackBar(c, tr("Retour à la recette")) { actions.closeOwnStep() }
        root.addView(bar)
        val column = Ui.vertical(c)
        Ui.pad(column, 20, 4, 20, 16)

        refusedBox.background = Ui.round(Ui.WARN_SOFT, 16f, Ui.WARN_LINE, 1f)
        Ui.pad(refusedBox, 14, 12, 14, 12)
        refusedBox.addView(Icon(c, Icon.Kind.WARNING, Ui.WARN), LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { rightMargin = Ui.dp(10); topMargin = Ui.dp(1) })
        refusedBox.addView(refused, Ui.lp(0, Ui.WRAP, 1f))
        body.addView(refusedBox, Ui.lp(b = 12))

        // Le texte vient de la recette : il se lit, il ne se change pas ici.
        body.addView(Ui.overline(c, tr("Ce qu'il faut faire")))
        body.addView(text, Ui.lp(t = 6))

        // Réglage du robot : la rangée ouvre la feuille.
        Ui.pad(machineBox, 14, 12, 14, 12)
        val head = Ui.horizontal(c)
        head.addView(Ui.ellipsize(Ui.overline(c, tr("Réglage du robot")), 2), Ui.lp(0, Ui.WRAP, 1f))
        head.addView(chevron, LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { leftMargin = Ui.dp(8) })
        machineBox.addView(head)
        machineBox.addView(machine, Ui.lp(t = 6))
        machineBox.addView(machineNote, Ui.lp(t = 4))
        machineBox.setOnClickListener { actions.openSetting() }
        body.addView(machineBox, Ui.lp(t = 18))
        body.addView(recipeLine, Ui.lp(t = 10))

        reset = Ui.button(c, tr("Revenir au réglage de la recette"), Ui.Style.GHOST, Icon.Kind.BACK, height = 44, textSize = 15f, iconSize = 18) {
            actions.resetOwnStep()
        }
        Ui.ellipsize(reset.label, 2)
        body.addView(reset, Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        body.addView(Ui.text(c, tr("Votre réglage reste sur le robot, à côté de la recette : rien n'est copié. Si le texte de cette étape change dans Mealie, il ne s'applique plus."),
            14f, Ui.TEXT2, lineHeight = 19f), Ui.lp(t = 18))

        column.addView(body)
        column.addView(status, Ui.lp(t = 12))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))

        val frame = FrameLayout(c)
        frame.addView(root, Ui.flp())
        sheet = SettingSheetView(c, actions)
        frame.addView(sheet, Ui.flp())
        return frame
    }

    override fun onBack(): Boolean {
        if (sheet.showing) actions.closeSetting() else actions.closeOwnStep()
        return true
    }

    override fun bind(s: MonReglageState, old: MonReglageState?) {
        Ui.set(bar.title, tr("Étape %d", s.number))
        Ui.set(bar.subtitle, s.recipe)
        Ui.show(bar.subtitle, s.recipe.isNotEmpty())
        val ready = !s.loading && s.error == null
        Ui.show(body, ready)
        Ui.show(status, !ready)
        when {
            s.loading -> status.set(tr("Lecture de l'étape sur le robot…"), "", loading = true)
            s.error != null -> status.set(tr("Étape indisponible"), s.error, withButton = true)
        }
        Ui.show(refusedBox, s.refused != null)
        if (s.refused != null) Ui.set(refused, s.refused)
        Ui.set(text, s.text.ifBlank { "—" })

        Ui.set(machine, s.machine ?: tr("Aucun réglage"))
        Ui.setColor(machine, if (s.machine != null) Ui.ACCENT_INK else Ui.TEXT3)
        val note = when {
            s.sending -> tr("Envoi au robot…")
            s.own == OWN_PHONE && s.machine != null -> tr("Votre réglage, envoyé du téléphone : à vérifier une fois sur le robot avant son lancement")
            s.own.isNotEmpty() -> tr("Votre réglage, gardé sur le robot")
            s.machine == null -> tr("Étape sans le robot : couper, verser, réserver…")
            s.review -> tr("Réglage lu dans la recette : à vérifier sur le robot avant le premier lancement")
            else -> tr("Réglage de la recette")
        }
        Ui.set(machineNote, note)
        Ui.setColor(machineNote, if (s.own == OWN_PHONE && !s.sending) Ui.ACCENT_INK else Ui.TEXT2)
        val editable = !s.sending && !s.offline
        val look = "$editable|${s.settingRefused}"
        if (look != boxLook) {
            boxLook = look
            val base = Ui.round(Ui.SURFACE, 18f, if (s.settingRefused) Ui.WARN_LINE else 0, if (s.settingRefused) 1f else 0f)
            machineBox.background = if (editable) Ui.pressable(base, 18f) else base
            machineBox.isClickable = editable
            machineBox.isFocusable = editable
        }
        Ui.show(chevron, editable)
        machineBox.contentDescription = tr("Réglage du robot") + " : " + (s.machine ?: tr("Aucun réglage"))

        Ui.show(recipeLine, s.own.isNotEmpty())
        if (s.own.isNotEmpty()) Ui.set(recipeLine, tr("Réglage de la recette : %s", s.recipeSetting ?: tr("Aucun réglage")))
        Ui.show(reset, s.own.isNotEmpty())
        Ui.setButtonEnabled(reset, editable)
        sheet.bind(s.sheet)
    }

    companion object {
        /** [MonReglageState.own] d'un réglage venu d'un téléphone (StepWire.own). */
        const val OWN_PHONE = "phone"
    }
}
