package app.toque.companion.ui.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.LivePage
import app.toque.companion.ui.Link
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.RecipeHeader
import app.toque.companion.ui.Ring
import app.toque.companion.ui.Route
import app.toque.companion.ui.Screen
import app.toque.companion.ui.StatTile
import app.toque.companion.ui.StopRecipe
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.actionBar
import app.toque.companion.ui.pauseButton
import app.toque.companion.ui.stopButton
import app.toque.companion.ui.tileRow
import app.toque.core.i18n.tr

/** Recette guidée, étape de cuisson en cours (maquette « Recette en cours »). */
data class MainState(
    val link: Link = Link.CONNECTED,
    val recipe: String,
    /** Index (0…) de l'étape en cours. */
    val stepIndex: Int,
    val stepCount: Int,
    /** Nom court de l'étape (programme : « Pétrir ») ; vide : seule la pastille des réglages, centrée. */
    val action: String,
    val remainingMs: Long,
    val totalMs: Long,
    /** Réglages de l'étape, voir [Fmt.cooking] : « 20 min · 100 °C · vitesse 1 ». */
    val settings: String,
    /** Température mesurée dans le bol. */
    val tempC: Int?,
    /** Consigne, null sans chauffe. */
    val targetC: Int?,
    val speed: Int,
    val lidClosed: Boolean,
    val lidLocked: Boolean,
    /** Consigne de l'étape, en clair. */
    val instruction: String,
    /** Geste à faire à la main ensuite (« Ajouter 100 g de crème »), ou null. */
    val nextByHand: String? = null,
    val canAddMinute: Boolean = true,
    val canPause: Boolean = true,
    val canStop: Boolean = true,
    /** « Arrêter la recette » proposé (robot qui le sait faire) ; [canStopRecipe] : utilisable maintenant. */
    val stopRecipeOffered: Boolean = false,
    val canStopRecipe: Boolean = false,
)

class MainScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<MainState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private lateinit var header: RecipeHeader
    private val ring = Ring(c, 12f)
    private val time = Ui.tabular(Ui.text(c, "", 52f, Ui.TEXT, Ui.LIGHT, lineHeight = 56f).apply { letterSpacing = -1f / 52f })
    private val timeSub = Ui.text(c, "", 13f, Ui.TEXT2)
    private val action = Ui.ellipsize(Ui.text(c, "", 18f, Ui.TEXT, Ui.MEDIUM))
    private val settings = Ui.chip(c, "", color = Ui.TEXT, size = 13f, height = 30, padH = 12)
    private val temp = StatTile(c, tr("Température"), 26f, 32f)
    private val speed = StatTile(c, tr("Vitesse"), 26f, 32f)
    private val lid = StatTile(c, tr("Couvercle"), 22f, 32f)
    private val instruction = Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 21f)
    private val nextRow = Ui.horizontal(c)
    private val nextText = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 19f)
    private val nextDivider = Ui.divider(c)
    private lateinit var addMinute: Ui.Button
    private lateinit var pause: Ui.Button
    private lateinit var stop: Ui.Button
    private val stopRecipe = StopRecipe(c) { actions.stopRecipe() }

    override fun build(): View {
        page = LivePage(c)
        header = RecipeHeader(c) { nav.open(Route.ETAPES) }
        page.body.addView(header)

        // Minuteur : anneau de 196 dp, temps restant au centre, nom et réglages de l'étape dessous.
        val card = Ui.card(c, 28f)
        card.gravity = Gravity.CENTER_HORIZONTAL
        Ui.pad(card, 18, 18, 18, 16)
        val ringBox = FrameLayout(c)
        ringBox.addView(ring, Ui.flp())
        val center = Ui.vertical(c, Gravity.CENTER)
        center.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP))
        center.addView(timeSub, Ui.lp(Ui.WRAP, Ui.WRAP, t = 2))
        ringBox.addView(center, Ui.flp())
        ring.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        card.addView(ringBox, LinearLayout.LayoutParams(Ui.dp(196), Ui.dp(196)))
        val row = Ui.horizontal(c, Gravity.CENTER)
        row.addView(action, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(settings, Ui.lp(Ui.WRAP, Ui.WRAP))
        card.addView(row, Ui.lp(t = 12))
        page.body.addView(card, Ui.lp(l = 20, t = 14, r = 20))

        page.body.addView(tileRow(c, temp, speed, lid), Ui.lp(l = 20, t = 12, r = 20))

        val step = Ui.card(c)
        Ui.pad(step, 16, 14, 16, 14)
        step.addView(instruction)
        step.addView(nextDivider, Ui.lp(Ui.MATCH, maxOf(1, Ui.dp(1)), t = 10))
        nextRow.addView(Ui.chip(c, tr("Ensuite, à la main"), color = Ui.TEXT, bg = Ui.SURFACE3, size = 12f, height = 24, padH = 9),
            Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        nextRow.addView(nextText, Ui.lp(0, Ui.WRAP, 1f))
        step.addView(nextRow, Ui.lp(t = 10))
        page.body.addView(step, Ui.lp(l = 20, t = 12, r = 20))
        // Arrêter toute la recette : dans le contenu, loin du Stop rouge de la barre.
        page.body.addView(stopRecipe.button, Ui.lp(Ui.WRAP, Ui.WRAP, l = 20, t = 14, r = 20).apply { gravity = Gravity.CENTER_HORIZONTAL })

        addMinute = Ui.button(c, tr("+1 min"), Ui.Style.SECONDARY, textSize = 17f) { actions.addMinute() }
        pause = pauseButton(c) { actions.pause() }
        stop = stopButton(c) { actions.stop() }
        page.bar(actionBar(c, pause to 1f, addMinute to 1f, stop to 1f))
        val frame = FrameLayout(c)
        frame.addView(page.root, Ui.flp())
        frame.addView(stopRecipe.sheet, Ui.flp())
        return frame
    }

    override fun onBack(): Boolean {
        if (!stopRecipe.sheet.showing) return false
        stopRecipe.sheet.hide()
        return true
    }

    override fun onHide() = stopRecipe.sheet.hide()

    override fun bind(s: MainState, old: MainState?) {
        page.header?.set(s.link)
        header.set(s.recipe, s.stepIndex, s.stepCount, paused = false)
        Ui.set(time, Ui.clock(s.remainingMs))
        Ui.set(timeSub, tr("restant sur %s", Fmt.duration((s.totalMs / 1000).toInt())))
        ring.progress = if (s.totalMs > 0) 1f - s.remainingMs.toFloat() / s.totalMs else 0f
        ring.color = Ui.ACCENT
        Ui.set(action, s.action)
        Ui.show(action, s.action.isNotEmpty())
        Ui.set(settings.label, s.settings)
        Ui.show(settings, s.settings.isNotEmpty())
        temp.set(Fmt.bowl(s.tempC), Ui.bowlColor(s.tempC), if (s.targetC != null) tr("vers %d °C", s.targetC) else tr("sans chauffe"))
        speed.set("${s.speed}", Ui.TEXT, Fmt.speedName(s.speed))
        if (s.lidClosed) lid.set(tr("Fermé"), Ui.TEXT, if (s.lidLocked) tr("verrouillé") else null)
        else lid.set(tr("Ouvert"), Ui.WARN, null)
        Ui.set(instruction, s.instruction)
        Ui.show(instruction, s.instruction.isNotEmpty())
        val next = s.nextByHand
        Ui.show(nextRow, next != null)
        Ui.show(nextDivider, next != null && s.instruction.isNotEmpty())
        if (next != null) Ui.set(nextText, next)
        Ui.setButtonEnabled(addMinute, s.canAddMinute)
        Ui.setButtonEnabled(pause, s.canPause)
        Ui.setButtonEnabled(stop, s.canStop)
        stopRecipe.bind(s.stopRecipeOffered, s.canStopRecipe, cooking = true)
    }
}
