package app.toque.companion.live

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.ui.Fmt
import app.toque.companion.ui.Link
import app.toque.companion.ui.Route
import app.toque.companion.ui.StepStatus
import app.toque.companion.ui.screens.AttenteState
import app.toque.companion.ui.screens.EtapeItem
import app.toque.companion.ui.screens.EtapesState
import app.toque.companion.ui.screens.FinState
import app.toque.companion.ui.screens.IncidentKind
import app.toque.companion.ui.screens.IncidentState
import app.toque.companion.ui.screens.IngredientItem
import app.toque.companion.ui.screens.LastActivity
import app.toque.companion.ui.screens.LastKnown
import app.toque.companion.ui.screens.MainState
import app.toque.companion.ui.screens.ManuelState
import app.toque.companion.ui.screens.NowBar
import app.toque.companion.ui.screens.PauseCause
import app.toque.companion.ui.screens.PauseState
import app.toque.companion.ui.screens.PeseeState
import app.toque.companion.ui.screens.ProgramItem
import app.toque.companion.ui.screens.ProgramKind
import app.toque.companion.ui.screens.ProgrammeState
import app.toque.companion.ui.screens.ReposState
import app.toque.companion.ui.screens.RepriseState
import app.toque.companion.ui.screens.StepProgress
import app.toque.companion.ui.screens.WeighItem
import app.toque.core.companion.ActivityWire
import app.toque.core.companion.JobWire
import app.toque.core.companion.MachineStepWire
import app.toque.core.companion.Origin
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.SessionWire
import app.toque.core.companion.StateWire
import app.toque.core.companion.StepWire
import app.toque.core.i18n.tr
import app.toque.core.companion.Permission
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualSettings
import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Commandes qui peuvent attendre la réponse du robot (bouton grisé en attendant). */
enum class Pending { PAUSE, RESUME, STOP, ADJUST, TARE, WITHDRAW, START, CLEAR, STOP_RECIPE }

/** Ce que le présentateur reçoit : la liaison, le dernier état et son âge, les commandes en cours. */
data class LiveInput(
    val link: LinkStatus,
    val state: StateWire?,
    /** Temps écoulé depuis l'arrivée de [state] (le compte à rebours avance d'autant). */
    val ageMs: Long = 0,
    /** Durée de la panne en cours ([LinkPhase.UNREACHABLE]), 0 sinon. */
    val offlineForMs: Long = 0,
    val pending: Set<Pending> = emptySet(),
    /** Fiche de la recette guidée (onglet Ingrédients des étapes), si elle a été lue. */
    val recipe: RecipeDetail? = null,
    /** Heure du téléphone maintenant (heures affichées : « lancée à 11:48 »). */
    val nowWall: Long = System.currentTimeMillis(),
    val zone: TimeZone = TimeZone.getDefault(),
    /** Réglage saisi ici sur la cuisson manuelle en cours, pas encore appliqué par le robot (affiché à la place du sien). */
    val manual: ManualOverride? = null,
    /** Cuisson manuelle en préparation sur ce téléphone (lancée depuis Repos), ou null. */
    val prep: ManualSettings? = null,
)

/** L'écran racine de « En direct » et son état ; [etapes] : l'état de l'écran Étapes, s'il y a une recette. */
data class LiveView(val route: Route, val screen: Any, val etapes: EtapesState? = null)

/**
 * Du [StateWire] aux états des écrans « En direct », en Kotlin pur (testé sur la JVM).
 *
 * Règles : rien en cours → Repos, ou Attente si une recette attend sur le robot ; recette guidée →
 * Pesée sur une pesée, Main pendant qu'une étape tourne, Pause ou Reprise en pause (Reprise seulement
 * si l'état dit que ce téléphone peut reprendre), Étapes (racine, sans retour) quand le robot attend
 * le cuisinier sur autre chose qu'une pesée ; sans recette : Manuel ou Programme, avec les mêmes
 * pauses. Alerte machine, base pas prête et robot injoignable passent avant tout (écran Incident).
 * Un programme terminé ou arrêté encore affiché sur le robot avec son OK (state.can.clear) : écran
 * Fin sans recette, avant la préparation, Attente et Repos ; dans une recette, seul un arrêt se montre,
 * dans la barre du bas des Étapes (une étape finie s'efface d'elle-même sur le robot).
 * Le contenu vient de l'état : les textes d'étape sont ceux de la recette, jamais inventés.
 */
object LivePresenter {
    /** Une panne plus courte garde l'écran (pastille « Hors ligne ») avant l'écran du robot injoignable. */
    const val OFFLINE_GRACE_MS = 8_000L

    private val ACTIVE = setOf("STARTING", "RUNNING", "STOPPING", "PAUSED")
    private val ENDED = setOf("FINISHED", "ABORTED")

    /**
     * Le programme terminé ou arrêté que le robot affiche encore avec son bouton OK, si ce téléphone
     * peut le fermer ; un robot plus ancien ne dit rien (pas de can.clear) : null, comme avant.
     */
    fun endedJob(s: StateWire): JobWire? = s.job?.takeIf { it.state in ENDED && s.can.clear.allowed }

    fun present(i: LiveInput): LiveView {
        val s = i.state
        val link = when (i.link.phase) {
            LinkPhase.CONNECTED -> Link.CONNECTED
            LinkPhase.UNREACHABLE -> Link.OFFLINE
            else -> Link.CONNECTING
        }
        if (i.link.phase == LinkPhase.UNREACHABLE && (s == null || i.offlineForMs >= OFFLINE_GRACE_MS)) return offline(i)
        if (s == null) return connecting(i)
        val ctx = Ctx(i, s, link)
        val job = s.job?.takeIf { it.state in ACTIVE }
        val m = s.machine
        val canStop = job != null && s.can.stop.allowed && Pending.STOP !in i.pending
        if (m.faultCode.isNotEmpty()) return incident(IncidentState(link, IncidentKind.FAULT, tr("Alerte machine"),
            m.faultMessage.ifEmpty { m.faultCode }, tr("L'alerte s'affiche et se règle sur l'écran du robot."),
            note = tr("Rien ne peut démarrer tant qu'elle est là."), canStop = canStop), ctx)
        if (m.link != "READY") return incident(IncidentState(link, IncidentKind.BASE, m.linkLabel.ifEmpty { tr("Base moteur") },
            tr("Base moteur pas prête"), tr("Le robot attend sa base moteur. Rien ne peut démarrer tant qu'elle n'est pas prête."),
            canStop = canStop), ctx)

        val session = s.session
        if (session != null) {
            val etapes = etapes(ctx, session, job)
            if (job != null) {
                if (job.state == "PAUSED") return paused(ctx, job, session).copy(etapes = etapes)
                return LiveView(Route.MAIN, main(ctx, session, job), etapes)
            }
            val step = session.steps.getOrNull(session.current)
            if (session.waitingFor == "weigh" && step?.weigh != null) return LiveView(Route.PESEE, pesee(ctx, session, step), etapes)
            return LiveView(Route.ETAPES, etapes.copy(root = true), etapes.copy(root = true))
        }
        if (job != null) {
            if (job.state == "PAUSED") return paused(ctx, job, null)
            return if (isManual(job)) LiveView(Route.MANUEL, manuel(ctx, job)) else LiveView(Route.PROGRAMME, programme(ctx, job))
        }
        val ended = endedJob(s)
        if (ended != null) return LiveView(Route.FIN, fin(ctx, ended))
        val prep = i.prep
        if (prep != null) return LiveView(Route.MANUEL, preparation(ctx, prep))
        val w = s.waiting
        if (w != null) return LiveView(Route.ATTENTE, AttenteState(link, w.title, ctx.clock(w.sentAt), w.stepCount, w.totalMinutes * 60,
            m.tempC, m.weightG, !m.lidOpen, bowlInPlace(s), servings = w.servings))
        val start = startPermission(s)
        return LiveView(Route.REPOS, ReposState(link, m.tempC, m.weightG, !m.lidOpen, bowlInPlace(s), s.last?.let { last(ctx, it) },
            canStartManual = start.allowed && Pending.START !in i.pending, startManualReason = start.reason))
    }

    /** Lancement d'une cuisson manuelle : la permission du robot ; un robot plus ancien ne la connaît pas. */
    fun startPermission(s: StateWire): Permission {
        val p = s.can.start
        if (p.allowed || p.code.isNotEmpty()) return p
        return Permission.no("update", tr("Mettez Toque à jour sur le robot"))
    }

    /** Une cuisson manuelle réglable (une phase) ; le reste (Nettoyage, programmes) passe par Programme. */
    fun isManual(job: JobWire) = job.kind == "MANUAL" && job.phaseCount <= 1

    /** Quelque chose est en cours sur le robot : une recette guidée ou un programme. */
    fun inProgress(s: StateWire?): Boolean = s != null && (s.session != null || s.job?.state in ACTIVE)

    /** Une recette envoyée qui attend sur le robot compte comme « en cours » pendant ce temps après l'envoi. */
    const val WAITING_FOLLOW_MS = 30 * 60_000L

    /**
     * Le suivi en arrière-plan doit continuer : quelque chose est en cours, ou une recette attend sur
     * le robot depuis moins de [WAITING_FOLLOW_MS] (envoyée, téléphone en poche, lancée sur le robot :
     * les alertes doivent arriver). Horloge du robot des deux côtés.
     */
    fun keepFollowing(s: StateWire?): Boolean {
        if (s == null) return false
        if (inProgress(s)) return true
        val w = s.waiting ?: return false
        return s.serverTime - w.sentAt < WAITING_FOLLOW_MS
    }

    // ------------------------------------------------------------------ contexte

    private class Ctx(val i: LiveInput, val s: StateWire, val link: Link) {
        /** Temps restant extrapolé : il ne bouge que si le minuteur avance (docs, section 4). */
        fun remaining(job: JobWire): Long =
            if (job.counting) (job.remainingMs - i.ageMs).coerceAtLeast(0) else job.remainingMs.coerceAtLeast(0)

        fun autoCancel(job: JobWire): Long? {
            val p = job.pause ?: return null
            if (p.autoCancelInMs < 0) return null
            return (p.autoCancelInMs - i.ageMs).coerceAtLeast(0)
        }

        /** Heure du robot [robotWall] vue sur l'horloge du téléphone (sans l'écart des horloges), « HH:mm ». */
        fun clock(robotWall: Long): String {
            if (robotWall <= 0) return ""
            val phone = i.nowWall - i.ageMs - (s.serverTime - robotWall)
            val f = SimpleDateFormat("HH:mm", Locale.ROOT)
            f.timeZone = i.zone
            return f.format(Date(phone))
        }

        fun can(p: Pending, allowed: Boolean) = allowed && p !in i.pending
    }

    private fun bowlInPlace(s: StateWire) = s.machine.bowlDetected && !s.machine.bowlRemoved

    private fun incident(st: IncidentState, ctx: Ctx) = LiveView(Route.INCIDENT, st,
        ctx.s.session?.let { etapes(ctx, it, ctx.s.job?.takeIf { j -> j.state in ACTIVE }) })

    private fun offline(i: LiveInput): LiveView {
        val name = i.link.robotName.ifEmpty { tr("le robot") }
        val note = if (i.link.maybeRemoved)
            tr("Un appareil répond à l'adresse du robot sans le reconnaître : ce téléphone a peut-être été retiré sur le robot. Il faudra peut-être l'appairer de nouveau.")
        else tr("Vérifiez que le robot est allumé et que le téléphone est sur le même Wi-Fi.")
        return LiveView(Route.INCIDENT, IncidentState(Link.OFFLINE, IncidentKind.OFFLINE, tr("Hors ligne"), tr("Robot injoignable"),
            tr("Le téléphone cherche %s sur le Wi-Fi de la maison.", name), note,
            lastKnown = i.state?.let { lastKnown(it, i.ageMs) }, canRetry = true))
    }

    /**
     * Robot injoignable : ce qui était en cours au dernier état reçu (recette guidée ou programme),
     * présenté comme passé — le temps restant est celui de ce moment-là, sans compte à rebours.
     * Rien en cours : null.
     */
    fun lastKnown(s: StateWire, ageMs: Long): LastKnown? {
        val session = s.session
        val job = s.job?.takeIf { it.state in ACTIVE }
        if (session == null && job == null) return null
        val title = session?.title ?: job!!.label.ifEmpty { job.kindLabel }
        val step = session?.takeIf { it.stepCount > 0 }?.let { tr("Recette · étape %d sur %d", it.current + 1, it.stepCount) }
        val status = when {
            job == null -> null
            job.state == "PAUSED" -> tr("En pause au dernier contact")
            job.remainingMs >= 1_000 -> tr("Il restait %s au dernier contact", Fmt.duration((job.remainingMs / 1000).toInt()))
            else -> null
        }
        val minutes = (ageMs.coerceAtLeast(0) / 60_000L).toInt()
        val heard = if (minutes < 1) tr("Dernier état reçu il y a moins d'une minute")
            else tr("Dernier état reçu il y a %s", Fmt.duration(minutes * 60))
        return LastKnown(title, step, status, heard)
    }

    private fun connecting(i: LiveInput): LiveView {
        val name = i.link.robotName.ifEmpty { tr("le robot") }
        return LiveView(Route.INCIDENT, IncidentState(Link.CONNECTING, IncidentKind.CONNECTING, tr("Connexion…"),
            tr("Connexion au robot"), tr("Le téléphone cherche %s sur le Wi-Fi de la maison.", name)))
    }

    // ------------------------------------------------------------------ recette guidée

    /** Nom court d'une étape machine : le genre de programme (pas de titre d'étape dans les recettes). */
    fun programName(key: String): String = when (key) {
        "manuel" -> tr("Cuisson manuelle")
        "petrir" -> tr("Pétrir")
        "vapeur" -> tr("Vapeur")
        "rissoler" -> tr("Rissoler")
        "turbo" -> tr("Turbo")
        else -> key
    }

    private fun jobName(job: JobWire) = job.kindLabel.ifEmpty { job.label }

    private fun main(ctx: Ctx, session: SessionWire, job: JobWire): MainState {
        val s = ctx.s
        val step = session.steps.getOrNull(session.current)
        val after = (session.running.maxOrNull() ?: session.current) + 1
        val next = session.steps.getOrNull(after)
        return MainState(
            link = ctx.link,
            recipe = session.title,
            stepIndex = session.current,
            stepCount = session.stepCount,
            // « Cuisson manuelle » ne dit rien dans une recette : le nom seulement s'il nomme un programme.
            action = if (job.kind == "MANUAL") "" else jobName(job),
            remainingMs = ctx.remaining(job),
            totalMs = job.totalMs,
            settings = Fmt.cooking((job.totalMs / 1000).toInt(), job.tempC.takeIf { it > 0 }, job.speed),
            tempC = s.machine.tempC,
            targetC = job.tempC.takeIf { it > 0 },
            speed = job.speed,
            lidClosed = !s.machine.lidOpen,
            lidLocked = !s.machine.lidOpen && s.machine.motorOn,
            instruction = step?.text.orEmpty(),
            nextByHand = next?.takeIf { it.machine == null && it.weigh == null }?.text,
            canAddMinute = ctx.can(Pending.ADJUST, s.can.adjust.allowed),
            canPause = ctx.can(Pending.PAUSE, s.can.pause.allowed),
            canStop = ctx.can(Pending.STOP, s.can.stop.allowed),
            canStopRecipe = ctx.can(Pending.STOP_RECIPE, s.can.stopRecipe.allowed),
            stopRecipeOffered = s.can.stopRecipe.allowed || Pending.STOP_RECIPE in ctx.i.pending,
        )
    }

    private fun pesee(ctx: Ctx, session: SessionWire, step: StepWire): PeseeState {
        val w = step.weigh!!
        // Les pesées faites juste avant (étapes de pesée seule, à la suite), puis celle en cours.
        val before = ArrayList<WeighItem>()
        var k = session.current - 1
        while (k >= 0) {
            val p = session.steps[k]
            val pw = p.weigh
            if (pw == null || p.machine != null || pw.weighedG == null) break
            before.add(0, WeighItem(pw.label, done = true, weightG = pw.weighedG!!))
            k--
        }
        val items = before + WeighItem(w.label, done = false, weightG = ctx.s.machine.weightG, targetG = w.grams)
        // Ensuite : l'étape machine de cette étape (pesée puis cuisson) ou de la suivante.
        val machine: MachineStepWire? = step.machine ?: session.steps.getOrNull(session.current + 1)?.machine
        return PeseeState(
            link = ctx.link,
            recipe = session.title,
            stepIndex = session.current,
            stepCount = session.stepCount,
            instruction = step.text.ifEmpty { tr("Pesée : ajoutez %d g (%s)", w.grams, w.label) },
            weightG = ctx.s.machine.weightG,
            targetG = w.grams,
            items = items,
            nextAction = machine?.let { programName(it.program) },
            nextSettings = machine?.summary.orEmpty(),
        )
    }

    /** Ce que le cuisinier doit faire, sur le robot, quand une étape l'attend (hors pesée). */
    fun waitingLine(waitingFor: String): String? = when (waitingFor) {
        "hand" -> tr("À faire à la main, puis « Suivant » sur le robot.")
        "launch" -> tr("Cette étape se lance sur le robot")
        "hot_blend" -> tr("Contenu chaud : confirmez le mixage sur le robot")
        "finish" -> tr("Dernière étape faite : terminez sur le robot")
        "weigh" -> tr("La pesée se fait sur la balance du robot.")
        "resume" -> tr("La reprise se fait sur le robot.")
        else -> null
    }

    private fun etapes(ctx: Ctx, session: SessionWire, job: JobWire?): EtapesState {
        val done = session.done.toSet()
        val running = session.running.toSet()
        val steps = session.steps.mapIndexed { i, st ->
            // Le robot attend le cuisinier sur cette étape (y compris « terminez » sur la dernière, déjà faite).
            val waiting = if (job == null && i == session.current) waitingLine(session.waitingFor) else null
            val status = when {
                waiting != null -> StepStatus.CURRENT
                i in done && i !in running -> StepStatus.DONE
                i == session.current || i in running -> StepStatus.CURRENT
                else -> StepStatus.TODO
            }
            val m = st.machine
            val w = st.weigh
            val detail = when {
                m != null -> m.summary.ifEmpty { programName(m.program) }
                w != null -> tr("Pesée · %s", Fmt.grams(w.weighedG ?: w.grams))
                else -> tr("À la main")
            }
            val warning = when {
                status == StepStatus.DONE -> null
                st.hotBlendOnRobot -> tr("Contenu chaud : à confirmer sur le robot")
                st.needsReview -> tr("Réglage à vérifier sur le robot")
                else -> null
            }
            val progress = if (job != null && status == StepStatus.CURRENT && (i in running || running.isEmpty())) {
                val rem = ctx.remaining(job)
                StepProgress(if (job.totalMs > 0) 1f - rem.toFloat() / job.totalMs else 0f, rem, ctx.s.machine.tempC)
            } else null
            EtapeItem(st.text, detail, status, warning, progress, waiting)
        }
        val ingredients = ctx.i.recipe?.takeIf { it.id == session.recipeId }?.ingredients.orEmpty().flatMap { g ->
            g.items.map { it ->
                val name = listOf(it.name, it.note).filter { n -> n.isNotBlank() }.joinToString(", ").ifEmpty { it.display }
                IngredientItem(name, listOf(it.qty, it.unit).filter { q -> q.isNotBlank() }.joinToString(" "))
            }
        }
        // Étape arrêtée (ou annulée, ou interrompue par une alerte) encore affichée sur le robot : la
        // barre le dit, avec le même OK. Une étape finie s'efface seule sur le robot : rien ici.
        val stopped = if (job == null) endedJob(ctx.s)?.takeIf { it.state == "ABORTED" } else null
        val now = job?.let { NowBar(jobName(it), ctx.remaining(it), paused = it.state == "PAUSED") }
            ?: stopped?.let { NowBar(jobName(it), 0, end = it.endReason.ifEmpty { tr("Arrêté") }, canOk = ctx.can(Pending.CLEAR, true)) }
        // « Arrêter la recette » : seulement si le robot le sait faire (robot plus ancien : pas de bouton).
        return EtapesState(session.title, session.servings, steps, ingredients, now,
            canStopRecipe = ctx.can(Pending.STOP_RECIPE, ctx.s.can.stopRecipe.allowed),
            stopRecipeOffered = ctx.s.can.stopRecipe.allowed || Pending.STOP_RECIPE in ctx.i.pending)
    }

    // ------------------------------------------------------------------ pauses

    private fun pausedBy(o: Origin?): String? = when {
        o == null || o.self -> null
        o.kind == Origin.PHONE -> tr("Pause depuis %s", o.name.ifEmpty { tr("un autre téléphone") })
        else -> tr("Pause sur le robot")
    }

    private fun paused(ctx: Ctx, job: JobWire, session: SessionWire?): LiveView {
        val s = ctx.s
        val p = job.pause
        val canStop = ctx.can(Pending.STOP, s.can.stop.allowed)
        val remaining = ctx.remaining(job)
        if (s.can.resume.allowed) {
            return LiveView(Route.REPRISE, RepriseState(ctx.link, session?.title, session?.current ?: 0, session?.stepCount ?: 0,
                remaining, ctx.autoCancel(job), canResume = ctx.can(Pending.RESUME, true), pausedBy = pausedBy(p?.origin),
                canStop = canStop))
        }
        val code = s.can.resume.code
        val cause = when {
            code == "bowl_removed" || p?.bowlRemoved == true || s.machine.bowlRemoved -> PauseCause.BOWL
            code == "lid_opened" || code == "lid_opened_during_pause" || p?.reason == "LID" || s.machine.lidOpen -> PauseCause.LID
            else -> PauseCause.ROBOT
        }
        return LiveView(Route.PAUSE, PauseState(ctx.link, session?.title, session?.current ?: 0, session?.stepCount ?: 0, cause,
            remaining, ctx.autoCancel(job), label = p?.label.orEmpty(), reason = s.can.resume.reason, canStop = canStop))
    }

    // ------------------------------------------------------------------ sans recette

    private fun manuel(ctx: Ctx, job: JobWire): ManuelState {
        val s = ctx.s
        // Une saisie faite ici se montre tout de suite, jusqu'à ce que le robot l'ait appliquée.
        val o = ctx.i.manual
        val total = o?.totalMs ?: job.totalMs
        val remaining = if (o != null) (ctx.remaining(job) + o.totalMs - job.totalMs).coerceAtLeast(0) else ctx.remaining(job)
        return ManuelState(
            link = ctx.link,
            startedAt = ctx.clock(job.startedAt),
            bowlC = s.machine.tempC,
            remainingMs = remaining,
            totalMs = total,
            targetC = (o?.tempC ?: job.tempC.takeIf { job.tempLevel > 0 } ?: 0).coerceAtLeast(0),
            speed = o?.speed ?: job.speed,
            reverse = o?.reverse ?: job.reverse,
            maxSpeedHeating = s.limits.maxSpeedHeated,
            // Les appuis s'accumulent pendant qu'une demande est en route : rien n'est grisé pour autant.
            canAdjust = s.can.adjust.allowed,
            canPause = ctx.can(Pending.PAUSE, s.can.pause.allowed),
            canStop = ctx.can(Pending.STOP, s.can.stop.allowed),
            whisk = job.accessory == "WHISK",
        )
    }

    /**
     * Cuisson manuelle préparée sur ce téléphone : réglage local, température du bol, couvercle et
     * bol, « Démarrer » si le robot le permet et si rien ne s'y oppose. Un contenu chaud à vitesse 4
     * ou plus se lance sur le robot (confirmation du mixage à chaud).
     */
    private fun preparation(ctx: Ctx, d0: ManualSettings): ManuelState {
        val s = ctx.s
        val m = s.machine
        val d = d0.copy(limits = s.limits.manual())
        val perm = startPermission(s)
        val v = d.violations(if (m.hasSample) m.tempC else null)
        val hot = v.any { it.code == SafetyPolicy.HOT_BLEND } || (m.hot && d.speed >= 4)
        val other = v.firstOrNull { it.code != SafetyPolicy.HOT_BLEND }
        val (hint, warn) = when {
            !perm.allowed -> perm.reason to true
            other != null -> other.message to true
            hot -> tr("Contenu chaud : à lancer sur le robot") to true
            d.speed == 0 && d.tempLevel > 0 -> tr("Sans mélange : surveillez la cuisson") to false
            else -> "" to false
        }
        return ManuelState(
            link = ctx.link,
            startedAt = "",
            bowlC = m.tempC,
            remainingMs = d.seconds * 1000L,
            totalMs = d.seconds * 1000L,
            targetC = if (d.tempLevel > 0) d.tempC else 0,
            speed = d.speed,
            reverse = d.reverse,
            maxSpeedHeating = s.limits.maxSpeedHeated,
            canAdjust = Pending.START !in ctx.i.pending,
            canPause = false,
            canStop = false,
            preparing = true,
            whisk = d.accessory == Accessory.WHISK,
            lidClosed = !m.lidOpen,
            bowlInPlace = bowlInPlace(s),
            hint = hint,
            hintWarn = warn,
            canStart = perm.allowed && v.isEmpty() && !hot && Pending.START !in ctx.i.pending,
            starting = Pending.START in ctx.i.pending,
            durationNote = if (d.speed > d.limits.highSpeedAbove) "max 15 min" else "max 99 min",
        )
    }

    private fun programKind(job: JobWire): ProgramKind? = when (job.kind) {
        "KNEAD" -> ProgramKind.KNEAD
        "STEAM" -> ProgramKind.STEAM
        "TOAST" -> ProgramKind.TOAST
        "MANUAL" -> ProgramKind.CLEAN
        else -> null
    }

    private fun programme(ctx: Ctx, job: JobWire): ProgrammeState {
        val s = ctx.s
        val tags = ArrayList<String>()
        if (job.tempC > 0) tags += tr("%d °C", job.tempC)
        tags += if (job.speed == 0) tr("Lame à l'arrêt") else tr("vitesse %d", job.speed)
        if (job.reverse) tags += tr("sens inverse")
        val kind = programKind(job)
        return ProgrammeState(
            link = ctx.link,
            startedAt = ctx.clock(job.startedAt),
            name = job.label.ifEmpty { job.kindLabel },
            bowlC = s.machine.tempC,
            remainingMs = ctx.remaining(job),
            totalMs = job.totalMs,
            tags = tags,
            programs = robotPrograms(kind),
            canPause = ctx.can(Pending.PAUSE, s.can.pause.allowed),
            canStop = ctx.can(Pending.STOP, s.can.stop.allowed),
        )
    }

    /** Les programmes du robot, décrits d'après leurs définitions (core, Programs). */
    fun robotPrograms(running: ProgramKind?): List<ProgramItem> {
        val knead = Programs.knead()
        val steam = Programs.steam()
        val toast = Programs.toast()
        val clean = Programs.clean()
        fun sec(ms: Long) = (ms / 1000).toInt()
        val cleanSpeeds = clean.phases.map { it.setting.speed }
        return listOf(
            ProgramItem(ProgramKind.KNEAD, tr("Pétrir"),
                tr("%s · sens alternés, vitesse %d", Fmt.duration(sec(knead.totalMs)), knead.phases.maxOf { it.setting.speed }),
                running == ProgramKind.KNEAD),
            ProgramItem(ProgramKind.STEAM, tr("Vapeur"),
                tr("%s · %d °C, lame à l'arrêt", Fmt.duration(sec(steam.totalMs)), steam.phases[0].setting.temperatureC),
                running == ProgramKind.STEAM),
            ProgramItem(ProgramKind.TOAST, tr("Rissoler"),
                tr("%s · %d °C, sens inverse", Fmt.duration(sec(toast.totalMs)), toast.phases[0].setting.temperatureC),
                running == ProgramKind.TOAST),
            ProgramItem(ProgramKind.CLEAN, tr("Nettoyage"),
                tr("%s · vitesse %d à %d", Fmt.duration(sec(clean.totalMs)), cleanSpeeds.minOrNull() ?: 0, cleanSpeeds.maxOrNull() ?: 0),
                running == ProgramKind.CLEAN),
        )
    }

    // ------------------------------------------------------------------ fin

    /** Le programme terminé ou arrêté, comme la barre machine du robot le montre jusqu'à son OK. */
    private fun fin(ctx: Ctx, job: JobWire): FinState {
        val finished = job.state == "FINISHED"
        val total = (job.totalMs / 1000).toInt()
        val elapsed = (job.elapsedMs.coerceIn(0, job.totalMs.coerceAtLeast(0)) / 1000).toInt()
        val detail = when {
            total <= 0 -> ""
            finished -> tr("%s au total", Fmt.duration(total))
            else -> tr("Après %s sur %s", Fmt.duration(elapsed), Fmt.duration(total))
        }
        // Réglages d'une cuisson manuelle ; un programme a les siens, connus par son nom.
        val settings = if (!isManual(job)) "" else listOfNotNull(
            if (job.tempLevel > 0) tr("%d °C", job.tempC) else null,
            if (job.speed == 0) tr("sans mélange") else tr("vitesse %d", job.speed),
            if (job.reverse && job.speed > 0) tr("sens inverse") else null,
        ).joinToString(" · ")
        return FinState(
            link = ctx.link,
            name = job.label.ifEmpty { job.kindLabel },
            finished = finished,
            end = if (finished) tr("Terminé") else job.endReason.ifEmpty { tr("Arrêté") },
            detail = detail,
            settings = settings,
            progress = if (finished || job.totalMs <= 0) 1f else job.elapsedMs.toFloat() / job.totalMs,
            bowlC = ctx.s.machine.tempC,
            hot = ctx.s.machine.hot,
            canOk = ctx.can(Pending.CLEAR, true),
        )
    }

    // ------------------------------------------------------------------ repos

    private fun last(ctx: Ctx, a: ActivityWire): LastActivity {
        val at = ctx.clock(a.endedAt)
        val took = if (a.endedAt > a.startedAt && a.startedAt > 0) Fmt.duration(((a.endedAt - a.startedAt) / 1000).toInt()) else ""
        val detail = when (a.outcome) {
            "finished" -> when {
                a.kind != "recipe" && took.isEmpty() -> tr("Terminé à %s", at)
                a.kind != "recipe" -> tr("Terminé à %s · %s", at, took)
                took.isEmpty() -> tr("Recette terminée à %s", at)
                else -> tr("Recette terminée à %s · %s", at, took)
            }
            "stopped" -> if (took.isEmpty()) tr("Arrêté à %s", at) else tr("Arrêté à %s · %s", at, took)
            "cancelled" -> tr("Annulé à %s : pause trop longue", at)
            "fault" -> tr("Interrompu par une alerte à %s", at)
            "abandoned" -> tr("Recette quittée à %s", at)
            else -> a.summary
        }
        return LastActivity(a.title, detail)
    }
}
