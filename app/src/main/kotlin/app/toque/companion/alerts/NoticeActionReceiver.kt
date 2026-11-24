package app.toque.companion.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.toque.companion.CompanionApp

/**
 * Boutons de la notification de la cuisson (« Pause », « Reprendre », « +1 min ») : la demande part
 * vers le robot par la liaison, hors du fil principal, avec les mêmes commandes et les mêmes règles
 * que les boutons de l'appli ; le robot a le dernier mot. Aucune activité n'est ouverte (Android 12+
 * l'interdit depuis un bouton de notification, et il faudrait déverrouiller le téléphone).
 */
class NoticeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = NoticeAction.of(intent.action) ?: return
        val app = context.applicationContext as CompanionApp
        FollowService.act(app, a)
    }
}
