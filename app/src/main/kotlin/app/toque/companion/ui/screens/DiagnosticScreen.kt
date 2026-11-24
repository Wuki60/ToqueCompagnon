package app.toque.companion.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import app.toque.companion.ui.BackBar
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.companion.ui.actionBar
import app.toque.core.i18n.tr

/** Le diagnostic des alertes : l'état du téléphone, puis les dernières lignes (sans jeton ni adresse). */
data class DiagnosticState(
    /** « Android 14 (API 34), samsung, notifications permises, batterie limitée ». */
    val phone: String,
    /** Les lignes, une par ligne de texte, la plus ancienne d'abord. */
    val lines: String,
)

/** Écran du diagnostic (onglet Robot, ligne discrète en bas) : texte qui défile et « Copier ». */
class DiagnosticScreen(c: Context, private val nav: Navigator) : Screen<DiagnosticState>(c) {
    override val tab = Tab.ROBOT
    private lateinit var bar: BackBar
    private val phone = Ui.text(c, "", 13f, Ui.TEXT2, lineHeight = 18f)
    private val body: TextView = Ui.text(c, "", 12f, Ui.TEXT, Typeface.MONOSPACE, lineHeight = 17f).apply { setTextIsSelectable(true) }
    private lateinit var copy: Ui.Button

    override fun build(): View {
        val root = Ui.vertical(c)
        bar = BackBar(c, tr("Retour")) { nav.back() }
        bar.title.text = tr("Diagnostic des alertes")
        root.addView(bar)
        val column = Ui.vertical(c)
        column.addView(Ui.text(c, tr("Ce que l'appli a fait pour suivre le robot et notifier. À copier pour signaler une alerte manquée : rien de secret n'y figure."),
            13f, Ui.TEXT2, lineHeight = 18f), Ui.lp(l = 20, t = 4, r = 20))
        column.addView(phone, Ui.lp(l = 20, t = 10, r = 20))
        val card = Ui.card(c)
        Ui.pad(card, 14, 12, 14, 12)
        card.addView(body)
        column.addView(card, Ui.lp(l = 20, t = 12, r = 20))
        column.addView(Ui.spacer(c, h = 12))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))
        copy = Ui.button(c, tr("Copier"), Ui.Style.SECONDARY, textSize = 16f) { copyAll() }
        root.addView(actionBar(c, copy to 1f))
        return root
    }

    private fun copyAll() {
        val s = state ?: return
        try {
            c.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(tr("Diagnostic des alertes"), s.phone + "\n" + s.lines))
            Ui.set(copy.label, tr("Copié"))
        } catch (_: Exception) {
        }
    }

    override fun bind(s: DiagnosticState, old: DiagnosticState?) {
        Ui.set(phone, s.phone)
        Ui.set(body, s.lines.ifEmpty { tr("Rien pour l'instant.") })
        Ui.set(copy.label, tr("Copier"))
    }
}
