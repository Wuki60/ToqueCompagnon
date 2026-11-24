package app.toque.companion

import android.content.Context
import android.util.Log
import app.toque.core.i18n.I18n
import app.toque.core.i18n.Language
import java.util.Locale

/**
 * Langues du compagnon : les fichiers `lang/<code>.json` des assets (app/src/main/assets/lang, même
 * format que l'appli du robot, vérifiés par LanguageFilesTest). Le téléphone n'a pas de réglage :
 * l'appli suit la langue du téléphone (l'anglais si elle n'est pas traduite, comme sur le robot).
 */
object CompanionLanguages {
    fun install(context: Context, systemLanguage: String = Locale.getDefault().language) {
        val found = ArrayList<Language>()
        try {
            for (name in context.assets.list("lang").orEmpty().sorted()) {
                if (!name.endsWith(".json")) continue
                try {
                    val text = context.assets.open("lang/$name").bufferedReader().use { it.readText() }
                    found += I18n.parse(name.removeSuffix(".json"), text)
                } catch (e: Exception) {
                    Log.w("Toque", "langue $name ignorée : ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("Toque", "langues illisibles : $e")
        }
        I18n.install(found)
        I18n.select(I18n.resolve(I18n.AUTO, systemLanguage).code)
    }
}
