package app.toque.companion.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * Gabarit des écrans « En direct » : en-tête fixé en haut (comme celui de Recettes), contenu dans
 * une zone qui défile (petits écrans, grande police), barre d'actions fixée en bas, au-dessus des
 * onglets.
 */
class LivePage(c: Context, withHeader: Boolean = true) {
    val header: LiveHeader? = if (withHeader) LiveHeader(c) else null

    /** Haut fixe, sous l'en-tête : barre de titre d'un écran sans en-tête (Étapes). */
    val top: LinearLayout = Ui.vertical(c)

    /** Colonne du contenu : les écrans y ajoutent leurs blocs (marges de la maquette en dp). */
    val body: LinearLayout = Ui.vertical(c)
    val root: LinearLayout = Ui.vertical(c)
    private val column = Ui.vertical(c)

    init {
        if (header != null) root.addView(header, Ui.lp())
        root.addView(top, Ui.lp())
        column.addView(body, Ui.lp())
        column.addView(Ui.spacer(c, h = 12))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))
    }

    /** Ajoute la barre d'actions du bas (hors défilement). */
    fun bar(v: View) {
        root.addView(v, Ui.lp())
    }
}
