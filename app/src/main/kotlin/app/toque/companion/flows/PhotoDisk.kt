package app.toque.companion.flows

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Photos des recettes sur le disque du téléphone (un sous-dossier de `cacheDir`), telles que le
 * robot les a envoyées. Un dossier par robot appairé (nom tiré de son identifiant, haché) : changer
 * de robot ou ne plus être appairé efface les photos de l'ancien ([select]). Moins récemment
 * utilisées d'abord dehors au-delà de [capBytes] (jusqu'à 90 %). Écriture atomique : fichier
 * temporaire puis renommage, un fichier visible est toujours complet.
 *
 * Nom d'un fichier : `<hachage de l'id>.<date du téléchargement en ms>.<taille>.img` (un fichier
 * tronqué se reconnaît) ; sa date de modification est celle du dernier usage (ordre LRU qui
 * survit au redémarrage de l'appli).
 * Kotlin pur ; sûr entre fils (tout est synchronisé, appelé hors du fil principal).
 */
class PhotoDisk(
    private val root: File,
    private val capBytes: Long = 256L shl 20,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Les octets sont-ils une image lisible ? (sur Android : les dimensions du JPEG se lisent). */
    private val valid: (ByteArray) -> Boolean = { it.isNotEmpty() },
    /** Écrit les octets dans le fichier temporaire (remplaçable par les tests : écriture interrompue). */
    private val writer: (File, ByteArray) -> Unit = ::writeSynced,
) {
    /** Une photo lue : ses octets et la date de son téléchargement. */
    class Photo(val bytes: ByteArray, val savedAt: Long)

    private class Entry(val file: File, val size: Long, val savedAt: Long, var usedAt: Long)

    /** Robot dont le dossier est ouvert (null : aucun, rien n'est gardé). */
    private var robot: String? = null
    private var selected = false
    private var dir: File? = null
    private val index = HashMap<String, Entry>()
    private var total = 0L

    /**
     * Ouvre le dossier du robot [robotId] et efface tous les autres ; null efface tout (téléphone
     * plus appairé, ou retiré sur le robot). Sans effet si ce robot est déjà ouvert.
     */
    @Synchronized fun select(robotId: String?) {
        if (selected && robotId == robot) return
        selected = true
        robot = robotId
        index.clear()
        total = 0
        val keep = robotId?.let { folderName(it) }
        root.listFiles()?.forEach { if (it.name != keep) it.deleteRecursively() }
        dir = keep?.let { File(root, it) }?.also { d ->
            d.mkdirs()
            scan(d)
        }
    }

    /** Relit le dossier : fichiers temporaires (écriture interrompue) et noms inconnus effacés. */
    private fun scan(d: File) {
        for (f in d.listFiles() ?: emptyArray()) {
            val parts = f.name.split('.')
            val savedAt = if (parts.size == 4 && parts[3] == "img") parts[1].toLongOrNull() else null
            if (!f.isFile || savedAt == null || parts[2].toLongOrNull() != f.length()) {
                f.deleteRecursively()
                continue
            }
            val e = Entry(f, f.length(), savedAt, f.lastModified())
            val old = index[parts[0]]
            // Deux fichiers pour la même photo (arrêt entre renommage et effacement) : le plus récent reste.
            if (old != null && old.savedAt >= savedAt) {
                f.delete()
                continue
            }
            if (old != null) drop(parts[0])
            index[parts[0]] = e
            total += e.size
        }
        trim()
    }

    /** La photo est-elle sur le disque pour [robotId] (le robot ouvert) ? */
    @Synchronized fun has(robotId: String?, id: String): Boolean = open(robotId) && index.containsKey(key(id))

    /** Octets de la photo [id], ou null ; un fichier illisible est effacé. Compte comme un usage. */
    @Synchronized fun read(robotId: String?, id: String): Photo? {
        if (!open(robotId)) return null
        val k = key(id)
        val e = index[k] ?: return null
        val bytes = try {
            e.file.readBytes()
        } catch (_: Exception) {
            null
        }
        if (bytes == null || bytes.size.toLong() != e.size || !valid(bytes)) {
            drop(k)
            return null
        }
        e.usedAt = now()
        e.file.setLastModified(e.usedAt)
        return Photo(bytes, e.savedAt)
    }

    /** Résultat de [write]. */
    enum class Written {
        NEW, SAME, CHANGED,
        /** Pas pour le robot ouvert (réponse arrivée après un changement de robot) : rien n'est fait. */
        REFUSED,
        /** Les octets ne sont pas une image lisible : jamais gardés. */
        NOT_IMAGE,
        /** L'écriture a échoué (disque plein, erreur d'entrée-sortie) : rien de gardé, l'image reste bonne. */
        FAILED,
    }

    /**
     * Garde les octets [bytes] de la photo [id] s'ils sont une image lisible ; les mêmes octets
     * qu'avant renouvellent seulement la date de téléchargement. Puis taille du dossier ramenée sous le plafond.
     */
    @Synchronized fun write(robotId: String?, id: String, bytes: ByteArray): Written {
        val d = dir
        if (!open(robotId) || d == null) return Written.REFUSED
        if (!valid(bytes)) return Written.NOT_IMAGE
        val k = key(id)
        val old = index[k]
        val same = old != null && old.size == bytes.size.toLong() &&
            (try { old.file.readBytes() } catch (_: Exception) { null })?.contentEquals(bytes) == true
        val t = now()
        val target = File(d, "$k.$t.${bytes.size}.img")
        if (same && old!!.file.renameTo(target)) {
            index[k] = Entry(target, old.size, t, t)
            target.setLastModified(t)
            return Written.SAME
        }
        val tmp = File(d, "$k.${System.nanoTime()}.tmp")
        try {
            writer(tmp, bytes)
            if (!tmp.renameTo(target)) throw java.io.IOException("renommage refusé")
        } catch (_: Exception) {
            tmp.delete()
            return Written.FAILED
        }
        if (old != null) {
            if (old.file != target) drop(k) else total -= old.size
        }
        target.setLastModified(t)
        index[k] = Entry(target, bytes.size.toLong(), t, t)
        total += bytes.size
        trim()
        return if (old == null) Written.NEW else Written.CHANGED
    }

    /** Efface la photo [id] (le robot n'en a plus : 404). */
    @Synchronized fun remove(robotId: String?, id: String) {
        if (open(robotId)) drop(key(id))
    }

    /** Octets gardés pour le robot ouvert. */
    @Synchronized fun bytes(): Long = total

    /** Nombre de photos gardées pour le robot ouvert. */
    @Synchronized fun count(): Int = index.size

    private fun open(robotId: String?) = selected && robotId != null && robotId == robot

    private fun drop(k: String) {
        val e = index.remove(k) ?: return
        total -= e.size
        e.file.delete()
    }

    /** Au-delà du plafond : les moins récemment utilisées sortent jusqu'à 90 % du plafond. */
    private fun trim() {
        if (total <= capBytes) return
        val goal = capBytes / 10 * 9
        for ((k, _) in index.entries.sortedBy { it.value.usedAt }) {
            if (total <= goal) break
            drop(k)
        }
    }

    companion object {
        /** Nom du dossier d'un robot : son identifiant haché (rien de lisible sur le disque). */
        fun folderName(robotId: String) = "r-" + sha256(robotId).take(16)

        private fun key(id: String) = sha256(id).take(32)

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Écrit et force sur le support avant le renommage (une coupure ne laisse pas un fichier vide). */
        fun writeSynced(f: File, bytes: ByteArray) {
            FileOutputStream(f).use {
                it.write(bytes)
                it.fd.sync()
            }
        }
    }
}
