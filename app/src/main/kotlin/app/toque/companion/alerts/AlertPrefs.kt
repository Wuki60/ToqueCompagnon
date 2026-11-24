package app.toque.companion.alerts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.toque.companion.flows.AlertSwitches

/**
 * Les interrupteurs des alertes (écran Robot, à venir) : une préférence par [AlertKind], valeur par
 * défaut de la maquette. [onChanged] prévient quand un interrupteur change.
 */
class AlertPrefs(context: Context) : AlertSwitches {
    private val prefs = context.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    private val listeners = ArrayList<() -> Unit>()

    fun isOn(kind: AlertKind): Boolean = prefs.getBoolean(kind.key, kind.defaultOn)

    override fun set(kind: AlertKind, on: Boolean) {
        prefs.edit().putBoolean(kind.key, on).apply()
        for (l in ArrayList(listeners)) l()
    }

    /** Les sortes d'alertes allumées. */
    override fun enabled(): Set<AlertKind> = AlertKind.values().filter { isOn(it) }.toSet()

    fun onChanged(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    /** « Suivi en arrière-plan » (écran Robot) : le service suit le robot tant qu'il est appairé. Oui par défaut. */
    var background: Boolean
        get() = prefs.getBoolean("background", true)
        set(v) {
            prefs.edit().putBoolean("background", v).apply()
            for (l in ArrayList(listeners)) l()
        }

    /** La demande d'autorisation des notifications (Android 13+) a déjà été faite une fois. */
    var permissionAsked: Boolean
        get() = prefs.getBoolean("permission_asked", false)
        set(v) { prefs.edit().putBoolean("permission_asked", v).apply() }

    companion object {
        const val PERMISSION_REQUEST = 41

        /** Les notifications sont-elles permises (toujours avant Android 13) ? */
        fun permitted(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Android limite l'appli en arrière-plan (optimisation de la batterie non levée). */
        fun batteryRestricted(context: Context): Boolean = try {
            !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }

        /** Ouvre la demande d'Android pour lever la limite de batterie (réglages de l'optimisation à défaut). */
        @SuppressLint("BatteryLife")
        fun requestBattery(activity: Activity) {
            try {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName)))
            } catch (_: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }

        /** Demande l'autorisation des notifications (Android 13+) ; la réponse arrive dans onRequestPermissionsResult. */
        fun request(activity: Activity) {
            if (Build.VERSION.SDK_INT >= 33 && !permitted(activity))
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        }
    }
}
