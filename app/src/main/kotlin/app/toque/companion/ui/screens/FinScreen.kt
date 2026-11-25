package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Icon
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.LivePage
import app.toque.companion.ui.Link
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Ring
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.actionBar
import app.toque.companion.ui.iconLine
import app.toque.core.i18n.tr

/**
 * Programme terminé ou arrêté, encore affiché sur le robot avec son bouton OK (barre machine du
 * robot) : le téléphone montre la même fin et le même OK, qui ferme l'affichage des deux côtés.
 */
data class FinState(
    val link: Link = Link.CONNECTED,
    /** Nom du programme : « Cuisson manuelle », « Vapeur »… */
    val name: String,
    /** Terminé normalement (vert) ; sinon arrêté ou interrompu (rouge). */
    val finished: Boolean,
    /** « Terminé », ou la raison du robot : « Arrêté », « Pause trop longue : programme annulé », une alerte. */
    val end: String,
    /** Durée : « 8 min au total », « Après 3 min 20 sur 8 min ». */
    val detail: String,
    /** Réglages : « 90 °C · vitesse 2 · sens inverse », vide pour aucun. */
    val settings: String = "",
    /** Avancement du programme quand il s'est arrêté (0 à 1). */
    val progress: Float = 1f,
    val bowlC: Int?,
    /** Contenu chaud (≥ 60 °C) : avertissement. */
    val hot: Boolean = false,
    val canOk: Boolean = true,
)

class FinScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<FinState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private val ring = Ring(c, 10f)
    private val bowl = Ui.tabular(Ui.text(c, "", 34f, Ui.ACCENT_INK, Ui.LIGHT))
    private val name = Ui.ellipsize(Ui.text(c, "", 22f, Ui.TEXT, Ui.MEDIUM, lineHeight = 28f), 2)
    private val end = Ui.ellipsize(Ui.text(c, "", 17f, Ui.OK, Ui.MEDIUM, lineHeight = 22f), 3)
    private val detail = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f), 2)
    private val settings = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f), 2)
    private lateinit var hotCard: LinearLayout
    private val hotText = Ui.text(c, "", 14f, Ui.WARN, lineHeight = 19f)
    private lateinit var ok: Ui.Button

    override fun build(): View {
        page = LivePage(c)
        page.body.addView(Ui.ellipsize(Ui.overline(c, tr("Fin du programme")), 2), Ui.lp(l = 20, t = 10, r = 20))

        val card = Ui.card(c, 28f)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        Ui.pad(card, 18, 18, 18, 18)
        val ringBox = FrameLayout(c)
        ringBox.addView(ring, Ui.flp())
        ring.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val center = Ui.vertical(c, Gravity.CENTER)
        center.addView(bowl, Ui.lp(Ui.WRAP, Ui.WRAP))
        center.addView(Ui.text(c, tr("dans le bol"), 12f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP))
        ringBox.addView(center, Ui.flp())
        card.addView(ringBox, LinearLayout.LayoutParams(Ui.dp(128), Ui.dp(128)).apply { rightMargin = Ui.dp(18) })
        val right = Ui.vertical(c)
        right.addView(name)
        right.addView(end, Ui.lp(t = 4))
        right.addView(detail, Ui.lp(t = 6))
        right.addView(settings, Ui.lp(t = 2))
        card.addView(right, Ui.lp(0, Ui.WRAP, 1f))
        page.body.addView(card, Ui.lp(l = 20, t = 12, r = 20))

        // Contenu chaud : le même jaune que les avertissements des étapes.
        hotCard = Ui.card(c, 20f, Ui.WARN_SOFT, Ui.WARN_LINE)
        hotCard.orientation = LinearLayout.HORIZONTAL
        hotCard.gravity = Gravity.CENTER_VERTICAL
        Ui.pad(hotCard, 16, 12, 16, 12)
        hotCard.addView(Icon(c, Icon.Kind.THERMO, Ui.WARN), LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)).apply { rightMargin = Ui.dp(12) })
        hotCard.addView(hotText, Ui.lp(0, Ui.WRAP, 1f))
        page.body.addView(hotCard, Ui.lp(l = 20, t = 12, r = 20))

        page.body.addView(iconLine(c, Icon.Kind.SCREEN, tr("OK ferme aussi le programme affiché sur le robot.")),
            Ui.lp(l = 20, t = 14, r = 20))

        ok = Ui.button(c, "OK", Ui.Style.PRIMARY, Icon.Kind.CHECK) { actions.clearEnded() }
        page.bar(actionBar(c, ok to 1f))
        return page.root
    }

    override fun bind(s: FinState, old: FinState?) {
        page.header?.set(s.link)
        Ui.set(bowl, Fmt.bowl(s.bowlC))
        Ui.setColor(bowl, Ui.bowlColor(s.bowlC))
        ring.color = if (s.finished) Ui.OK else Ui.DANGER
        ring.progress = s.progress
        Ui.set(name, s.name)
        Ui.set(end, s.end)
        Ui.setColor(end, if (s.finished) Ui.OK else Ui.DANGER)
        Ui.set(detail, s.detail)
        Ui.show(detail, s.detail.isNotEmpty())
        Ui.set(settings, s.settings)
        Ui.show(settings, s.settings.isNotEmpty())
        Ui.show(hotCard, s.hot)
        Ui.set(hotText, if (s.bowlC == null) tr("Contenu chaud : attention en ouvrant le couvercle.")
            else tr("Contenu chaud (%d °C) : attention en ouvrant le couvercle.", s.bowlC))
        Ui.setButtonEnabled(ok, s.canOk)
    }
}
