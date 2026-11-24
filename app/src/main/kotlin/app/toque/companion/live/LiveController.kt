package app.toque.companion.live

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.toque.companion.link.CommandOutcome
import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.LiveActions
import app.toque.companion.ui.Route
import app.toque.companion.ui.Screens
import app.toque.companion.ui.Shell
import app.toque.companion.ui.screens.AttenteState
import app.toque.companion.ui.screens.EtapesState
import app.toque.companion.ui.screens.FinState
import app.toque.companion.ui.screens.IncidentState
import app.toque.companion.ui.screens.MainState
import app.toque.companion.ui.screens.ManuelState
import app.toque.companion.ui.screens.PauseState
import app.toque.companion.ui.screens.PeseeState
import app.toque.companion.ui.screens.ProgrammeState
import app.toque.companion.ui.screens.ReposState
import app.toque.companion.ui.screens.RepriseState
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.StartRequest
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualSettings
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.StateWire

/**
 * Relie la liaison aux écrans « En direct » : chaque état (et chaque seconde, pour le compte à
 * rebours) passe par [LivePresenter], puis l'écran choisi est rendu et montré. Implémente aussi
 * [LiveActions] : la demande part vers le robot, qui décide ; son refus est montré avec son message.
 * Sur le fil principal uniquement.
 */
class LiveController(
    private val link: RobotLink,
    private val shell: Shell,
    private val mono: () -> Long = { SystemClock.elapsedRealtime() },
) : RobotLink.Listener, LiveActions {
    lateinit var screens: Screens
    private val handler = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null
    private var started = false

    private var status: LinkStatus = link.status
    private var state: StateWire? = null
    /** Arrivée de [state], sur l'horloge [mono] (et non celle de la liaison : seul l'écart compte). */
    private var receivedAt = 0L
    private var statusAt = 0L

    /** Commandes en cours : jusqu'à la réponse, puis jusqu'à l'état de la révision annoncée. */
    private class Wait(val since: Long, var revision: Long = Long.MAX_VALUE)
    private val pending = HashMap<Pending, Wait>()

    private var recipe: RecipeDetail? = null
    private var recipeAsked: String? = null

    /** Réglages de la cuisson manuelle en cours, saisis ici (affichés tout de suite, envoyés groupés). */
    private val tuner = ManualTuner(
        send = { req, done -> link.adjust(req, done) },
        schedule = { ms, block -> val r = Runnable { block() }; handler.postDelayed(r, ms); { handler.removeCallbacks(r) } },
        mono = mono, notice = { shell.notice(it) }, changed = { refresh() },
    )

    /** Écran Manuel en préparation (lancé depuis Repos), jusqu'à ce que la cuisson soit en route ou abandonnée. */
    private var preparing = false
    /** Lancement accepté : révision annoncée et heure de la réponse, jusqu'à l'état qui montre la cuisson. */
    private var startRevision = Long.MAX_VALUE
    private var startAnsweredAt = 0L

    /** Écran Fiche à venir : ouvrir la recette envoyée qui attend sur le robot. */
    var onOpenSentRecipe: ((recipeId: String) -> Unit)? = null

    /** Réglage de la cuisson manuelle préparée ici, gardé tant que l'appli reste ouverte. */
    private var draft: ManualSettings
        get() = keptDraft
        set(v) { keptDraft = v }

    /** Dernier rendu (tests). */
    var lastView: LiveView? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            tuner.tick()
            refresh()
            handler.postDelayed(this, 1_000L - (SystemClock.uptimeMillis() % 1_000L))
        }
    }

    /** L'activité est visible : suivre le robot et rendre chaque seconde. */
    fun attach() {
        if (started) return
        started = true
        // La liaison redit d'elle-même son état et le dernier état du robot (RobotLink.addListener).
        unsubscribe = link.addListener(this)
        status = link.status
        statusAt = mono()
        link.follow(OWNER)
        handler.post(tick)
    }

    /** L'activité n'est plus visible. */
    fun detach() {
        if (!started) return
        started = false
        handler.removeCallbacks(tick)
        unsubscribe?.invoke()
        unsubscribe = null
        link.unfollow(OWNER)
        state = null
    }

    override fun onStatus(status: LinkStatus) {
        if (status.phase != this.status.phase) statusAt = mono()
        this.status = status
        if (status.phase == LinkPhase.CONNECTING || status.phase == LinkPhase.NOT_PAIRED || status.phase == LinkPhase.REMOVED) state = null
        refresh()
    }

    override fun onState(state: StateWire, receivedMono: Long) {
        this.state = state
        // Arrivée réelle de l'état (un état redit à l'inscription peut dater) : le compte à rebours en tient compte.
        receivedAt = mono() - (link.nowMono - receivedMono).coerceIn(0, MAX_AGE_MS)
        val done = pending.filterValues { it.revision <= state.revision }.keys
        pending.keys.removeAll(done)
        tuner.onState(state)
        // La cuisson (ou une recette) est en route : la préparation est finie, l'écran Manuel en direct prend la suite.
        if (preparing && (LivePresenter.inProgress(state) || (state.revision >= startRevision && mono() - startAnsweredAt > START_GRACE_MS))) {
            preparing = false
            startRevision = Long.MAX_VALUE
        }
        val id = state.session?.recipeId
        if (id != null && id != recipeAsked) {
            recipeAsked = id
            link.recipe(id) { r -> r.onSuccess { recipe = it; refresh() } }
        }
        refresh()
    }

    /** Recalcule et rend l'écran « En direct ». */
    fun refresh() {
        if (!::screens.isInitialized) return
        val now = mono()
        pending.entries.removeAll { now - it.value.since > PENDING_MAX_MS }
        val offlineFor = if (status.phase == LinkPhase.UNREACHABLE) now - statusAt else 0L
        val v = LivePresenter.present(LiveInput(status, state, (now - receivedAt).coerceAtLeast(0), offlineFor,
            pending.keys.toSet(), recipe, manual = tuner.override, prep = if (preparing) draft else null))
        lastView = v
        renderLive(screens, shell, v)
    }

    // ------------------------------------------------------------------ actions

    private fun send(p: Pending, call: ((CommandOutcome) -> Unit) -> Unit) {
        if (p in pending) return
        pending[p] = Wait(mono())
        refresh()
        call { out ->
            val w = pending[p]
            if (!out.ok || (state?.revision ?: 0) >= out.revision) pending.remove(p) else w?.revision = out.revision
            if (!out.ok) shell.notice(out.message)
            refresh()
        }
    }

    private val job get() = state?.job

    override fun pause() = send(Pending.PAUSE) { link.pause(it) }
    override fun resume() = send(Pending.RESUME) { link.resume(it) }
    override fun stop() = send(Pending.STOP) { link.stop(it) }
    override fun tare() = send(Pending.TARE) { link.tare(it) }
    override fun clearEnded() = send(Pending.CLEAR) { link.clear(it) }
    override fun stopRecipe() = send(Pending.STOP_RECIPE) { link.stopRecipe(it) }
    override fun retry() = link.wake()
    override fun addMinute() = adjust(AdjustRequest(addMs = 60_000L))
    override fun adjustTime(deltaSeconds: Int) = adjust(AdjustRequest(addMs = deltaSeconds * 1_000L))

    // Cuisson manuelle : en préparation, le brouillon ; en cours, le réglage de la cuisson (ManualTuner).

    /** L'écran Manuel montre la préparation (aucune cuisson en cours). */
    private val inPrep get() = preparing && !LivePresenter.inProgress(state)

    private fun editDraft(e: ManualSettings.Edit?) {
        if (e == null) return
        draft = e.settings
        for (m in e.messages) shell.notice(m)
        refresh()
    }

    /** Brouillon aux bornes annoncées par le robot. */
    private fun draftNow(): ManualSettings = state?.limits?.manual()?.let { draft.copy(limits = it) } ?: draft

    override fun stepDuration(dir: Int) =
        if (inPrep) editDraft(draftNow().change(ManualSettings.CARD_TIME, dir)) else tuner.time(dir)

    override fun adjustTemp(delta: Int) =
        if (inPrep) editDraft(draftNow().change(ManualSettings.CARD_TEMP, delta)) else tuner.step(ManualSettings.CARD_TEMP, delta)

    override fun adjustSpeed(delta: Int) =
        if (inPrep) editDraft(draftNow().change(ManualSettings.CARD_SPEED, delta)) else tuner.step(ManualSettings.CARD_SPEED, delta)

    override fun setReverse(reverse: Boolean) =
        if (inPrep) editDraft(draftNow().withReverse(reverse)) else tuner.reverse(reverse)

    override fun setAccessory(whisk: Boolean) {
        if (inPrep) editDraft(draftNow().withAccessory(if (whisk) Accessory.WHISK else Accessory.BLADE))
    }

    override fun openManual() {
        if (LivePresenter.inProgress(state)) return
        preparing = true
        refresh()
    }

    override fun closeManual() {
        if (!preparing) return
        preparing = false
        startRevision = Long.MAX_VALUE
        refresh()
    }

    override fun startManual() {
        if (!inPrep || Pending.START in pending) return
        send(Pending.START) { done ->
            link.start(StartRequest.of(draftNow())) { out ->
                if (out.ok) {
                    startRevision = out.revision
                    startAnsweredAt = mono()
                }
                done(out)
            }
        }
    }

    private fun adjust(req: AdjustRequest) = send(Pending.ADJUST) { link.adjust(req, it) }

    /** Retrait de la recette en attente par l'onglet Recettes (qui suit aussi l'écran Envoyée), si branché. */
    var onWithdraw: (() -> Unit)? = null

    override fun withdrawSentRecipe() {
        val w = onWithdraw
        if (w != null) w() else send(Pending.WITHDRAW) { link.removeWaiting(it) }
    }

    override fun openSentRecipe() {
        val id = state?.waiting?.recipeId ?: return
        onOpenSentRecipe?.invoke(id)
    }

    companion object {
        private const val OWNER = "ui"
        /** Une commande sans nouvelles depuis… ne grise plus ses boutons. */
        const val PENDING_MAX_MS = 10_000L
        /** Âge retenu au plus pour un état reçu (horloge de la liaison incohérente : pas de saut aberrant). */
        const val MAX_AGE_MS = 10 * 60_000L
        /** Lancement accepté sans cuisson visible après ce délai (déjà finie…) : fin de la préparation. */
        const val START_GRACE_MS = 3_000L

        /** Brouillon de cuisson manuelle, pour la vie du processus (l'activité peut être recréée). */
        private var keptDraft = ManualSettings()

        /** Essais : un brouillon neuf. */
        internal fun forgetDraft() {
            keptDraft = ManualSettings()
        }

        /** Cran de température suivant (+1) ou précédent (−1) dans la table du robot ; 0 = sans chauffe. */
        fun nextTemperature(current: Int, delta: Int, temps: List<Int>): Int? = when {
            temps.isEmpty() -> null
            delta > 0 -> temps.firstOrNull { it > current }
            current <= 0 -> null
            else -> temps.lastOrNull { it < current } ?: 0
        }
    }
}

/** Rend [v] dans son écran et le montre à la racine de « En direct » (Étapes suit, s'il y a une recette). */
fun renderLive(screens: Screens, shell: Shell, v: LiveView) {
    when (v.route) {
        Route.REPOS -> screens.repos.render(v.screen as ReposState)
        Route.ATTENTE -> screens.attente.render(v.screen as AttenteState)
        Route.FIN -> screens.fin.render(v.screen as FinState)
        Route.MAIN -> screens.main.render(v.screen as MainState)
        Route.PESEE -> screens.pesee.render(v.screen as PeseeState)
        Route.PAUSE -> screens.pause.render(v.screen as PauseState)
        Route.REPRISE -> screens.reprise.render(v.screen as RepriseState)
        Route.MANUEL -> screens.manuel.render(v.screen as ManuelState)
        Route.PROGRAMME -> screens.programme.render(v.screen as ProgrammeState)
        Route.ETAPES -> screens.etapes.render(v.screen as EtapesState)
        Route.INCIDENT -> screens.incident.render(v.screen as IncidentState)
        else -> return
    }
    val etapes = v.etapes
    if (etapes != null && v.route != Route.ETAPES) screens.etapes.render(etapes)
    // La recette est finie pendant qu'on regardait ses étapes : retour au suivi.
    if (etapes == null && shell.current == Route.ETAPES && v.route != Route.ETAPES) shell.back()
    shell.showLive(v.route)
}
