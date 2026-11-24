package app.toque.companion.flows

/**
 * File des téléchargements de photos depuis le robot. Kotlin pur, un seul fil (le principal).
 *
 * - Urgentes : les photos qu'une vue attend ([want]), la plus récente demande d'abord (après un
 *   défilement rapide, les cartes à l'écran passent avant celles déjà parties).
 * - Basses, seulement si [active] (appli à l'écran) : les demandes abandonnées ([Ticket.cancel] :
 *   carte recyclée ou détachée), gardées car l'utilisateur reviendra sans doute, et les photos
 *   anciennes à vérifier ([refresh]), la plus récente d'abord ; puis le préchargement d'une page
 *   du catalogue ([prefetch]), dans l'ordre de la liste.
 * - Un seul téléchargement par photo, quel que soit le nombre de vues qui l'attendent ; au plus
 *   [maxParallel] à la fois (la liaison a 3 fils de lecture : un reste libre pour les pages du
 *   catalogue et la fiche).
 * - Rien ne part tant que la liaison n'est pas connectée ([online]) : chaque essai coûterait un délai
 *   de connexion, et la carte resterait sans photo. Tout attend et repart à la reconnexion.
 * - Un échec, liaison connectée, est réessayé [attempts] − 1 fois, à [retryDelayMs] d'intervalle,
 *   sans occuper de place pendant l'attente ; puis [Fetch.FAILED], pas retenu (la prochaine
 *   demande réessaie). Un échec pendant que la liaison est coupée ne compte pas : la photo attend.
 *   Un 404 est retenu ([isMissing]).
 *
 * [start] lance le téléchargement de la photo (même si elle est sur le disque : vérification
 * d'une photo ancienne) et appelle `done` une fois, sur ce même fil, à la fin.
 */
class PhotoQueue(
    private val maxParallel: Int = 2,
    /** Attente avant un nouvel essai (fil principal, comme les flux). */
    private val schedule: Scheduler,
    private val attempts: Int = 3,
    private val retryDelayMs: Long = 1_500,
    private val start: (id: String, done: (Fetch) -> Unit) -> Unit,
) {
    enum class Fetch {
        /** Sur le disque. */
        STORED,
        /** Téléchargée mais pas gardée (disque plein, erreur d'écriture) : en mémoire seulement, rien de retenu. */
        UNSTORED,
        /** Le robot n'a pas de photo pour cette recette (404). */
        MISSING,
        /** Réseau, robot injoignable… : à réessayer à la prochaine demande. */
        FAILED,
    }

    private class Entry(val id: String) {
        val askers = ArrayList<Ticket>()
        /** Dernière demande urgente (plus grand : d'abord). */
        var urgentSeq = 0L
        /** File basse (abandonnée ou à vérifier) : > 0, plus grand d'abord. */
        var lowSeq = 0L
        /** Préchargement : > 0, plus petit d'abord (ordre de la liste). */
        var prefetchSeq = 0L
        var refresh = false
        var running = false
        /** Essais ratés de suite, liaison connectée. */
        var failures = 0
        /** Attend son prochain essai (après un échec) : pas lancée avant. */
        var retrying = false
    }

    /** Une vue qui attend la photo [id] ; [cancel] quand elle n'en veut plus. */
    inner class Ticket internal constructor(val id: String, private val onDone: (Fetch) -> Unit) {
        internal var live = true

        /** La vue n'attend plus : la photo quitte la file urgente (gardée en file basse si plus personne ne l'attend). */
        fun cancel() {
            if (!live) return
            live = false
            val e = entries[id] ?: return
            e.askers.remove(this)
            if (e.askers.isEmpty() && !e.running) e.lowSeq = ++seq
        }

        internal fun deliver(f: Fetch) {
            if (!live) return
            live = false
            onDone(f)
        }
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val missing = HashSet<String>()
    private var seq = 0L
    private var prefetchCounter = 0L
    /** Numéro de [reset] : la fin d'un téléchargement d'avant ne prévient personne. */
    private var generation = 0L

    /** Téléchargements en cours (y compris ceux d'avant un [reset], qui occupent encore la liaison). */
    var running = 0
        private set

    /** Appli à l'écran : la file basse et le préchargement avancent. Sinon seules les urgentes partent. */
    var active = true
        set(v) {
            field = v
            if (v) pump()
        }

    /** Liaison connectée : les téléchargements partent. Sinon tout attend, urgentes comprises. */
    var online = true
        set(v) {
            field = v
            if (v) pump()
        }

    fun isMissing(id: String) = id in missing

    /**
     * Une vue attend la photo [id]. [onDone] est appelé une fois à la fin du téléchargement (tout de
     * suite avec [Fetch.MISSING] si le robot a déjà dit qu'il n'en a pas), jamais après [Ticket.cancel].
     */
    fun want(id: String, onDone: (Fetch) -> Unit): Ticket {
        val t = Ticket(id, onDone)
        if (id in missing) {
            t.deliver(Fetch.MISSING)
            return t
        }
        val e = entries.getOrPut(id) { Entry(id) }
        e.askers += t
        e.urgentSeq = ++seq
        pump()
        return t
    }

    /** Photo voulue plus tard, sans vue qui l'attende (demande abandonnée avant d'arriver ici). */
    fun later(id: String) {
        if (id in missing) return
        val e = entries.getOrPut(id) { Entry(id) }
        if (!e.running && e.askers.isEmpty()) e.lowSeq = ++seq
        pump()
    }

    /** Photos d'une page du catalogue, pas encore sur le disque : en fond, après tout le reste. */
    fun prefetch(ids: List<String>) {
        for (id in ids) {
            if (id in missing) continue
            val e = entries.getOrPut(id) { Entry(id) }
            if (e.prefetchSeq == 0L && e.lowSeq == 0L && e.askers.isEmpty() && !e.running) e.prefetchSeq = ++prefetchCounter
        }
        pump()
    }

    /** Photo sur le disque depuis longtemps : la télécharger de nouveau, en file basse. */
    fun refresh(id: String) {
        if (id in missing) return
        val e = entries.getOrPut(id) { Entry(id) }
        if (e.running) return
        e.refresh = true
        if (e.askers.isEmpty()) e.lowSeq = ++seq
        pump()
    }

    /** Nouvelle recherche ou nouveau filtre : ce qui attendait en fond pour l'ancienne liste est oublié (pas les vérifications). */
    fun newList() {
        entries.values.removeAll { !it.running && it.askers.isEmpty() && !it.refresh }
    }

    /** Autre robot, ou plus de robot : tout est oublié, y compris les 404. */
    fun reset() {
        generation++
        entries.clear()
        missing.clear()
    }

    /** Photos en attente (pas encore lancées), pour les tests. */
    fun waiting(): List<String> = entries.values.filter { !it.running }.map { it.id }

    private fun ready(e: Entry) = !e.running && !e.retrying

    private fun next(): Entry? {
        var best: Entry? = null
        for (e in entries.values) if (ready(e) && e.askers.isNotEmpty() && (best == null || e.urgentSeq > best.urgentSeq)) best = e
        if (best != null || !active) return best
        for (e in entries.values) if (ready(e) && e.askers.isEmpty() && e.lowSeq > 0 && (best == null || e.lowSeq > best.lowSeq)) best = e
        if (best != null) return best
        for (e in entries.values) if (ready(e) && e.prefetchSeq > 0 && (best == null || e.prefetchSeq < best.prefetchSeq)) best = e
        return best
    }

    private fun pump() {
        while (online && running < maxParallel) {
            val e = next() ?: return
            e.running = true
            running++
            val gen = generation
            var finished = false
            start(e.id) { f ->
                if (!finished) {
                    finished = true
                    finish(e, gen, f)
                }
            }
        }
    }

    private fun finish(e: Entry, gen: Long, f: Fetch) {
        running--
        if (gen == generation && f == Fetch.FAILED && entries[e.id] === e) {
            e.running = false
            // Liaison coupée entre-temps : la photo attend la reconnexion, sans compter d'essai.
            if (!online) return
            if (++e.failures < attempts) {
                e.retrying = true
                schedule(retryDelayMs, Runnable {
                    if (gen != generation || entries[e.id] !== e) return@Runnable
                    e.retrying = false
                    pump()
                })
                pump()
                return
            }
        }
        if (gen == generation) {
            if (entries[e.id] === e) entries.remove(e.id)
            if (f == Fetch.MISSING) missing += e.id
            for (t in e.askers.toList()) t.deliver(f)
        }
        pump()
    }
}
