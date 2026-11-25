package app.toque.companion.ui

import android.content.Context
import android.widget.LinearLayout
import app.toque.core.i18n.tr

/**
 * Les quatre cartes d'état du robot au repos (écrans Repos et Attente) : température du bol,
 * balance avec remise à zéro, couvercle, bol.
 */
class RobotTiles(c: Context, onTare: () -> Unit) : LinearLayout(c) {
    private val temp = StatTile(c, tr("Température du bol"), 30f, 36f, padH = 14)
    private val scale = StatTile(c, tr("Balance"), 30f, 36f, padH = 14)
    private val lid = StatTile(c, tr("Couvercle"), 20f, 28f, padH = 14)
    private val bowl = StatTile(c, tr("Bol"), 20f, 28f, padH = 14)

    init {
        orientation = VERTICAL
        temp.sub.textSize = 13f
        scale.addView(Ui.button(c, tr("Remettre à zéro"), Ui.Style.OUTLINE, height = 44, textSize = 13f) { onTare() }
            .apply { setPadding(Ui.dp(8), 0, Ui.dp(8), 0) }, Ui.lp(t = 4))
        addView(tileRow(c, temp, scale))
        addView(tileRow(c, lid, bowl), Ui.lp(t = 10))
    }

    /** [bowlC] null : température non mesurée en ce moment (Smart hors chauffe), affichée « — ». */
    fun set(bowlC: Int?, scaleG: Int, lidClosed: Boolean, bowlInPlace: Boolean) {
        temp.set(Fmt.bowl(bowlC), Ui.bowlColor(bowlC), Fmt.bowlNote(bowlC))
        scale.set(Fmt.grams(scaleG))
        lid.set(if (lidClosed) tr("Fermé") else tr("Ouvert"), if (lidClosed) Ui.TEXT else Ui.WARN)
        bowl.set(if (bowlInPlace) tr("En place") else tr("Retiré"), if (bowlInPlace) Ui.TEXT else Ui.WARN)
    }
}
