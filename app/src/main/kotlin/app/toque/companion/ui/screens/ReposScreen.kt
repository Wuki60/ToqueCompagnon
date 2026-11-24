package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.toque.companion.ui.Dot
import app.toque.companion.ui.Icon
import app.toque.companion.ui.IconBadge
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.LivePage
import app.toque.companion.ui.Link
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Ring
import app.toque.companion.ui.RobotTiles
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr

/** Robot au repos (maquette « Robot au repos »). */
data class ReposState(
    val link: Link = Link.CONNECTED,
    val bowlC: Int,
    val scaleG: Int,
    val lidClosed: Boolean,
    val bowlInPlace: Boolean,
    /** Dernière activité (ouvre le Journal), ou null. */
    val last: LastActivity? = null,
    /** « Cuisson manuelle » : le robot permet de la lancer d'ici ; sinon [startManualReason] sous le bouton. */
    val canStartManual: Boolean = true,
    val startManualReason: String = "",
)

/** Ligne « Dernière activité » : titre et détail (« Recette terminée à 12:41 · 36 min »). */
data class LastActivity(val title: String, val detail: String)

class ReposScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<ReposState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private val tiles = RobotTiles(c) { actions.tare() }
    private val lastTitle = Ui.overline(c)
    private val lastRow = Ui.horizontal(c)
    private val lastName = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT, Ui.MEDIUM))
    private val lastDetail = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2), 2)
    private lateinit var manual: Ui.Button
    private val manualReason = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f).apply { gravity = Gravity.CENTER }

    override fun build(): View {
        page = LivePage(c)
        val card = Ui.card(c, 28f)
        card.gravity = Gravity.CENTER_HORIZONTAL
        Ui.pad(card, 22, 18, 22, 18)
        val ringBox = FrameLayout(c)
        ringBox.addView(Ring(c, 12f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, Ui.flp())
        val center = Ui.vertical(c, Gravity.CENTER)
        // Tout tient dans le cercle : une marge de chaque côté, et l'état sur une seule ligne, rapetissé
        // au besoin (« Base prête », « Base lista » sont plus longs que « Ready »), la pastille à sa gauche.
        Ui.pad(center, RING_INSET, 0, RING_INSET, 0)
        val ready = Ui.horizontal(c, Gravity.CENTER)
        ready.addView(Dot(c, Ui.OK), LinearLayout.LayoutParams(Ui.dp(10), Ui.dp(10)).apply { rightMargin = Ui.dp(8) })
        ready.addView(Ui.fitText(c, tr("Base prête"), 40f, Ui.TEXT, Ui.LIGHT, minScale = 0.5f), Ui.lp(Ui.WRAP, Ui.WRAP))
        center.addView(ready, Ui.lp(Ui.WRAP, Ui.WRAP))
        center.addView(Ui.text(c, tr("rien en cours"), 13f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        ringBox.addView(center, Ui.flp())
        card.addView(ringBox, LinearLayout.LayoutParams(Ui.dp(RING), Ui.dp(RING)))
        card.addView(Ui.text(c, tr("Lancez une cuisson manuelle d'ici, ou une recette ou un programme sur le robot : vous suivrez tout ici."),
            14f, Ui.TEXT2, lineHeight = 20f).apply { gravity = Gravity.CENTER }, Ui.lp(t = 12))
        // L'icône de la tuile « Cuisson manuelle » de l'accueil du robot.
        manual = Ui.button(c, tr("Cuisson manuelle"), Ui.Style.PRIMARY, Icon.Kind.POT) { actions.openManual() }
        card.addView(manual, Ui.lp(Ui.MATCH, Ui.WRAP, t = 14))
        card.addView(manualReason, Ui.lp(t = 6))
        page.body.addView(card, Ui.lp(l = 20, t = 12, r = 20))

        page.body.addView(tiles, Ui.lp(l = 20, t = 12, r = 20))

        lastTitle.text = tr("Dernière activité")
        page.body.addView(lastTitle, Ui.lp(l = 20, t = 18, r = 20))
        lastRow.background = Ui.pressable(Ui.round(Ui.SURFACE, 20f), 20f)
        Ui.pad(lastRow, 14, 14, 10, 14)
        lastRow.addView(IconBadge(c, Icon.Kind.BOOK), LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)).apply { rightMargin = Ui.dp(12) })
        val col = Ui.vertical(c)
        col.addView(lastName)
        col.addView(lastDetail, Ui.lp(t = 1))
        lastRow.addView(col, Ui.lp(0, Ui.WRAP, 1f, r = 8))
        lastRow.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2), LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)))
        lastRow.isClickable = true
        lastRow.isFocusable = true
        lastRow.setOnClickListener { nav.select(Tab.LOG) }
        page.body.addView(lastRow, Ui.lp(l = 20, t = 10, r = 20))
        return page.root
    }

    override fun bind(s: ReposState, old: ReposState?) {
        page.header?.set(s.link)
        tiles.set(s.bowlC, s.scaleG, s.lidClosed, s.bowlInPlace)
        Ui.setButtonEnabled(manual, s.canStartManual)
        Ui.show(manualReason, !s.canStartManual && s.startManualReason.isNotEmpty())
        Ui.set(manualReason, s.startManualReason)
        val last = s.last
        Ui.show(lastTitle, last != null)
        Ui.show(lastRow, last != null)
        if (last != null) {
            Ui.set(lastName, last.title)
            Ui.set(lastDetail, last.detail)
        }
    }

    companion object {
        /** Côté du cercle, en dp. */
        const val RING = 196
        /** Marge du contenu de chaque côté du cercle : son trait (12 dp) et de l'air. */
        const val RING_INSET = 22
    }
}
