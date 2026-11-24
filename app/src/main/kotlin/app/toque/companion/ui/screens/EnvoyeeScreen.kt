package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.ui.BackBar
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Icon
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.PictureSource
import app.toque.companion.ui.Screen
import app.toque.companion.ui.StepMarker
import app.toque.companion.ui.StepStatus
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Thumbnail
import app.toque.companion.ui.Ui
import app.toque.companion.ui.actionBar
import app.toque.core.i18n.tr

/** Recette envoyée, qui attend sur le robot (maquette « Recette envoyée, en attente »). */
data class EnvoyeeState(
    val id: String,
    val title: String,
    val personal: Boolean = false,
    val category: String = "",
    val stepCount: Int,
    val picture: Boolean = false,
    val durationMin: Int? = null,
    val servings: Int? = null,
    /** Heure d'envoi : « 12:02 ». */
    val sentAt: String,
    /** Heure où le robot l'a reçue ; null tant qu'il ne l'a pas confirmée. */
    val receivedAt: String?,
    /** « Retirer du robot » en cours : boutons inactifs. */
    val withdrawing: Boolean = false,
    /** Envoyée par un autre téléphone (son nom), ou null : ce téléphone. */
    val sentBy: String? = null,
    /** Retirée sur le robot (ou depuis un autre téléphone) : elle n'y attend plus. */
    val removed: Boolean = false,
)

class EnvoyeeScreen(c: Context, private val actions: LiveActions, private val pictures: PictureSource, private val nav: Navigator) :
    Screen<EnvoyeeState>(c) {
    override val tab = Tab.RECIPES
    private lateinit var bar: BackBar
    private val sent = TimelineRow(c)
    private val received = TimelineRow(c)
    private val launch = TimelineRow(c)
    private val thumb = Thumbnail(c, 16f, 22f)
    private val name = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT, Ui.MEDIUM), 2)
    private val summary = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2), 2)
    private lateinit var withdraw: Ui.Button
    private lateinit var live: Ui.Button

    override fun build(): View {
        val root = Ui.vertical(c)
        val column = Ui.vertical(c)
        bar = BackBar(c, tr("Retour au catalogue")) { nav.back() }
        // Barre fixée en haut ; le reste défile dessous.
        root.addView(bar)

        val card = Ui.card(c, 28f, Ui.SURFACE, Ui.ACCENT_LINE)
        Ui.pad(card, 18, 18, 18, 18)
        waitingChip = Ui.chip(c, tr("En attente sur le robot"), Icon.Kind.SCREEN, Ui.TEXT, Ui.ACCENT_SOFT, iconSize = 16)
        card.addView(waitingChip, Ui.lp(Ui.WRAP, Ui.WRAP))
        waitingText = Ui.text(c, tr("La recette est affichée sur l'accueil du robot. Rien ne démarre tant que personne ne la lance là-bas."),
            15f, lineHeight = 21f)
        card.addView(waitingText, Ui.lp(t = 14))
        card.addView(Ui.divider(c), Ui.lp(Ui.MATCH, maxOf(1, Ui.dp(1)), t = 14))
        card.addView(sent.root, Ui.lp(t = 14))
        card.addView(received.root, Ui.lp(t = 12))
        card.addView(launch.root, Ui.lp(t = 12))
        column.addView(card, Ui.lp(l = 20, t = 8, r = 20))

        val recipe = Ui.horizontal(c)
        recipe.background = Ui.round(Ui.SURFACE, 20f)
        Ui.pad(recipe, 12, 12, 12, 12)
        recipe.addView(thumb, LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)).apply { rightMargin = Ui.dp(12) })
        val col = Ui.vertical(c)
        col.addView(name)
        col.addView(summary, Ui.lp(t = 2))
        recipe.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        column.addView(recipe, Ui.lp(l = 20, t = 12, r = 20))

        column.addView(Ui.text(c, tr("Dès qu'elle est lancée, vous la suivez dans En direct."), 13f, Ui.TEXT2, lineHeight = 18f),
            Ui.lp(l = 20, t = 14, r = 20))
        column.addView(Ui.spacer(c, h = 12))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))

        withdraw = Ui.button(c, tr("Retirer du robot"), Ui.Style.GHOST, textSize = 16f) { if (state?.withdrawing == false) actions.withdrawSentRecipe() }
        live = Ui.button(c, tr("Voir En direct"), Ui.Style.SECONDARY, textSize = 16f) { nav.select(Tab.LIVE) }
        root.addView(actionBar(c, withdraw to 1f, live to 1f))
        return root
    }

    private lateinit var waitingChip: View
    private lateinit var waitingText: View

    override fun bind(s: EnvoyeeState, old: EnvoyeeState?) {
        // Retirée : elle n'attend plus, la pastille « En attente » tomberait faux.
        Ui.show(waitingChip, !s.removed)
        Ui.show(waitingText, !s.removed)
        Ui.set(bar.title, s.title)
        Ui.set(bar.subtitle, recipeSubtitle(s.personal, s.category, s.stepCount))
        sent.bind(StepStatus.DONE, 1, s.sentBy?.let { tr("Envoyée depuis %s", it) } ?: tr("Envoyée depuis ce téléphone"), s.sentAt)
        val got = s.receivedAt != null
        received.bind(if (got) StepStatus.DONE else StepStatus.CURRENT, 2,
            if (got) tr("Reçue par le robot") else tr("En route vers le robot…"), s.receivedAt.orEmpty())
        if (s.removed) launch.bind(StepStatus.TODO, 3, tr("Retirée du robot"), "", tr("Elle n'attend plus sur le robot."))
        else launch.bind(if (got) StepStatus.CURRENT else StepStatus.TODO, 3, tr("À lancer sur le robot"), "",
            tr("Touchez la recette sur l'accueil, puis Cuisiner."))
        thumb.bind(s.id, s.title, s.picture, pictures, 56)
        Ui.set(name, s.title)
        Ui.set(summary, listOfNotNull(
            s.durationMin?.takeIf { it > 0 }?.let { Fmt.duration(it * 60) },
            s.servings?.takeIf { it > 0 }?.let { tr("%d pers.", it) },
            if (s.stepCount > 0) tr("%d étapes", s.stepCount) else null,
        ).joinToString(" · "))
        Ui.setButtonEnabled(withdraw, !s.withdrawing && !s.removed)
        Ui.set(withdraw.label, if (s.withdrawing) tr("Retrait…") else tr("Retirer du robot"))
    }

    /** Une ligne de la frise : repère, texte (en gras pour l'étape en cours, avec sa consigne), heure. */
    private class TimelineRow(c: Context) {
        val root = Ui.horizontal(c, Gravity.TOP)
        private val marker = StepMarker(c)
        private val title: TextView = Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 22f)
        private val detail: TextView = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
        private val time: TextView = Ui.tabular(Ui.text(c, "", 13f, Ui.TEXT2))

        init {
            root.addView(marker, LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)).apply { rightMargin = Ui.dp(12) })
            val col = Ui.vertical(c)
            col.addView(title)
            col.addView(detail, Ui.lp(t = 2))
            root.addView(col, Ui.lp(0, Ui.WRAP, 1f, r = 8))
            root.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        }

        fun bind(status: StepStatus, number: Int, text: String, at: String, hint: String = "") {
            marker.set(status, number)
            Ui.set(title, text)
            title.typeface = if (hint.isNotEmpty()) Ui.MEDIUM else Ui.REGULAR
            Ui.setColor(title, if (status == StepStatus.TODO) Ui.TEXT2 else Ui.TEXT)
            Ui.set(detail, hint)
            Ui.show(detail, hint.isNotEmpty())
            Ui.set(time, at)
            Ui.show(time, at.isNotEmpty())
        }
    }
}
