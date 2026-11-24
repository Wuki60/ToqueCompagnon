package app.toque.companion.ui.screens

import android.content.Context
import android.view.View
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
import app.toque.companion.ui.resumeCard
import app.toque.companion.ui.spokenPlayGlyph
import app.toque.companion.ui.stopButton
import app.toque.companion.ui.withPlayGlyph
import app.toque.core.i18n.tr

/** En pause depuis le téléphone : la reprise est possible ici (maquette « reprise depuis le téléphone »). */
data class RepriseState(
    val link: Link = Link.CONNECTED,
    /** Recette suivie, ou null (cuisson manuelle, programme). */
    val recipe: String? = null,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val remainingMs: Long,
    val autoCancelMs: Long? = null,
    /** false pendant qu'une reprise demandée attend la réponse du robot. */
    val canResume: Boolean = true,
    /** Qui a mis en pause, si ce n'est pas ce téléphone : « Pause sur le robot »… ; null : ce téléphone. */
    val pausedBy: String? = null,
    val canStop: Boolean = true,
)

class RepriseScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<RepriseState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private lateinit var header: RecipeHeader
    private val card = PausedCard(c)
    private lateinit var resume: Ui.Button
    private lateinit var stop: Ui.Button

    override fun build(): View {
        page = LivePage(c)
        header = RecipeHeader(c) { nav.open(Route.ETAPES) }
        page.body.addView(header)
        page.body.addView(card, Ui.lp(l = 20, t = 14, r = 20))

        val how = resumeCard(c)
        val template = tr("Appuyez sur %s ci-dessous, ou en bas de l'écran du robot.")
        val line = Ui.text(c, withPlayGlyph(c, template), 15f, Ui.TEXT, lineHeight = 21f)
        line.contentDescription = spokenPlayGlyph(template)
        how.addView(line, Ui.lp(t = 10))
        how.addView(Ui.text(c, tr("Si le couvercle est ouvert entre-temps, la reprise se fait sur le robot."), 13f, Ui.TEXT2, lineHeight = 18f),
            Ui.lp(t = 10))
        page.body.addView(how, Ui.lp(l = 20, t = 12, r = 20))

        resume = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLAY, description = tr("Reprendre")) { actions.resume() }
        stop = stopButton(c) { actions.stop() }
        page.bar(actionBar(c, resume to 2f, stop to 1f))
        return page.root
    }

    override fun bind(s: RepriseState, old: RepriseState?) {
        page.header?.set(s.link)
        Ui.show(header, s.recipe != null)
        if (s.recipe != null) header.set(s.recipe, s.stepIndex, s.stepCount, paused = true)
        card.set(s.remainingMs, tr("Le moteur et la chauffe sont arrêtés. Le temps restant est gardé."), s.autoCancelMs)
        if (s.pausedBy != old?.pausedBy || old == null) card.setChip(Icon.Kind.PAUSE, s.pausedBy ?: tr("Pause depuis ce téléphone"), 16)
        Ui.setButtonEnabled(resume, s.canResume)
        Ui.setButtonEnabled(stop, s.canStop)
    }
}
