package app.toque.companion

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.live.LiveInput
import app.toque.companion.live.Pending
import app.toque.core.companion.ActivityWire
import app.toque.core.companion.JobWire
import app.toque.core.companion.LimitsWire
import app.toque.core.companion.MachineStepWire
import app.toque.core.companion.MachineWire
import app.toque.core.companion.Origin
import app.toque.core.companion.PauseWire
import app.toque.core.companion.Permission
import app.toque.core.companion.Permissions
import app.toque.core.companion.RobotWire
import app.toque.core.companion.SessionWire
import app.toque.core.companion.StateWire
import app.toque.core.companion.StepWire
import app.toque.core.companion.WaitingWire
import app.toque.core.companion.WeighWire
import app.toque.core.json.Json
import java.util.TimeZone

/**
 * États du robot tels qu'ils voyagent (StateWire), pour les tests du présentateur et les captures.
 * Chaque état passe par le JSON aller-retour ([wire]) : ce sont de vraies données du fil.
 */
object WireFixtures {
    /** 12:00:00 UTC, heure du robot à la réponse. */
    const val T = 1_795_262_400_000L
    val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    val ready = MachineWire(link = "READY", linkLabel = "Base prête", ready = true, hasSample = true, tempC = 22,
        bowlDetected = true, scaleStable = true)
    val yes = Permission(true)
    fun no(code: String, reason: String) = Permission(false, code, reason)

    val limits = LimitsWire(temperaturesC = listOf(37, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100, 105, 110, 115, 120, 125, 130))

    /** Le velouté de la démo : textes de la recette, rien d'inventé côté téléphone. */
    val steps = listOf(
        StepWire(0, "Couper le potimarron et l'oignon en dés."),
        StepWire(1, "Mettre le beurre dans le bol.", weigh = WeighWire("Beurre", 20)),
        StepWire(2, "Faire revenir l'oignon.", MachineStepWire("manuel", 180, 1, 100, summary = "3 min · 100 °C · vitesse 1")),
        StepWire(3, "Ajouter le potimarron et l'eau.", weigh = WeighWire("Eau", 500)),
        StepWire(4, "Laisser cuire jusqu'à ce que le potimarron soit tendre.",
            MachineStepWire("manuel", 1200, 1, 100, summary = "20 min · 100 °C · vitesse 1")),
        StepWire(5, "Ajouter la crème, puis mixer.", MachineStepWire("manuel", 60, 8, 0, summary = "1 min · vitesse 8"),
            hotBlendOnRobot = true),
    )

    fun session(current: Int, waitingFor: String, done: List<Int> = (0 until current).toList(), running: List<Int> = emptyList(),
                steps: List<StepWire> = WireFixtures.steps) =
        SessionWire("perso-veloute-de-potimarron", "Velouté de potimarron", 4, steps.size, current, done, running, waitingFor,
            T - 30 * 60_000L, steps)

    fun job(kind: String = "MANUAL", state: String = "RUNNING", remainingMs: Long = 760_000, totalMs: Long = 1_200_000,
            speed: Int = 1, tempC: Int = 100, label: String = "Cuisson manuelle", kindLabel: String = "Cuisson manuelle",
            phaseCount: Int = 1, reverse: Boolean = false, pause: PauseWire? = null, startedAt: Long = T - 440_000,
            endReason: String = "", accessory: String = "BLADE") =
        JobWire(kind, kindLabel, label, state, counting = state == "RUNNING", endReason = endReason, phaseIndex = 0, phaseCount = phaseCount,
            elapsedMs = totalMs - remainingMs, totalMs = totalMs, remainingMs = remainingMs, speed = speed,
            tempLevel = if (tempC > 0) limits.temperaturesC.indexOf(tempC) + 1 else 0, tempC = tempC, reverse = reverse,
            accessory = accessory, startedAt = startedAt, pause = pause)

    fun paused(job: JobWire, reason: String = "USER", origin: Origin = Origin(Origin.PHONE, "1eeddd36", "Téléphone 1", self = true),
               lidOpened: Boolean = false, bowl: Boolean = false, label: String = "En pause") =
        job.copy(state = "PAUSED", counting = false, pause = PauseWire(reason, label, T - 48_000, T + 552_000, 552_000,
            origin, lidOpened, bowl))

    /** Un robot d'avant le « OK » depuis le téléphone : pas de can.clear (lu comme refusé, sans code). */
    fun olderRobot(p: Permissions): Permissions = p.copy(clear = Permission(false))

    /** Permissions habituelles : ce que le robot dirait pour [job]. */
    fun can(job: JobWire?, resume: Permission = no("not_paused", "Le programme n'est pas en pause"),
            waiting: Boolean = false, recipe: Boolean = false): Permissions {
        val active = job != null && job.state in setOf("STARTING", "RUNNING", "STOPPING", "PAUSED")
        val manual = active && job!!.kind == "MANUAL" && job.phaseCount == 1
        return Permissions(
            pause = if (active && job!!.state != "PAUSED") yes else no("nothing_running", "Aucun programme en cours"),
            resume = resume,
            stop = if (active) yes else no("nothing_running", "Aucun programme en cours"),
            adjust = if (manual) yes else no("fixed", "Réglages fixes pour ce programme"),
            tare = yes, sendRecipe = yes,
            removeWaiting = if (waiting) yes else no("no_waiting", "Aucune recette en attente"),
            start = if (!active) yes else no("busy", "Un programme est déjà en cours sur le robot"),
            clear = if (job != null && job.state in setOf("FINISHED", "ABORTED")) yes else no("nothing_ended", "Aucun programme terminé à fermer"),
            favorite = yes,
            stopRecipe = if (recipe) yes else no("no_recipe", "Aucune recette en cours sur le robot"),
        )
    }

    fun state(machine: MachineWire = ready, job: JobWire? = null, session: SessionWire? = null, can: Permissions? = null,
              waiting: WaitingWire? = null, last: ActivityWire? = null, journalSeq: Long = 17, revision: Long = 42) = wire(
        StateWire(revision = revision, serverTime = T, robot = RobotWire("a1b2c3d4e5f6", "Monsieur Cuisine Connect", "0.7.0", "fr"),
            machine = machine, job = job, can = can ?: can(job, waiting = waiting != null, recipe = session != null), session = session, waiting = waiting,
            last = last, journalSeq = journalSeq, limits = limits))

    /** Aller-retour par le JSON, comme sur le fil. */
    fun wire(s: StateWire): StateWire {
        @Suppress("UNCHECKED_CAST")
        return StateWire.fromMap(Json.parse(Json.write(s.toMap())) as Map<String, Any?>)
    }

    val connected = LinkStatus(LinkPhase.CONNECTED, "Monsieur Cuisine Connect")

    fun input(s: StateWire?, link: LinkStatus = connected, ageMs: Long = 0, offlineForMs: Long = 0,
              pending: Set<Pending> = emptySet()) =
        LiveInput(link, s, ageMs, offlineForMs, pending, nowWall = T + ageMs, zone = UTC)
}
