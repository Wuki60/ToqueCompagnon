package app.toque.companion

import android.content.Context
import android.util.Log
import app.toque.core.i18n.I18n
import app.toque.core.i18n.Language
import java.util.Locale

/**
 * Langues du compagnon : les fichiers `lang/<code>.json` des assets (app/src/main/assets/lang, même
 * format que l'appli du robot, vérifiés par LanguageFilesTest). Comme « Langue · Language » sur le
 * robot : « auto » (par défaut) suit la langue du téléphone (l'anglais si elle n'est pas traduite),
 * sinon la langue choisie dans l'onglet Robot.
 */
object CompanionLanguages {
    private const val PREFS = "langue"
    private const val KEY = "langue"

    /** Réglage gardé : « auto » ou un code (fr, en…). */
    fun setting(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, I18n.AUTO) ?: I18n.AUTO

    /** Garde [code] (« auto » ou une langue installée) et le met en place. */
    fun choose(context: Context, code: String) {
        val c = if (code == I18n.AUTO || I18n.find(code) != null) code else I18n.AUTO
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, c).apply()
        I18n.select(I18n.resolve(c, Locale.getDefault().language).code)
    }

    /** Langues proposées (code, nom dans sa langue), le français d'abord. */
    fun options(): List<Pair<String, String>> = I18n.languages.map { it.code to it.name }

    fun install(context: Context, systemLanguage: String = Locale.getDefault().language, setting: String? = null) {
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
        I18n.select(I18n.resolve(setting ?: setting(context), systemLanguage).code)
    }
}
