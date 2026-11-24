package app.toque.companion

import android.content.SharedPreferences
import app.toque.companion.flows.LookStore

/** Apparence du téléphone dans ses préférences (sauvegardées avec l'appli : ce n'est pas un secret). */
class PrefsLookStore(private val p: SharedPreferences) : LookStore {
    override var accent: String
        get() = p.getString("accent", "") ?: ""
        set(v) { p.edit().putString("accent", v).apply() }
    override var theme: String
        get() = p.getString("theme", "") ?: ""
        set(v) { p.edit().putString("theme", v).apply() }
    override var asked: String
        get() = p.getString("asked", "") ?: ""
        set(v) { p.edit().putString("asked", v).apply() }
}
