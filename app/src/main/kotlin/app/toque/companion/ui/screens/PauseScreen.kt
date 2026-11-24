package app.toque.companion.ui.screens

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import app.toque.companion.ui.Icon
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.LivePage
import app.toque.companion.ui.Link
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.PausedCard
import app.toque.companion.ui.RecipeHeader
import app.toque.companion.ui.Route
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.actionBar
import app.toque.companion.ui.numberedLine
import app.toque.companion.ui.resumeCard
import app.toque.companion.ui.spokenPlayGlyph
import app.toque.companion.ui.stopButton
import app.toque.companion.ui.withPlayGlyph
import app.toque.core.i18n.tr

/**
 * Ce qui a mis le robot en pause, côté robot (la reprise ne peut se faire que sur le robot).
 * [ROBOT] : une autre raison, donnée par le robot ([PauseState.reason]).
 */
enum class PauseCause { LID, BOWL, ROBOT }

/** En pause à cause du couvercle ou du bol (maquette « En pause : couvercle ouvert »). */
data class PauseState(
    val link: Link = Link.CONNECTED,
    /** Recette suivie, ou null (cuisson manuelle, programme) : l'en-tête de recette est alors caché. */
    val recipe: String? = null,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val cause: PauseCause,
    val remainingMs: Long,
    /** Temps avant l'annulation automatique, ou null. */
    val autoCancelMs: Long? = null,
    /** [PauseCause.ROBOT] : libellé de la pause et raison du refus de reprise, du robot. */
    val label: String = "",
    val reason: String = "",
    val canStop: Boolean = true,
)

class PauseScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<PauseState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private lateinit var header: RecipeHeader
    private val card = PausedCard(c)
    private val first = Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 21f)
    private lateinit var stop: Ui.Button

    override fun build(): View {
        page = LivePage(c)
        header = RecipeHeader(c) { nav.open(Route.ETAPES) }
        page.body.addView(header)
        page.body.addView(card, Ui.lp(l = 20, t = 14, r = 20))

        val how = resumeCard(c)
        how.addView(numberedLine(c, 1, first), Ui.lp(t = 10))
        val template = tr("Appuyez sur %s en bas de l'écran du robot.")
        val second = Ui.text(c, withPlayGlyph(c, template), 15f, Ui.TEXT, lineHeight = 21f)
        second.contentDescription = spokenPlayGlyph(template)
        how.addView(numberedLine(c, 2, second), Ui.lp(t = 10))
        page.body.addView(how, Ui.lp(l = 20, t = 12, r = 20))

        // La reprise se fait sur le robot : bouton inactif, en tirets, qui le rappelle.
        val onRobot = Ui.horizontal(c, android.view.Gravity.CENTER)
        onRobot.minimumHeight = Ui.dp(56)
        onRobot.setPadding(Ui.dp(12), 0, Ui.dp(12), 0)
        onRobot.background = Ui.dashed(Ui.SURFACE, 28f, Ui.STROKE)
        onRobot.addView(Icon(c, Icon.Kind.SCREEN, Ui.TEXT_MUTED), LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)).apply { rightMargin = Ui.dp(8) })
        onRobot.addView(Ui.ellipsize(Ui.text(c, tr("Reprise sur le robot"), 15f, Ui.TEXT_MUTED, Ui.MEDIUM), 2))
        onRobot.isEnabled = false
        stop = stopButton(c) { actions.stop() }
        page.bar(actionBar(c, onRobot to 2f, stop to 1f))
        return page.root
    }

    override fun bind(s: PauseState, old: PauseState?) {
        page.header?.set(s.link)
        Ui.show(header, s.recipe != null)
        if (s.recipe != null) header.set(s.recipe, s.stepIndex, s.stepCount, paused = true)
        when (s.cause) {
            PauseCause.LID -> {
                card.setChip(Icon.Kind.LID, tr("Couvercle ouvert"), 18)
                card.set(s.remainingMs, tr("Le robot s'est arrêté dès l'ouverture du couvercle. Rien ne repart tout seul."), s.autoCancelMs)
                Ui.set(first, tr("Fermez et verrouillez le couvercle."))
            }
            PauseCause.BOWL -> {
                card.setChip(Icon.Kind.POT, tr("Bol retiré"), 18)
                card.set(s.remainingMs, tr("Le robot s'est arrêté dès que le bol a été retiré. Rien ne repart tout seul."), s.autoCancelMs)
                Ui.set(first, tr("Remettez le bol et fermez le couvercle."))
            }
            PauseCause.ROBOT -> {
                card.setChip(Icon.Kind.PAUSE, s.label.ifEmpty { tr("En pause") }, 16)
                card.set(s.remainingMs, tr("Le moteur et la chauffe sont arrêtés. Le temps restant est gardé."), s.autoCancelMs)
                Ui.set(first, s.reason.ifEmpty { tr("Vérifiez le robot.") })
            }
        }
        Ui.setButtonEnabled(stop, s.canStop)
    }
}
