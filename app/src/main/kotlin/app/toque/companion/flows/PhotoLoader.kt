package app.toque.companion.flows

import app.toque.companion.link.LinkPhase
import app.toque.companion.link.LinkStatus
import app.toque.companion.link.RobotLink
import app.toque.companion.ui.screens.RecipeCard
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Le chemin d'une photo de recette, sans Android (le type d'image [B] et son décodage sont donnés) :
 * mémoire → disque ([PhotoDisk]) → robot ([PhotoQueue], [RobotLink.request]).
 *
 * - Mémoire : images à la taille affichée, plafond en octets, la moins récemment utilisée sort.
 *   Trouvée en mémoire, la photo est rendue pendant [load] (pas de clignotement quand une carte
 *   est redessinée). Une image plus petite en mémoire est rendue tout de suite, puis la bonne.
 * - Disque et décodage sur [work] (un petit groupe de fils à lui, jamais ceux de la liaison) ;
 *   une photo sur le disque ne fait jamais de requête, sauf si elle a plus de [maxAgeMs] : elle
 *   est montrée tout de suite et téléchargée de nouveau en fond.
 * - Un seul fichier pour toutes les tailles (carte, fiche, Envoyée) : chacune le décode à sa largeur.
 * - Un autre robot, ou plus de robot : mémoire, file et disque de l'ancien sont vidés.
 * - Liaison pas connectée : mémoire et disque servent encore ; les téléchargements attendent la
 *   reconnexion, et les vues qui attendent reçoivent alors leur photo.
 * - Disque plein ou en erreur : la photo téléchargée est quand même montrée (en mémoire seulement).
 *
 * [load], [catalogShown], [setVisible], [trimMemory] : sur le fil principal ([post] y ramène).
 */
class PhotoLoader<B : Any>(
    private val link: RobotLink,
    private val disk: PhotoDisk,
    private val post: (Runnable) -> Unit,
    private val work: Executor,
    /** Attente avant un nouvel essai de téléchargement, sur le fil principal. */
    schedule: Scheduler,
    /** Décode [ByteArray] à environ cette largeur en pixels (jamais plus que l'original) ; null si illisible. */
    private val decode: (ByteArray, Int) -> B?,
    private val sizeOf: (B) -> Int,
    memoryBytes: Int,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxAgeMs: Long = 30L * 24 * 3600 * 1000,
    parallel: Int = 2,
) : RobotLink.Listener {
    /** Ce qu'une vue garde pour abandonner sa demande (carte recyclée, vue détachée). */
    fun interface Request {
        fun cancel()
    }

    private val memory = Memory<B>(memoryBytes, sizeOf)
    // Une vérification (photo ancienne) se télécharge comme les autres : PhotoDisk.write compare les octets.
    private val queue = PhotoQueue(parallel, schedule) { id, done -> download(id, done) }.also {
        it.online = link.status.phase == LinkPhase.CONNECTED
    }
    /**
     * Octets téléchargés que le disque n'a pas pu garder, le temps de les remettre aux vues qui
     * attendent (pendant l'appel de fin de la file, sur le fil principal).
     */
    private val unstored = HashMap<String, ByteArray>()
    /** Robot dont la mémoire et la file sont pleines ; [UNSET] tant que rien n'est vu. */
    private var robot: Any? = UNSET
    /** Identifiants (avec photo) de la liste du catalogue déjà vue, pour reconnaître une page de plus. */
    private var shownIds: List<String> = emptyList()
    private var shownItems: List<RecipeCard>? = null
    private val unsubscribe = link.addListener(this)

    private fun robotId(): String? = link.paired?.robotId

    /** Le robot a changé (ou n'est plus appairé) : tout ce qui vient de l'ancien est oublié. */
    private fun checkRobot() {
        val r = robotId()
        if (r == robot) return
        robot = r
        memory.clear()
        queue.reset()
        shownIds = emptyList()
        shownItems = null
        background { disk.select(robotId()) }
    }

    /**
     * Appairage, oubli, retrait sur le robot : `paired` a déjà changé, les photos de l'ancien robot
     * partent. Les téléchargements n'avancent que liaison connectée.
     */
    override fun onStatus(status: LinkStatus) {
        checkRobot()
        queue.online = status.phase == LinkPhase.CONNECTED
    }

    /**
     * La photo de [id] à environ [widthPx] pixels de large. [onLoaded] : pendant cet appel si elle
     * est en mémoire, sinon plus tard sur le fil principal (null : pas de photo, ou robot injoignable).
     */
    fun load(id: String, widthPx: Int, onLoaded: (B?) -> Unit): Request {
        checkRobot()
        val w = bucket(widthPx)
        val held = memory.best(id, w)
        if (held != null && held.second >= w) {
            onLoaded(held.first)
            return DONE
        }
        if (held != null) onLoaded(held.first)
        if (queue.isMissing(id)) {
            if (held == null) onLoaded(null)
            return DONE
        }
        val r = Pending(id, w, onLoaded, shown = held != null)
        background { fromDisk(r, mayDownload = true) }
        return r
    }

    private inner class Pending(val id: String, val width: Int, val onLoaded: (B?) -> Unit, var shown: Boolean) : Request {
        @Volatile var cancelled = false
        var ticket: PhotoQueue.Ticket? = null
        var done = false

        override fun cancel() {
            if (cancelled || done) return
            cancelled = true
            ticket?.cancel()
        }

        fun deliver(b: B?) {
            if (cancelled || done) return
            done = true
            if (b == null && shown) return
            onLoaded(b)
        }
    }

    /** Sur [work] : lit le disque et décode ; absente, la demande passe à la file (urgente). */
    private fun fromDisk(r: Pending, mayDownload: Boolean) {
        val rid = robotId()
        disk.select(rid)
        if (r.cancelled) {
            // Abandonnée avant d'arriver : sans décoder ; téléchargée plus tard si elle n'est pas sur le disque.
            if (mayDownload && !disk.has(rid, r.id)) post(Runnable { if (rid == robot) queue.later(r.id) })
            return
        }
        val photo = disk.read(rid, r.id)
        val img = photo?.let { decode(it.bytes, r.width) }
        if (photo != null && img == null) disk.remove(rid, r.id)
        post(Runnable {
            if (rid != robot) return@Runnable
            if (img != null) {
                memory.put(r.id, r.width, img)
                r.deliver(img)
                if (now() - photo!!.savedAt > maxAgeMs) queue.refresh(r.id)
            } else if (!mayDownload) r.deliver(null)
            else if (r.cancelled) queue.later(r.id)
            else r.ticket = queue.want(r.id) { f ->
                when (f) {
                    PhotoQueue.Fetch.STORED -> background { fromDisk(r, mayDownload = false) }
                    PhotoQueue.Fetch.UNSTORED -> unstored[r.id]?.let { fromBytes(r, it, rid) } ?: r.deliver(null)
                    else -> r.deliver(null)
                }
            }
        })
    }

    /** Sur [work] : décode les octets téléchargés que le disque n'a pas gardés ; en mémoire seulement. */
    private fun fromBytes(r: Pending, bytes: ByteArray, rid: String?) = background {
        val img = if (r.cancelled) null else decode(bytes, r.width)
        post(Runnable {
            if (rid != robot) return@Runnable
            if (img != null) memory.put(r.id, r.width, img)
            r.deliver(img)
        })
    }

    /** Lancé par la file : télécharge sur la liaison, puis vérifie et écrit sur [work]. */
    private fun download(id: String, done: (PhotoQueue.Fetch) -> Unit) {
        val rid = robotId()
        link.request({ c -> c.image(id) }) { res ->
            val bytes = res.getOrNull()
            when {
                res.isFailure -> done(PhotoQueue.Fetch.FAILED)
                bytes == null -> {
                    // 404 : la recette n'a plus de photo, l'ancienne est effacée partout.
                    memory.remove(id)
                    background { disk.remove(rid, id) }
                    done(PhotoQueue.Fetch.MISSING)
                }
                else -> background {
                    disk.select(robotId())
                    val w = disk.write(rid, id, bytes)
                    post(Runnable {
                        when (w) {
                            // Des octets illisibles ne seront pas meilleurs au prochain essai : comme un 404.
                            PhotoDisk.Written.NOT_IMAGE -> done(PhotoQueue.Fetch.MISSING)
                            // Disque plein ou en erreur : montrée quand même, rien de retenu (la prochaine demande retélécharge).
                            PhotoDisk.Written.FAILED -> {
                                unstored[id] = bytes
                                try {
                                    done(PhotoQueue.Fetch.UNSTORED)
                                } finally {
                                    unstored.remove(id)
                                }
                            }
                            else -> {
                                if (w == PhotoDisk.Written.CHANGED) memory.remove(id)
                                done(PhotoQueue.Fetch.STORED)
                            }
                        }
                    })
                }
            }
        }
    }

    /**
     * Le catalogue affiché a changé. Une page de plus : ses photos absentes du disque sont
     * téléchargées en fond (disque seulement, rien n'est décodé). Une autre liste (recherche,
     * filtre) : le préchargement de l'ancienne est abandonné.
     */
    fun catalogShown(items: List<RecipeCard>) {
        if (items === shownItems) return
        checkRobot()
        shownItems = items
        val ids = items.filter { it.picture }.map { it.id }
        val extends = ids.size >= shownIds.size && ids.subList(0, shownIds.size) == shownIds
        if (!extends) queue.newList()
        val fresh = if (extends) ids.subList(shownIds.size, ids.size).toList() else ids
        shownIds = ids
        if (fresh.isEmpty()) return
        val r = robot
        background {
            val rid = robotId()
            disk.select(rid)
            val need = fresh.filter { !disk.has(rid, it) }
            if (need.isNotEmpty()) post(Runnable { if (rid == r && rid == robot) queue.prefetch(need) })
        }
    }

    /** Appli à l'écran ou non : le fond (préchargement, vérifications) n'avance qu'à l'écran. */
    fun setVisible(visible: Boolean) {
        queue.active = visible
    }

    /** Mémoire demandée par le système : [keep] = part gardée (0 : tout est rendu). */
    fun trimMemory(keep: Double) = memory.trimTo((memory.max * keep).toLong())

    /** Octets d'images en mémoire, pour les tests. */
    fun memoryBytes(): Long = memory.size

    fun close() = unsubscribe()

    private fun background(block: () -> Unit) {
        try {
            work.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * Images en mémoire par recette et largeur (arrondie, [bucket]) ; plafond en octets, la moins
     * récemment utilisée sort la première.
     */
    private class Memory<B : Any>(val max: Int, private val sizeOf: (B) -> Int) {
        private val map = LinkedHashMap<String, B>(64, 0.75f, true)
        private val widths = HashMap<String, MutableSet<Int>>()
        var size = 0L
            private set

        /** La plus petite image d'au moins [w] pixels, sinon la plus grande ; avec sa largeur. */
        fun best(id: String, w: Int): Pair<B, Int>? {
            val ws = widths[id] ?: return null
            val pick = ws.filter { it >= w }.minOrNull() ?: ws.maxOrNull() ?: return null
            return map["$id@$pick"]?.let { it to pick }
        }

        fun put(id: String, w: Int, b: B) {
            val k = "$id@$w"
            map.remove(k)?.let { size -= sizeOf(it) }
            map[k] = b
            widths.getOrPut(id) { HashSet() } += w
            size += sizeOf(b)
            trimTo(max.toLong())
        }

        fun remove(id: String) {
            val ws = widths.remove(id) ?: return
            for (w in ws) map.remove("$id@$w")?.let { size -= sizeOf(it) }
        }

        fun trimTo(goal: Long) {
            val it = map.entries.iterator()
            while (size > goal && it.hasNext()) {
                val e = it.next()
                it.remove()
                size -= sizeOf(e.value)
                val id = e.key.substringBeforeLast('@')
                val ws = widths[id]
                ws?.remove(e.key.substringAfterLast('@').toInt())
                if (ws != null && ws.isEmpty()) widths.remove(id)
            }
        }

        fun clear() = trimTo(-1)
    }

    companion object {
        private val UNSET = Any()
        private val DONE = Request {}

        /** Largeurs arrondies à 64 px au-dessus : des cartes de tailles voisines partagent l'image. */
        fun bucket(widthPx: Int) = ((widthPx.coerceAtLeast(1) + 63) / 64) * 64
    }
}
