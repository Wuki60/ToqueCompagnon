package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Icon
import app.toque.companion.ui.IconBadge
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
import app.toque.companion.ui.pauseButton
import app.toque.companion.ui.stopButton
import app.toque.core.i18n.tr

/** Les programmes du robot, avec l'icône de l'écran Programmes du robot. */
enum class ProgramKind(val icon: Icon.Kind) { KNEAD(Icon.Kind.KNEAD), STEAM(Icon.Kind.STEAM), TOAST(Icon.Kind.FLAME), CLEAN(Icon.Kind.DROP) }

/** Programme lancé sur le robot (maquette « Programme »). */
data class ProgrammeState(
    val link: Link = Link.CONNECTED,
    /** Heure de lancement sur le robot : « 19:02 », vide si inconnue (ligne cachée). */
    val startedAt: String,
    /** Nom du programme en cours : « Vapeur ». */
    val name: String,
    val bowlC: Int,
    val remainingMs: Long,
    val totalMs: Long,
    /** Réglages du programme en pastilles : « 120 °C », « Lame à l'arrêt ». */
    val tags: List<String>,
    /** Rappel à droite des pastilles : « 500 g d'eau au moins », ou vide. */
    val hint: String = "",
    /** Les programmes du robot ; celui qui tourne a [ProgramItem.running]. */
    val programs: List<ProgramItem>,
    val canPause: Boolean = true,
    val canStop: Boolean = true,
)

data class ProgramItem(val kind: ProgramKind, val name: String, val detail: String, val running: Boolean = false)

class ProgrammeScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<ProgrammeState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private val started = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2))
    private val ring = Ring(c, 10f)
    private val bowl = Ui.tabular(Ui.text(c, "", 34f, Ui.ACCENT_INK, Ui.LIGHT))
    private val name = Ui.ellipsize(Ui.text(c, "", 22f, Ui.TEXT, Ui.MEDIUM, lineHeight = 28f))
    private val time = Ui.tabular(Ui.text(c, "", 44f, Ui.TEXT, Ui.LIGHT).apply { letterSpacing = -1f / 44f })
    private val timeSub = Ui.text(c, "", 13f, Ui.TEXT2)
    private val tags = Ui.horizontal(c)
    private val hint = Ui.text(c, "", 13f, Ui.TEXT2).apply { gravity = Gravity.END }
    private val list = Ui.card(c)
    private val rows = ArrayList<Row>()
    private lateinit var pause: Ui.Button
    private lateinit var stop: Ui.Button

    override fun build(): View {
        page = LivePage(c)
        val top = Ui.horizontal(c)
        top.addView(Ui.ellipsize(Ui.overline(c, tr("Programme")), 2), Ui.lp(0, Ui.WRAP, 1f, r = 8))
        top.addView(started)
        page.body.addView(top, Ui.lp(l = 20, t = 10, r = 20))

        val card = Ui.card(c, 28f)
        Ui.pad(card, 18, 18, 18, 18)
        val head = Ui.horizontal(c)
        val ringBox = FrameLayout(c)
        ringBox.addView(ring, Ui.flp())
        ring.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val center = Ui.vertical(c, Gravity.CENTER)
        center.addView(bowl, Ui.lp(Ui.WRAP, Ui.WRAP))
        center.addView(Ui.text(c, tr("dans le bol"), 12f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP))
        ringBox.addView(center, Ui.flp())
        head.addView(ringBox, LinearLayout.LayoutParams(Ui.dp(128), Ui.dp(128)).apply { rightMargin = Ui.dp(18) })
        val right = Ui.vertical(c)
        right.addView(name)
        right.addView(time, Ui.lp(t = 2))
        right.addView(timeSub, Ui.lp(t = 2))
        head.addView(right, Ui.lp(0, Ui.WRAP, 1f))
        card.addView(head)
        card.addView(Ui.divider(c), Ui.lp(Ui.MATCH, maxOf(1, Ui.dp(1)), t = 14))
        val foot = Ui.horizontal(c)
        foot.addView(tags, Ui.lp(Ui.WRAP, Ui.WRAP))
        foot.addView(hint, Ui.lp(0, Ui.WRAP, 1f))
        card.addView(foot, Ui.lp(t = 14))
        page.body.addView(card, Ui.lp(l = 20, t = 12, r = 20))

        page.body.addView(Ui.overline(c, tr("Les programmes du robot")), Ui.lp(l = 20, t = 20, r = 20))
        Ui.pad(list, 16, 2, 16, 2)
        page.body.addView(list, Ui.lp(l = 20, t = 10, r = 20))
        page.body.addView(iconLine(c, Icon.Kind.SCREEN, tr("Un programme se lance sur le robot, puis se suit ici.")),
            Ui.lp(l = 20, t = 12, r = 20))

        pause = pauseButton(c) { actions.pause() }
        stop = stopButton(c) { actions.stop() }
        page.bar(actionBar(c, pause to 2f, stop to 1f))
        return page.root
    }

    override fun bind(s: ProgrammeState, old: ProgrammeState?) {
        page.header?.set(s.link)
        Ui.show(started, s.startedAt.isNotEmpty())
        if (s.startedAt.isNotEmpty()) Ui.set(started, tr("lancé sur le robot à %s", s.startedAt))
        Ui.setButtonEnabled(pause, s.canPause)
        Ui.setButtonEnabled(stop, s.canStop)
        Ui.set(bowl, "${s.bowlC}°")
        Ui.setColor(bowl, Ui.tempColor(s.bowlC))
        ring.progress = if (s.totalMs > 0) 1f - s.remainingMs.toFloat() / s.totalMs else 0f
        Ui.set(name, s.name)
        Ui.set(time, Ui.clock(s.remainingMs))
        Ui.set(timeSub, tr("restant sur %s", Fmt.duration((s.totalMs / 1000).toInt())))
        if (s.tags != old?.tags) {
            tags.removeAllViews()
            s.tags.forEachIndexed { i, t ->
                tags.addView(Ui.chip(c, t, color = Ui.TEXT, size = 13f, height = 30, padH = 12), Ui.lp(Ui.WRAP, Ui.WRAP, l = if (i > 0) 8 else 0))
            }
        }
        Ui.set(hint, s.hint)
        Ui.show(hint, s.hint.isNotEmpty())

        while (rows.size < s.programs.size) {
            val r = Row(c)
            if (rows.isNotEmpty()) list.addView(r.divider)
            list.addView(r.root)
            rows += r
        }
        while (rows.size > s.programs.size) {
            val r = rows.removeAt(rows.size - 1)
            list.removeView(r.root)
            list.removeView(r.divider)
        }
        s.programs.forEachIndexed { i, p -> rows[i].bind(p) }
    }

    private class Row(c: Context) {
        val divider = Ui.divider(c)
        val root: LinearLayout = Ui.horizontal(c)
        private val badge = IconBadge(c, Icon.Kind.KNEAD)
        private val name: TextView = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT, Ui.MEDIUM))
        private val detail: TextView = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2), 2)
        private val running = Ui.chip(c, tr("En cours"), color = Ui.TEXT, bg = Ui.ACCENT_SOFT, size = 12f, height = 26, padH = 10)

        init {
            root.minimumHeight = Ui.dp(60)
            root.addView(badge, LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)).apply { rightMargin = Ui.dp(12) })
            val col = Ui.vertical(c)
            col.addView(name)
            col.addView(detail, Ui.lp(t = 1))
            root.addView(col, Ui.lp(0, Ui.WRAP, 1f, t = 8, b = 8))
            root.addView(running, Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
        }

        fun bind(p: ProgramItem) {
            badge.icon.set(p.kind.icon)
            badge.setActive(p.running)
            Ui.set(name, p.name)
            Ui.set(detail, p.detail)
            Ui.show(running, p.running)
        }
    }
}
