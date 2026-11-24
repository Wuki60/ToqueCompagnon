package app.toque.companion.ui

import android.content.Context
import android.view.Gravity
import app.toque.core.i18n.tr

/**
 * « Arrêter la recette » des écrans de la recette guidée (Étapes, Recette en cours) : un bouton
 * discret dans le contenu, loin du Stop rouge de la barre du bas (qui reste en un seul appui), et sa
 * confirmation, comme sur le robot. « Continuer » ne change rien.
 */
class StopRecipe(c: Context, onConfirm: () -> Unit) {
    val sheet = ConfirmSheet(c, tr("Arrêter la recette ?"), "", tr("Arrêter la recette"), Ui.Style.DANGER, tr("Continuer"), onConfirm)

    val button: Ui.Button = Ui.button(c, tr("Arrêter la recette"), Ui.Style.GHOST, Icon.Kind.STOP, height = 44, textSize = 14f,
        iconSize = 16) { sheet.show() }.apply {
        gravity = Gravity.CENTER
        label.setTextColor(Ui.DANGER)
        icon?.color = Ui.DANGER
        Ui.ellipsize(label, 2)
    }

    /** [offered] : le robot le permet (sinon pas de bouton) ; [enabled] : pas de demande en route ; [cooking] : un programme tourne. */
    fun bind(offered: Boolean, enabled: Boolean, cooking: Boolean) {
        Ui.show(button, offered)
        Ui.setButtonEnabled(button, enabled)
        Ui.set(sheet.body, if (cooking) tr("La cuisson en cours s'arrête, et la recette ne sera plus proposée sur l'accueil du robot.")
            else tr("La recette ne sera plus proposée sur l'accueil du robot."))
        if (!offered) sheet.hide()
    }
}
