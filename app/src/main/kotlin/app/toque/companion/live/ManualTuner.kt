package app.toque.companion.live

import app.toque.companion.link.CommandOutcome
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.JobWire
import app.toque.core.companion.StateWire
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualSettings

/** Réglage montré à la place de celui du robot, le temps qu'il l'applique. */
data class ManualOverride(val speed: Int, val tempC: Int, val reverse: Boolean, val totalMs: Long)

/**
 * Réglages de la cuisson manuelle en cours saisis sur le téléphone, comme sur l'écran du robot :
 * chaque appui se voit tout de suite, une seule demande part [DEBOUNCE_MS] après le dernier, avec
 * les valeurs finales (vitesse, température, sens) et la somme des durées ajoutées. Les appuis
 * faits pendant qu'une demande est en route partent après sa réponse. L'écran reprend les valeurs
 * du robot une fois la réponse reçue et l'état de la révision annoncée arrivé, quand il montre le
 * réglage envoyé (ou au plus tard [GRACE_MS] après la réponse) ; sur un refus, aussitôt, avec le
 * message du robot. Mêmes règles de saisie que le robot ([ManualSettings], bornes de state.limits).
 * Kotlin pur, fil principal.
 */
class ManualTuner(
    private val send: (AdjustRequest, (CommandOutcome) -> Unit) -> Unit,
    /** Lance [block] après [ms] ; renvoie de quoi l'annuler. */
    private val schedule: (ms: Long, block: () -> Unit) -> () -> Unit,
    private val mono: () -> Long,
    private val notice: (String) -> Unit,
    /** L'affichage a changé : rendre de nouveau. */
    private val changed: () -> Unit,
) {
    private var state: StateWire? = null
    /** Cuisson à laquelle s'appliquent les saisies (un réglage ne passe pas d'une cuisson à l'autre). */
    private var jobKey: String? = null
    private var local: ManualOverride? = null

    // Saisies pas encore envoyées.
    private var dirtySpeed = false
    private var dirtyTemp = false
    private var dirtyDir = false
    private var addMs = 0L
    private var cancelTimer: (() -> Unit)? = null

    // Demande en route, puis attente de l'état qui la montre.
    private var inFlight = false
    private var awaitRevision = Long.MAX_VALUE
    private var answeredAt = 0L
    private var sent: AdjustRequest? = null
    private var sentTotal = 0L

    /** Réglage à montrer à la place de celui du robot, ou null : suivre le robot. */
    val override: ManualOverride? get() = local

    /** Une saisie est en cours (pas encore appliquée par le robot). */
    val busy: Boolean get() = local != null

    private fun key(j: JobWire) = "${j.kind}|${j.label}|${j.startedAt}"

    private fun adjustable(s: StateWire?): JobWire? {
        val j = s?.job ?: return null
        // Une cuisson manuelle seulement : un yaourt est aussi MANUAL pour la base, mais il ne se règle pas d'ici.
        if (!LivePresenter.isManual(j) || j.state !in setOf("STARTING", "RUNNING", "PAUSED")) return null
        return j
    }

    fun onState(s: StateWire) {
        state = s
        val j = adjustable(s)
        if (j == null || (jobKey != null && jobKey != key(j))) {
            reset()
            return
        }
        settle()
    }

    /** Toutes les secondes : le délai de grâce peut avoir expiré. */
    fun tick() = settle()

    private fun settle() {
        if (local == null || inFlight || dirty()) return
        val s = state ?: return
        if (s.revision < awaitRevision) return
        val j = adjustable(s) ?: return reset()
        val req = sent
        val shown = req == null || ((req.speed == null || req.speed == j.speed) && (req.tempC == null || req.tempC == j.tempC.takeIf { j.tempLevel > 0 } ?: 0) &&
            (req.reverse == null || req.reverse == j.reverse) && (req.addMs == null || j.totalMs == sentTotal))
        if (shown || mono() - answeredAt >= GRACE_MS) {
            local = null
            sent = null
            awaitRevision = Long.MAX_VALUE
            changed()
        }
    }

    private fun dirty() = dirtySpeed || dirtyTemp || dirtyDir || addMs != 0L

    private fun reset() {
        val had = local != null
        cancelTimer?.invoke()
        cancelTimer = null
        local = null
        jobKey = null
        dirtySpeed = false
        dirtyTemp = false
        dirtyDir = false
        addMs = 0L
        sent = null
        awaitRevision = Long.MAX_VALUE
        if (had) changed()
    }

    /** Valeurs de départ d'une saisie : celles déjà montrées, sinon celles du robot. */
    private fun current(j: JobWire): ManualOverride =
        local ?: ManualOverride(j.speed, if (j.tempLevel > 0) j.tempC else 0, j.reverse, j.totalMs)

    private fun settings(s: StateWire, j: JobWire, o: ManualOverride): ManualSettings {
        val acc = if (j.accessory == "WHISK") Accessory.WHISK else Accessory.BLADE
        // Une température du robot hors de la table envoyée au téléphone (40 °C du Smart) reste un cran : « − » mène
        // à 37 °C, pas à « sans chauffe ».
        val lim = s.limits.manual().let { l -> if (o.tempC > 0 && o.tempC !in l.temperaturesC) l.copy(temperaturesC = (l.temperaturesC + o.tempC).sorted()) else l }
        val base = ManualSettings(60, 0, o.speed, o.reverse, acc, lim)
        // withCelsius : niveau de Toque, et celui du Smart pour 40 °C (lancé sur son écran) ; pas « sans chauffe ».
        return if (o.tempC == 0) base else base.withCelsius(o.tempC)
    }

    /** Durée totale, d'une minute de plus ([dir] = 1) ou de moins (−1). */
    fun time(dir: Int) {
        val s = state ?: return
        val j = adjustable(s) ?: return
        val o = current(j)
        val lim = s.limits.manual()
        val max = (if (o.speed > lim.highSpeedAbove) lim.maxSecondsHighSpeed else lim.maxSeconds) * 1000L
        // Il reste au moins la durée minimale de l'écran du robot.
        val min = j.elapsedMs + lim.minSeconds * 1000L
        val next = (o.totalMs + dir * 60_000L).coerceIn(minOf(min, o.totalMs), maxOf(max, o.totalMs))
        if (next == o.totalMs) return
        addMs += next - o.totalMs
        apply(j, o.copy(totalMs = next))
    }

    /** Température ([card] = 1) ou vitesse (2), d'un cran, avec les corrections du robot. */
    fun step(card: Int, dir: Int) {
        val s = state ?: return
        val j = adjustable(s) ?: return
        val o = current(j)
        val e = settings(s, j, o).change(card, dir)
        for (m in e.messages) notice(m)
        val n = o.copy(speed = e.settings.speed, tempC = if (e.settings.tempLevel > 0) e.settings.tempC else 0)
        if (n.speed != o.speed) dirtySpeed = true
        if (n.tempC != o.tempC) dirtyTemp = true
        if (n == o) return
        apply(j, n)
    }

    fun reverse(r: Boolean) {
        val s = state ?: return
        val j = adjustable(s) ?: return
        val o = current(j)
        val e = settings(s, j, o).withReverse(r) ?: return
        for (m in e.messages) notice(m)
        val n = o.copy(speed = e.settings.speed, reverse = e.settings.reverse)
        if (n.speed != o.speed) dirtySpeed = true
        if (n.reverse != o.reverse) dirtyDir = true
        if (n == o) return
        apply(j, n)
    }

    private fun apply(j: JobWire, n: ManualOverride) {
        jobKey = key(j)
        local = n
        cancelTimer?.invoke()
        cancelTimer = schedule(DEBOUNCE_MS) { cancelTimer = null; flush() }
        changed()
    }

    private fun flush() {
        if (inFlight || !dirty()) return
        val o = local ?: return
        val req = AdjustRequest(speed = o.speed.takeIf { dirtySpeed }, tempC = o.tempC.takeIf { dirtyTemp },
            addMs = addMs.takeIf { it != 0L }, reverse = o.reverse.takeIf { dirtyDir })
        dirtySpeed = false
        dirtyTemp = false
        dirtyDir = false
        addMs = 0L
        sent = req
        sentTotal = o.totalMs
        inFlight = true
        val forJob = jobKey
        send(req) { out ->
            inFlight = false
            if (forJob != jobKey) return@send
            if (!out.ok) {
                // Refus : les valeurs du robot, et son message ; les appuis faits entre-temps partaient de ce réglage refusé.
                reset()
                notice(out.message)
                changed()
                return@send
            }
            awaitRevision = out.revision
            answeredAt = mono()
            if (dirty()) flush() else settle()
            changed()
        }
    }

    companion object {
        /** Comme l'écran du robot : la demande part après le dernier appui. */
        const val DEBOUNCE_MS = 350L
        /** Après la réponse, l'écran attend au plus ce temps l'état qui montre le réglage envoyé. */
        const val GRACE_MS = 2_000L
    }
}
