package app.toque.companion.alerts

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Le diagnostic des alertes : les dernières lignes (au plus [capacity]) de ce qu'ont fait le service
 * de suivi, la liaison et le centre d'alertes, pour comprendre une alerte manquée sur un téléphone
 * qu'on ne peut pas brancher. Heure locale du téléphone. Jamais de jeton, de secret ni d'adresse IP :
 * [scrub] les masque même si un message en contient. [onLine] reçoit chaque ligne écrite (fichier).
 * Kotlin pur, sûr entre fils.
 */
class DiagLog(
    val capacity: Int = 400,
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    private val format: (Long) -> String = { SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date(it)) },
) {
    private val ring = ArrayDeque<String>()
    @Volatile var onLine: ((String) -> Unit)? = null

    fun add(message: String) {
        val line = format(wallMs()) + "  " + scrub(message)
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > capacity) ring.removeFirst()
        }
        try {
            onLine?.invoke(line)
        } catch (_: Exception) {
        }
    }

    /** Lignes reprises du fichier au démarrage (déjà écrites, donc déjà masquées ; masquées encore par sûreté). */
    fun restore(lines: List<String>) = synchronized(ring) {
        val keep = lines.takeLast(capacity).map { scrub(it) }
        ring.clear()
        ring.addAll(keep)
    }

    fun lines(): List<String> = synchronized(ring) { ArrayList(ring) }

    fun text(): String = lines().joinToString("\n")

    companion object {
        private val IPV4 = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
        private val IPV6 = Regex("[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){4,7}")
        /** Jetons (64 hexadécimaux), secrets d'appairage : toute suite de 16 caractères ou plus sans espace qui mêle chiffres et lettres. */
        private val SECRET = Regex("[A-Za-z0-9_\\-+/=]{16,}")

        fun scrub(s: String): String {
            var out = IPV4.replace(s, "•.•.•.•")
            out = IPV6.replace(out, "•:•")
            out = SECRET.replace(out) { m -> if (m.value.any { it.isDigit() } && m.value.any { it.isLetter() }) "[masqué]" else m.value }
            return out.replace('\n', ' ')
        }
    }
}
