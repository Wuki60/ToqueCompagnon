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
import app.toque.companion.ui.SegmentTabs
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.ValueStepper
import app.toque.companion.ui.actionBar
import app.toque.companion.ui.pauseButton
import app.toque.companion.ui.stopButton
import app.toque.core.i18n.tr

/**
 * Cuisson manuelle lancée sur le robot (ou depuis ce téléphone), réglable depuis le téléphone
 * (maquette « Cuisson manuelle ») ; avec [preparing], la même page sert à la préparer avant de la
 * lancer : réglage local, accessoire, couvercle et bol à la place du temps restant, « Démarrer ».
 */
data class ManuelState(
    val link: Link = Link.CONNECTED,
    /** Heure de lancement sur le robot : « 11:48 », vide si inconnue (ligne cachée). */
    val startedAt: String,
    /** Température mesurée dans le bol. */
    val bowlC: Int,
    val remainingMs: Long,
    val totalMs: Long,
    /** Consigne de température, 0 sans chauffe. */
    val targetC: Int,
    val speed: Int,
    val reverse: Boolean,
    /** Vitesse maximale en chauffe (affichée quand la chauffe est en marche), ou null. */
    val maxSpeedHeating: Int? = null,
    /** false : réglages grisés (le robot refuse les changements, ou une demande est en cours). */
    val canAdjust: Boolean = true,
    val canPause: Boolean = true,
    val canStop: Boolean = true,
    /**
     * Accessoire fouet (segments « Lame | Fouet ») : choisi en préparation, celui qui est monté
     * pendant la cuisson (verrouillé : il ne change pas en cours de route).
     */
    val whisk: Boolean = false,
    /** Préparation sur le téléphone : rien ne tourne encore. */
    val preparing: Boolean = false,
    val lidClosed: Boolean = true,
    val bowlInPlace: Boolean = true,
    /** Préparation : ce qui s'oppose au lancement, ou un rappel (vide : rien). */
    val hint: String = "",
    val hintWarn: Boolean = false,
    val canStart: Boolean = false,
    /** « Démarrer » envoyé, réponse attendue. */
    val starting: Boolean = false,
    /** Note de la durée en préparation (« max 99 min »), comme sur le robot. */
    val durationNote: String = "",
)

class ManuelScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<ManuelState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private val started = Ui.ellipsize(Ui.text(c, "", 13f, Ui.TEXT2))
    private val ring = Ring(c, 10f)
    private val bowl = Ui.tabular(Ui.text(c, "", 34f, Ui.ACCENT_INK, Ui.LIGHT))
    private val time = Ui.tabular(Ui.text(c, "", 48f, Ui.TEXT, Ui.LIGHT).apply { letterSpacing = -1f / 48f })
    private val timeSub = Ui.text(c, "", 13f, Ui.TEXT2)
    private val duration = ValueStepper(c, tr("Durée"), tr("Moins de temps"), tr("Plus de temps")) { actions.stepDuration(it) }
    private val temp = ValueStepper(c, tr("Température"), tr("Baisser la température"), tr("Monter la température")) { actions.adjustTemp(it) }
    private val speed = ValueStepper(c, tr("Vitesse"), tr("Baisser la vitesse"), tr("Monter la vitesse")) { actions.adjustSpeed(it) }
    private val direction = SegmentTabs(c, listOf(tr("Sens normal"), tr("Inverse")), accent = true) { actions.setReverse(it == 1) }
    private val accessory = SegmentTabs(c, listOf(tr("Lame"), tr("Fouet")), accent = true) { actions.setAccessory(it == 1) }
    private lateinit var pause: Ui.Button
    private lateinit var stop: Ui.Button
    private lateinit var start: Ui.Button
    private lateinit var back: View
    private lateinit var liveNote: View
    private val lid = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT))
    private val bowlState = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT))
    private val hint = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)

    override fun build(): View {
        page = LivePage(c)
        val top = Ui.horizontal(c)
        // Préparation : retour à « Robot au repos ».
        back = Ui.iconButton(c, Icon.Kind.BACK, tr("Retour"), visible = 40, iconSize = 20) { actions.closeManual() }
        top.addView(back, (back.layoutParams as LinearLayout.LayoutParams).apply { rightMargin = Ui.dp(6); leftMargin = -Ui.dp(4) })
        top.addView(Ui.ellipsize(Ui.overline(c, tr("Cuisson manuelle")), 2), Ui.lp(0, Ui.WRAP, 1f, r = 8))
        top.addView(started)
        page.body.addView(top, Ui.lp(l = 20, t = 10, r = 20))

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
        right.addView(time)
        right.addView(timeSub, Ui.lp(t = 2))
        // Préparation : l'état du couvercle et du bol à la place du temps restant.
        right.addView(lid)
        right.addView(bowlState, Ui.lp(t = 6))
        card.addView(right, Ui.lp(0, Ui.WRAP, 1f))
        page.body.addView(card, Ui.lp(l = 20, t = 12, r = 20))

        page.body.addView(duration.root, Ui.lp(l = 20, t = 12, r = 20))
        page.body.addView(temp.root, Ui.lp(l = 20, t = 10, r = 20))
        page.body.addView(speed.root, Ui.lp(l = 20, t = 10, r = 20))

        // Sens de rotation et accessoire : deux choix exclusifs de toute la largeur, comme les cartes
        // de réglage au-dessus. L'accessoire se choisit en préparation ; pendant la cuisson, celui qui
        // est monté reste affiché, verrouillé.
        page.body.addView(direction, Ui.lp(l = 20, t = 10, r = 20))
        page.body.addView(accessory, Ui.lp(l = 20, t = 10, r = 20))

        liveNote = Ui.text(c, tr("Chaque réglage s'applique tout de suite sur le robot, dans les limites de sécurité de Toque."),
            13f, Ui.TEXT2, lineHeight = 18f)
        page.body.addView(liveNote, Ui.lp(l = 20, t = 12, r = 20))
        page.body.addView(hint, Ui.lp(l = 20, t = 12, r = 20))

        pause = pauseButton(c) { actions.pause() }
        stop = stopButton(c) { actions.stop() }
        start = Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY) { actions.startManual() }
        page.bar(actionBar(c, pause to 2f, stop to 1f, start to 1f))
        return page.root
    }

    override fun bind(s: ManuelState, old: ManuelState?) {
        page.header?.set(s.link)
        val p = s.preparing
        Ui.show(back, p)
        // En préparation, « Démarrer » est seul dans la barre : aligné sur les cartes (sans l'écart des boutons voisins).
        (start.layoutParams as LinearLayout.LayoutParams).let { lp ->
            val m = if (p) 0 else Ui.dp(10)
            if (lp.leftMargin != m) { lp.leftMargin = m; start.layoutParams = lp }
        }
        for (v in listOf(time, timeSub, pause, stop, liveNote)) Ui.show(v, !p)
        for (v in listOf(lid, bowlState, start)) Ui.show(v, p)
        Ui.show(hint, p && s.hint.isNotEmpty())
        Ui.show(started, s.startedAt.isNotEmpty())
        if (s.startedAt.isNotEmpty()) Ui.set(started, tr("lancée sur le robot à %s", s.startedAt))
        Ui.set(bowl, "${s.bowlC}°")
        Ui.setColor(bowl, Ui.tempColor(s.bowlC))
        ring.progress = if (s.totalMs > 0) 1f - s.remainingMs.toFloat() / s.totalMs else 0f
        Ui.set(time, Ui.clock(s.remainingMs))
        Ui.set(timeSub, tr("restant sur %s", Fmt.duration((s.totalMs / 1000).toInt())))

        // En préparation, la durée s'écrit comme sur la carte du robot (« 01:30 »).
        if (p) duration.set(Ui.clock(s.totalMs), Ui.TEXT, s.durationNote)
        else duration.set(Fmt.duration((s.totalMs / 1000).toInt()), Ui.TEXT, tr("au total"))
        if (s.targetC > 0) temp.set(tr("%d °C", s.targetC), Ui.tempColor(s.targetC), Fmt.tempName(s.targetC))
        else temp.set("—", Ui.TEXT2, Fmt.tempName(0))
        val speedNote = Fmt.speedName(s.speed) +
            if (s.targetC > 0 && s.maxSpeedHeating != null) " · " + tr("%d maximum en chauffe", s.maxSpeedHeating) else ""
        speed.set("${s.speed}", Ui.TEXT, speedNote)
        for (st in listOf(duration, temp, speed)) st.enable(s.canAdjust)

        direction.select(if (s.reverse) 1 else 0)
        // Sans mélange, pas de sens de rotation (comme sur le robot) : grisé en préparation.
        direction.setState(s.canAdjust, dimmed = p && s.speed == 0)
        accessory.select(if (s.whisk) 1 else 0)
        accessory.setState(p && s.canAdjust)
        Ui.setButtonEnabled(pause, s.canPause)
        Ui.setButtonEnabled(stop, s.canStop)
        if (p) {
            Ui.set(lid, if (s.lidClosed) tr("Couvercle fermé") else tr("Couvercle ouvert"))
            Ui.setColor(lid, if (s.lidClosed) Ui.TEXT else Ui.WARN)
            Ui.set(bowlState, if (s.bowlInPlace) tr("Bol en place") else tr("Bol retiré"))
            Ui.setColor(bowlState, if (s.bowlInPlace) Ui.TEXT else Ui.WARN)
            Ui.set(hint, s.hint)
            Ui.setColor(hint, if (s.hintWarn) Ui.WARN else Ui.TEXT2)
            Ui.setButtonEnabled(start, s.canStart)
            Ui.set(start.label, if (s.starting) tr("Démarrage…") else tr("Démarrer"))
        }
    }

    override fun onBack(): Boolean {
        // Préparation : le retour système revient à « Robot au repos ».
        if (state?.preparing != true) return false
        actions.closeManual()
        return true
    }
}
