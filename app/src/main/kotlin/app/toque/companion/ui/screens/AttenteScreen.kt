package app.toque.companion.ui.screens

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import app.toque.companion.ui.Dot
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Icon
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.LivePage
import app.toque.companion.ui.Link
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.RobotTiles
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr

/** Robot au repos avec une recette envoyée qui attend d'être lancée (maquette « recette en attente »). */
data class AttenteState(
    val link: Link = Link.CONNECTED,
    val recipe: String,
    /** Heure d'envoi : « 12:02 ». */
    val sentAt: String,
    val stepCount: Int,
    /** Durée totale de la recette, en secondes. */
    val durationS: Int,
    val bowlC: Int,
    val scaleG: Int,
    val lidClosed: Boolean,
    val bowlInPlace: Boolean,
    /** Nombre de personnes choisi sur le robot (0 : la recette n'en indique pas). */
    val servings: Int = 0,
)

class AttenteScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<AttenteState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private val title = Ui.ellipsize(Ui.text(c, "", 22f, Ui.TEXT, Ui.MEDIUM, lineHeight = 28f), 2)
    private val subtitle = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
    private val tiles = RobotTiles(c) { actions.tare() }

    override fun build(): View {
        page = LivePage(c)
        val card = Ui.card(c, 28f, Ui.SURFACE, Ui.ACCENT_LINE)
        Ui.pad(card, 18, 18, 18, 18)
        card.addView(Ui.chip(c, tr("En attente sur le robot"), Icon.Kind.SCREEN, Ui.TEXT, Ui.ACCENT_SOFT, iconSize = 16), Ui.lp(Ui.WRAP, Ui.WRAP))
        card.addView(title, Ui.lp(t = 12))
        card.addView(subtitle, Ui.lp(t = 2))
        card.addView(Ui.text(c, tr("À lancer sur le robot : touchez la recette sur l'accueil, puis Cuisiner."), 15f, lineHeight = 21f),
            Ui.lp(t = 12))
        val buttons = Ui.horizontal(c)
        buttons.addView(Ui.button(c, tr("Voir la recette"), Ui.Style.SECONDARY, height = 48) { actions.openSentRecipe() },
            Ui.lp(0, Ui.WRAP, 1f))
        buttons.addView(Ui.button(c, tr("Retirer"), Ui.Style.GHOST, height = 48) { actions.withdrawSentRecipe() },
            Ui.lp(0, Ui.WRAP, 1f, l = 10))
        card.addView(buttons, Ui.lp(t = 12))
        page.body.addView(card, Ui.lp(l = 20, t = 12, r = 20))

        val status = Ui.horizontal(c)
        status.background = Ui.round(Ui.SURFACE, 20f)
        Ui.pad(status, 18, 14, 18, 14)
        status.addView(Dot(c, Ui.OK), LinearLayout.LayoutParams(Ui.dp(10), Ui.dp(10)).apply { rightMargin = Ui.dp(12) })
        status.addView(Ui.ellipsize(Ui.text(c, tr("Base prête"), 24f, Ui.TEXT, Ui.LIGHT, lineHeight = 30f)), Ui.lp(0, Ui.WRAP, 1f, r = 12))
        status.addView(Ui.text(c, tr("rien en cours"), 13f, Ui.TEXT2))
        page.body.addView(status, Ui.lp(l = 20, t = 12, r = 20))

        page.body.addView(tiles, Ui.lp(l = 20, t = 12, r = 20))
        return page.root
    }

    override fun bind(s: AttenteState, old: AttenteState?) {
        page.header?.set(s.link)
        Ui.set(title, s.recipe)
        Ui.set(subtitle, tr("Envoyée à %s · %d étapes · %s", s.sentAt, s.stepCount, Fmt.duration(s.durationS)) +
            if (s.servings > 0) " · " + tr("%d pers.", s.servings) else "")
        tiles.set(s.bowlC, s.scaleG, s.lidClosed, s.bowlInPlace)
    }
}
