package app.toque.companion.link

import app.toque.core.companion.RobotWire
import app.toque.core.json.Json
import java.io.File

/**
 * Ce que le téléphone a appris à l'appairage : où joindre le robot, son jeton, les identifiants
 * qui servent à la preuve (docs/COMPANION.md, 2.6), et le nom du robot. Gardé en stockage privé,
 * hors sauvegarde (voir [FileLinkStore]).
 */
data class PairedRobot(
    val host: String,
    val port: Int,
    val token: String,
    val phoneId: String,
    val robotId: String,
    val robotName: String,
    /** Date de l'appairage (horloge du téléphone, ms depuis 1970). */
    val pairedAt: Long,
    /** « qr » ou « code ». */
    val method: String = "",
    /**
     * Couleur choisie sur ce robot (« #RRGGBB ») et son thème (« dark », « light »), tels qu'il les a dits
     * la dernière fois ; vides s'il ne les dit pas. Gardés pour que l'appli s'ouvre à ses couleurs.
     */
    val accent: String = "",
    val theme: String = "",
) {
    fun toJson(): String = Json.write(linkedMapOf("host" to host, "port" to port, "token" to token, "phoneId" to phoneId,
        "robotId" to robotId, "robotName" to robotName, "pairedAt" to pairedAt, "method" to method,
        "accent" to accent, "theme" to theme))

    companion object {
        /** null si le texte est illisible ou incomplet (on repart alors de zéro : appairage à refaire). */
        fun fromJson(text: String?): PairedRobot? {
            if (text.isNullOrBlank()) return null
            return try {
                @Suppress("UNCHECKED_CAST")
                val m = Json.parse(text) as? Map<String, Any?> ?: return null
                fun s(k: String) = m[k] as? String ?: ""
                val p = PairedRobot(s("host"), (m["port"] as? Number)?.toInt() ?: 0, s("token"), s("phoneId"), s("robotId"),
                    s("robotName"), (m["pairedAt"] as? Number)?.toLong() ?: 0L, s("method"),
                    RobotWire.accent(s("accent")), RobotWire.theme(s("theme")))
                if (p.host.isEmpty() || p.port !in 1..65535 || p.token.isEmpty() || p.phoneId.isEmpty() || p.robotId.isEmpty()) null else p
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Stockage de l'appairage (un seul robot). */
interface LinkStore {
    fun load(): PairedRobot?
    fun save(p: PairedRobot)
    fun clear()
}

/**
 * Un fichier dans le dossier « sans sauvegarde » de l'appli (Context.getNoBackupFilesDir) : ni la
 * sauvegarde dans le nuage ni le transfert vers un autre téléphone ne l'emportent, en plus de
 * android:allowBackup="false". Écriture atomique (fichier temporaire puis renommage).
 */
class FileLinkStore(private val file: File) : LinkStore {
    @Synchronized override fun load(): PairedRobot? = try {
        if (file.isFile) PairedRobot.fromJson(file.readText(Charsets.UTF_8)) else null
    } catch (_: Exception) {
        null
    }

    @Synchronized override fun save(p: PairedRobot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(p.toJson(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    @Synchronized override fun clear() {
        file.delete()
    }
}

/** En mémoire (tests). */
class MemoryLinkStore(var value: PairedRobot? = null) : LinkStore {
    override fun load() = value
    override fun save(p: PairedRobot) { value = p }
    override fun clear() { value = null }
}
