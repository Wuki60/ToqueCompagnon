package app.toque.companion.ui.screens

import android.content.Context
import android.view.View
import app.toque.companion.ui.Icon
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.LivePage
import app.toque.companion.ui.Link
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.actionBar
import app.toque.companion.ui.iconLine
import app.toque.companion.ui.stopButton
import app.toque.core.i18n.tr

/** Ce qui empêche de suivre ou d'utiliser le robot (pas dans la maquette : affichage sobre). */
enum class IncidentKind { CONNECTING, OFFLINE, FAULT, BASE }

/**
 * « En direct » quand le robot ne peut pas être suivi (connexion, robot injoignable) ou ne peut pas
 * cuire (alerte machine, base moteur pas prête). Textes déjà mis en forme par le présentateur ; le
 * message d'une alerte est celui du robot.
 */
data class IncidentState(
    val link: Link = Link.CONNECTED,
    val kind: IncidentKind,
    /** Pastille : « Hors ligne », « Alerte machine »… */
    val chip: String,
    val title: String,
    val message: String,
    /** Précision en dessous (téléphone peut-être retiré, ce qui se fait sur le robot…), ou null. */
    val note: String? = null,
    /** Un programme tourne encore : le bouton d'arrêt reste là. */
    val canStop: Boolean = false,
    /** Robot injoignable : ce qui était en cours au dernier état reçu, ou null. */
    val lastKnown: LastKnown? = null,
    /** Bouton « Réessayer » (robot injoignable). */
    val canRetry: Boolean = false,
)

/**
 * Ce qui était en cours quand le robot a été entendu pour la dernière fois : textes au passé, rien
 * qui bouge ni qui ressemble à une commande (le robot ne répond pas).
 */
data class LastKnown(
    /** Recette ou programme. */
    val title: String,
    /** « Recette · étape 5 sur 9 », ou null. */
    val step: String?,
    /** « Il restait 6 min 20 au dernier contact », « En pause au dernier contact », ou null. */
    val status: String?,
    /** « Dernier état reçu il y a 3 min ». */
    val heard: String,
)

class IncidentScreen(c: Context, private val actions: LiveActions, private val nav: Navigator) : Screen<IncidentState>(c) {
    override val tab = Tab.LIVE
    private lateinit var page: LivePage
    private val chip = Ui.chip(c, "", Icon.Kind.WARNING, Ui.WARN, Ui.WARN_SOFT, iconSize = 16)
    private val title = Ui.ellipsize(Ui.text(c, "", 24f, Ui.TEXT, Ui.MEDIUM, lineHeight = 30f), 3)
    private val message = Ui.text(c, "", 15f, Ui.TEXT, lineHeight = 21f)
    private val note = iconLine(c, Icon.Kind.INFO, "")
    private lateinit var bar: View
    // Dernier état connu (robot hors ligne) : carte grise, sans rien qui ressemble à du direct.
    private val known = Ui.card(c, 24f, Ui.SURFACE)
    private val knownLabel = iconLine(c, Icon.Kind.CLOCK, tr("Dernier état connu"))
    private val knownTitle = Ui.ellipsize(Ui.text(c, "", 17f, Ui.TEXT2, Ui.MEDIUM, lineHeight = 22f), 2)
    private val knownStep = Ui.ellipsize(Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 20f), 2)
    private val knownStatus = Ui.text(c, "", 14f, Ui.TEXT2, lineHeight = 20f)
    private val knownHeard = Ui.text(c, "", 13f, Ui.TEXT3, lineHeight = 18f)
    private lateinit var retry: View

    override fun build(): View {
        page = LivePage(c)
        val card = Ui.card(c, 28f, Ui.SURFACE, Ui.WARN_LINE)
        Ui.pad(card, 20, 20, 20, 20)
        card.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP))
        card.addView(title, Ui.lp(t = 12))
        card.addView(message, Ui.lp(t = 10))
        page.body.addView(card, Ui.lp(l = 20, t = 12, r = 20))
        page.body.addView(note, Ui.lp(l = 20, t = 14, r = 20))
        Ui.pad(known, 20, 16, 20, 16)
        known.addView(knownLabel, Ui.lp())
        known.addView(knownTitle, Ui.lp(t = 10))
        known.addView(knownStep, Ui.lp(t = 4))
        known.addView(knownStatus, Ui.lp(t = 4))
        known.addView(knownHeard, Ui.lp(t = 10))
        page.body.addView(known, Ui.lp(l = 20, t = 16, r = 20))
        retry = Ui.button(c, tr("Réessayer"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 48) { actions.retry() }
        page.body.addView(retry, Ui.lp(l = 20, t = 16, r = 20))
        // Place vide à gauche (Space mesure 0 de haut) : l'arrêt à droite, comme dans les autres barres.
        bar = actionBar(c, android.widget.Space(c) to 2f, stopButton(c) { actions.stop() } to 1f)
        page.bar(bar)
        return page.root
    }

    override fun bind(s: IncidentState, old: IncidentState?) {
        page.header?.set(s.link)
        val (icon, color, bg) = when (s.kind) {
            IncidentKind.CONNECTING -> Triple(Icon.Kind.SYNC, Ui.TEXT, Ui.SURFACE2)
            IncidentKind.OFFLINE -> Triple(Icon.Kind.LINK, Ui.DANGER, Ui.SURFACE2)
            IncidentKind.FAULT -> Triple(Icon.Kind.WARNING, Ui.DANGER, Ui.SURFACE2)
            IncidentKind.BASE -> Triple(Icon.Kind.WARNING, Ui.WARN, Ui.WARN_SOFT)
        }
        (chip.lead as Icon).set(icon)
        (chip.lead as Icon).color = color
        chip.label.setTextColor(color)
        chip.background = Ui.round(bg, 16f)
        Ui.set(chip.label, s.chip)
        Ui.set(title, s.title)
        Ui.set(message, s.message)
        Ui.show(note, s.note != null)
        if (s.note != null) Ui.set(note.getChildAt(1) as android.widget.TextView, s.note)
        Ui.show(bar, s.canStop)
        val k = s.lastKnown
        Ui.show(known, k != null)
        if (k != null) {
            Ui.set(knownTitle, k.title)
            Ui.show(knownStep, k.step != null)
            Ui.set(knownStep, k.step.orEmpty())
            Ui.show(knownStatus, k.status != null)
            Ui.set(knownStatus, k.status.orEmpty())
            Ui.set(knownHeard, k.heard)
        }
        Ui.show(retry, s.canRetry)
    }
}
