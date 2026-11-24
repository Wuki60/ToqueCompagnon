package app.toque.companion.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import app.toque.companion.CompanionActivity
import app.toque.companion.R
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr

/**
 * Les notifications : un canal par sorte d'alerte (réglables aussi dans les réglages d'Android),
 * plus deux canaux discrets pour la notification que le service de suivi doit garder : « Suivi en
 * cours » (une cuisson, son minuteur) et « Suivi au repos » (rien en cours, texte fixe, que
 * l'utilisateur peut couper à part). Une notification par sorte : la plus récente remplace la
 * précédente, rien ne s'empile.
 */
class Notifier(context: Context, private val log: (String) -> Unit = {}) : AlertSink {
    private val c = context.applicationContext
    private val nm = c.getSystemService(NotificationManager::class.java)

    init {
        createChannels()
    }

    /** Crée (ou renomme, dans la langue du moment) les canaux. Sans effet visible s'ils existent. */
    fun createChannels() {
        val list = listOf(
            NotificationChannel(channel(AlertKind.WAITING), tr("Le robot vous attend"), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(channel(AlertKind.END), tr("Fin d'étape, fin de cuisson"), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(channel(AlertKind.PAUSE), tr("Pause, couvercle, bol retiré"), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(channel(AlertKind.OFFLINE), tr("Robot hors ligne"), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(channel(AlertKind.TEMP), tr("Température atteinte"), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(FOLLOW_CHANNEL, tr("Suivi en cours"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            NotificationChannel(IDLE_CHANNEL, tr("Suivi au repos"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        nm.createNotificationChannels(list)
    }

    private fun channel(k: AlertKind) = "alert_" + k.key

    /** Pourquoi une alerte de cette sorte ne sonnerait pas (null : elle sonnera) : autorisation, canal coupé ou silencieux. */
    fun blockedReason(k: AlertKind): String? {
        if (!AlertPrefs.permitted(c)) return "autorisation des notifications refusée"
        if (!nm.areNotificationsEnabled()) return "notifications de l'appli désactivées"
        val ch = nm.getNotificationChannel(channel(k)) ?: return null
        return when {
            ch.importance == NotificationManager.IMPORTANCE_NONE -> "canal « ${k.key} » bloqué"
            ch.importance < NotificationManager.IMPORTANCE_DEFAULT -> "canal « ${k.key} » silencieux"
            else -> null
        }
    }

    /** Une des alertes [enabled] a son canal bloqué ou rendu silencieux dans les réglages d'Android (notifications permises). */
    fun anyChannelMuted(enabled: Set<AlertKind>): Boolean {
        if (!AlertPrefs.permitted(c) || !nm.areNotificationsEnabled()) return false
        return enabled.any { k ->
            val ch = nm.getNotificationChannel(channel(k))
            ch != null && ch.importance < NotificationManager.IMPORTANCE_DEFAULT
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(c, 0,
        Intent(c, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun builder(channel: String) = Notification.Builder(c, channel)
        .setSmallIcon(R.drawable.ic_notify)
        .setColor(Ui.ACCENT)
        .setContentIntent(openApp())
        .setVisibility(Notification.VISIBILITY_PUBLIC)

    override fun show(alert: Alert, robotName: String, onScreen: Boolean) {
        val why = blockedReason(alert.kind)
        if (why != null) log("alerte ligne ${alert.seq} : $why")
        if (!AlertPrefs.permitted(c)) return
        val title = alert.title.ifEmpty { robotName.ifEmpty { tr("Toque Compagnon") } }
        val n = builder(channel(alert.kind))
            .setContentTitle(title)
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setCategory(if (alert.kind == AlertKind.WAITING) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
        // Appli à l'écran : son et bannière, puis la notification s'efface (l'écran montre déjà l'événement).
        if (onScreen) n.setTimeoutAfter(ON_SCREEN_MS)
        post(ID_BASE + alert.kind.ordinal, n.build(), "alerte ligne ${alert.seq}")
    }

    override fun showOffline(robotName: String, onScreen: Boolean) {
        blockedReason(AlertKind.OFFLINE)?.let { log("alerte hors ligne : $it") }
        if (!AlertPrefs.permitted(c)) return
        val n = builder(channel(AlertKind.OFFLINE))
            .setContentTitle(tr("Robot hors ligne"))
            .setContentText(tr("Le téléphone cherche %s sur le Wi-Fi de la maison.", robotName.ifEmpty { tr("le robot") }))
            .setAutoCancel(true)
        if (onScreen) n.setTimeoutAfter(ON_SCREEN_MS)
        post(ID_BASE + AlertKind.OFFLINE.ordinal, n.build(), "alerte hors ligne")
    }

    private fun post(id: Int, n: Notification, what: String) {
        try {
            nm.notify(id, n)
        } catch (e: Exception) {
            log("$what : Android refuse la notification (${e.javaClass.simpleName})")
        }
    }

    override fun clearOffline() = nm.cancel(ID_BASE + AlertKind.OFFLINE.ordinal)

    /**
     * Notification du service de suivi : la recette ou le programme en titre, l'étape et l'état en
     * texte ([CookingNotice]) et, si [endsAtWall] > 0, un compte à rebours tenu par Android lui-même
     * (aucun réveil de l'appli chaque seconde). Ses boutons (cuisson manuelle) partent vers
     * [NoticeActionReceiver] : un broadcast, jamais une activité, donc utilisables téléphone verrouillé.
     */
    fun following(n: CookingNotice, endsAtWall: Long): Notification {
        val b = builder(FOLLOW_CHANNEL)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (endsAtWall > 0) b.setWhen(endsAtWall).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        else b.setShowWhen(false)
        for (a in n.actions) b.addAction(action(a))
        return b.build()
    }

    /** Un bouton de la notification de la cuisson, sans déverrouillage (la cuisson ne s'arrête jamais d'ici). */
    private fun action(a: NoticeAction): Notification.Action {
        val label = when (a) {
            NoticeAction.PAUSE -> tr("Pause")
            NoticeAction.RESUME -> tr("Reprendre")
            NoticeAction.ADD_MINUTE -> tr("+1 min")
        }
        val pi = PendingIntent.getBroadcast(c, 10 + a.ordinal, Intent(c, NoticeActionReceiver::class.java).setAction(a.action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_notify), label, pi)
        if (Build.VERSION.SDK_INT >= 31) b.setAuthenticationRequired(false)
        return b.build()
    }

    /**
     * Notification du service quand rien n'est en cours : texte fixe, posée une fois (jamais mise à
     * jour), avec « Masquer » qui ouvre la page de son canal dans les réglages d'Android.
     */
    fun idle(): Notification {
        val hide = PendingIntent.getActivity(c, 1, idleSettings(c), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return builder(IDLE_CHANNEL)
            .setContentTitle(tr("Toque Compagnon suit le robot"))
            .setContentText(tr("Rien en cours sur le robot"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_notify), tr("Masquer"), hide).build())
            .build()
    }

    /** La notification du repos est masquée : son canal coupé, ou les notifications de l'appli refusées. */
    fun idleHidden(): Boolean {
        if (!AlertPrefs.permitted(c) || !nm.areNotificationsEnabled()) return true
        val ch = nm.getNotificationChannel(IDLE_CHANNEL) ?: return false
        return ch.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun update(id: Int, n: Notification) = post(id, n, "notification de suivi")

    /** Retire une notification (l'ancienne notification du suivi après un changement de numéro). */
    fun cancel(id: Int) = try {
        nm.cancel(id)
    } catch (_: Exception) {
    }

    companion object {
        const val FOLLOW_CHANNEL = "follow"
        const val FOLLOW_ID = 10
        const val IDLE_CHANNEL = "follow_idle"
        /**
         * Numéro à part pour la notification du repos : un startForeground avec un autre numéro retire
         * l'ancienne notification ; avec le même numéro sur un canal coupé, Android garderait affichée
         * celle de la cuisson (la nouvelle est écartée sans remplacer l'ancienne).
         */
        const val IDLE_ID = 11
        const val ID_BASE = 20
        /** Durée d'une alerte notifiée pendant que l'appli est à l'écran. */
        const val ON_SCREEN_MS = 8_000L

        /** La page d'Android du seul canal « Suivi au repos » (le masquer ou le réafficher). */
        fun idleSettings(c: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, IDLE_CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
