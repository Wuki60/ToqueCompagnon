package app.toque.companion.update

import app.toque.core.json.Json
import app.toque.core.update.Release
import app.toque.core.update.Releases

/**
 * Versions du compagnon publiées sur GitHub (page « Releases » de Wuki60/ToqueCompagnon) : la même
 * lecture que pour Toque (core/update/Releases.kt), avec le dépôt et le nom de l'APK du compagnon,
 * « ToqueCompagnon-x.y.z.apk ». Kotlin pur.
 */
object CompanionReleases {
    const val REPO = "Wuki60/ToqueCompagnon"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE_URL = "https://github.com/$REPO/releases/latest"
    const val APK_PREFIX = "ToqueCompagnon-"

    /** Les téléchargements viennent de GitHub seulement (l'API renvoie vers ses serveurs de fichiers). */
    private val HOSTS = listOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun isNewer(candidate: String, installed: String): Boolean = Releases.isNewer(candidate, installed)

    /** Lit la réponse de `GET /repos/Wuki60/ToqueCompagnon/releases/latest`. */
    fun parse(json: String): Release {
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(json) as? Map<String, Any?> ?: throw IllegalArgumentException("réponse GitHub illisible")
        val tag = o["tag_name"] as? String ?: throw IllegalArgumentException("version sans numéro")
        @Suppress("UNCHECKED_CAST")
        val assets = (o["assets"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }
        fun name(a: Map<String, Any?>) = (a["name"] as? String).orEmpty()
        val apks = assets.filter { name(it).endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { name(it).startsWith(APK_PREFIX, ignoreCase = true) } ?: apks.firstOrNull()
        val url = (apk?.get("browser_download_url") as? String)?.takeIf { trusted(it) }
        return Release(
            tag = tag,
            version = Releases.versionOf(tag),
            title = (o["name"] as? String)?.takeIf { it.isNotBlank() } ?: tag,
            notes = (o["body"] as? String).orEmpty().trim(),
            apkName = if (url != null) apk?.get("name") as? String else null,
            apkUrl = url,
            apkSize = if (url != null) (apk?.get("size") as? Number)?.toLong() ?: 0L else 0L,
        )
    }

    /** Adresse https d'un serveur de GitHub. */
    fun trusted(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
        return host in HOSTS
    }
}
