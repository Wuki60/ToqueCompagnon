package app.toque.companion.ui.screens

import android.content.Context
import android.graphics.Paint
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.companion.ui.ConfirmSheet
import app.toque.companion.ui.EmptyState
import app.toque.companion.ui.Icon
import app.toque.companion.ui.Navigator
import app.toque.companion.ui.Screen
import app.toque.companion.ui.Tab
import app.toque.companion.ui.Ui
import app.toque.core.i18n.tr

/** Une ligne de la liste de courses : « 500 g de farine », les recettes d'où elle vient. */
data class CourseRow(val id: Long, val text: String, val from: String = "", val checked: Boolean = false)

/** Onglet Courses : à acheter, puis ce qui est déjà dans le panier (coché). */
data class CoursesState(
    val toBuy: List<CourseRow> = emptyList(),
    val inCart: List<CourseRow> = emptyList(),
)

/** Ce que l'onglet Courses demande ; tout reste sur le téléphone. */
interface CoursesActions {
    fun addItem(text: String) {}
    fun toggleItem(id: Long) {}
    fun removeItem(id: Long) {}
    fun clearChecked() {}
    fun clearAll() {}
    fun shareList() {}
}

class CoursesScreen(c: Context, private val actions: CoursesActions, private val nav: Navigator) : Screen<CoursesState>(c) {
    override val tab = Tab.SHOP
    private val count = Ui.text(c, "", 14f, Ui.TEXT2)
    private lateinit var field: EditText
    private val toBuy = Ui.card(c)
    private val inCartTitle = Ui.overline(c)
    private val inCartHeader = Ui.horizontal(c)
    private val inCart = Ui.card(c)
    private val empty = EmptyState(c, Icon.Kind.BASKET)
    private lateinit var bottom: LinearLayout
    private lateinit var share: Ui.Button
    private lateinit var clearChecked: Ui.Button
    private lateinit var clearAll: Ui.Button
    private lateinit var confirm: ConfirmSheet

    override fun build(): View {
        val root = Ui.vertical(c)
        // En-tête comme les autres onglets : titre, et le nombre d'articles à acheter.
        val header = Ui.horizontal(c)
        Ui.pad(header, 20, 18, 20, 6)
        header.addView(Ui.ellipsize(Ui.text(c, tr("Courses"), 26f, Ui.TEXT, Ui.MEDIUM).apply { letterSpacing = -0.2f / 26f }),
            Ui.lp(0, Ui.WRAP, 1f, r = 8))
        header.addView(count, Ui.lp(Ui.WRAP, Ui.WRAP))
        root.addView(header, Ui.lp())

        // Ajouter un article à la main : champ de 48 dp et bouton +.
        val add = Ui.horizontal(c)
        add.background = Ui.round(Ui.SURFACE, 24f, Ui.STROKE, 1f)
        add.setPadding(Ui.dp(16), 0, Ui.dp(4), 0)
        add.minimumHeight = Ui.dp(48)
        field = EditText(c).apply {
            background = null
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT2)
            hint = tr("Ajouter un article")
            typeface = Ui.REGULAR
            includeFontPadding = false
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = Ui.dp(44)
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(120))
            Ui.tintField(this)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
        }
        add.addView(field, Ui.lp(0, Ui.WRAP, 1f))
        add.addView(Ui.iconButton(c, Icon.Kind.PLUS, tr("Ajouter à la liste"), visible = 40, iconSize = 20, style = Ui.Style.PRIMARY) { submit() })
        root.addView(add, Ui.lp(l = 20, t = 10, r = 20))

        val column = Ui.vertical(c)
        column.addView(toBuy, Ui.lp(l = 20, t = 12, r = 20))
        // « Dans le panier (n) », et à droite de quoi retirer ce qui est déjà pris.
        inCartHeader.addView(inCartTitle, Ui.lp(0, Ui.WRAP, 1f))
        clearChecked = Ui.button(c, tr("Retirer"), Ui.Style.GHOST, Icon.Kind.CHECK, height = 36, textSize = 13f, iconSize = 16,
            description = tr("Retirer les cochés")) { actions.clearChecked() }
        inCartHeader.addView(clearChecked, Ui.lp(Ui.WRAP, Ui.WRAP))
        column.addView(inCartHeader, Ui.lp(l = 20, t = 12, r = 12))
        column.addView(inCart, Ui.lp(l = 20, t = 2, r = 20))
        column.addView(empty, Ui.lp(t = 24))
        clearAll = Ui.button(c, tr("Vider la liste"), Ui.Style.GHOST, Icon.Kind.TRASH, height = 44, textSize = 14f, iconSize = 18) { confirm.show() }
        column.addView(clearAll, Ui.lp(Ui.WRAP, Ui.WRAP, l = 20, t = 8, r = 20))
        column.addView(Ui.spacer(c, h = 12))
        root.addView(Ui.scroll(c, column), Ui.lp(Ui.MATCH, 0, 1f))

        // Bas fixe : partager ce qui reste à acheter.
        bottom = Ui.horizontal(c)
        Ui.pad(bottom, 20, 8, 20, 12)
        share = Ui.button(c, tr("Partager la liste de courses"), Ui.Style.PRIMARY, Icon.Kind.SHARE, height = 52, textSize = 16f, iconSize = 20) { actions.shareList() }
        Ui.ellipsize(share.label)
        bottom.addView(share, Ui.lp(0, Ui.WRAP, 1f))
        root.addView(bottom, Ui.lp())

        val frame = FrameLayout(c)
        frame.addView(root, Ui.flp())
        confirm = ConfirmSheet(c, tr("Vider la liste ?"), tr("Tous les articles sont retirés, cochés ou non."),
            tr("Vider")) { actions.clearAll() }
        frame.addView(confirm, Ui.flp())
        return frame
    }

    private fun submit() {
        val t = field.text?.toString().orEmpty().trim()
        if (t.isEmpty()) return
        actions.addItem(t)
        field.setText("")
    }

    override fun bind(s: CoursesState, old: CoursesState?) {
        val n = s.toBuy.size
        Ui.set(count, when {
            n == 0 && s.inCart.isEmpty() -> ""
            n == 0 -> tr("Tout est pris")
            n == 1 -> tr("1 article à acheter")
            else -> tr("%d articles à acheter", n)
        })
        fill(toBuy, s.toBuy)
        fill(inCart, s.inCart)
        Ui.show(toBuy, s.toBuy.isNotEmpty())
        Ui.show(inCart, s.inCart.isNotEmpty())
        Ui.show(inCartHeader, s.inCart.isNotEmpty())
        Ui.set(inCartTitle, tr("Dans le panier (%d)", s.inCart.size))
        val none = s.toBuy.isEmpty() && s.inCart.isEmpty()
        Ui.show(empty, none)
        if (none) empty.set(tr("La liste est vide"), tr("Sur une recette, touchez « Ajouter à la liste ». Vous pouvez aussi écrire un article ci-dessus."))
        Ui.show(clearAll, !none)
        Ui.show(bottom, s.toBuy.isNotEmpty())
    }

    private fun fill(card: LinearLayout, rows: List<CourseRow>) {
        card.removeAllViews()
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(Ui.divider(c), Ui.lp(Ui.MATCH, maxOf(1, Ui.dp(1)), l = 56))
            card.addView(row(r))
        }
    }

    /** Rond à cocher, texte (barré une fois pris), recettes d'origine, et une croix pour retirer. */
    private fun row(r: CourseRow): View {
        val line = Ui.horizontal(c)
        line.minimumHeight = Ui.dp(56)
        line.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(4), Ui.dp(6))
        line.isClickable = true
        line.isFocusable = true
        line.background = Ui.pressable(Ui.round(0, 20f), 20f)
        line.contentDescription = (if (r.checked) tr("Pris") + " : " else "") + r.text
        line.setOnClickListener { actions.toggleItem(r.id) }

        val box = FrameLayout(c)
        box.background = if (r.checked) Ui.round(Ui.ACCENT, 14f) else Ui.round(0, 14f, Ui.TEXT3, 2f)
        if (r.checked) box.addView(Icon(c, Icon.Kind.CHECK, Ui.ON_ACCENT), Ui.flp(Ui.dp(18), Ui.dp(18), Gravity.CENTER))
        box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        line.addView(box, LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)).apply { rightMargin = Ui.dp(14) })

        val texts = Ui.vertical(c)
        val main: TextView = Ui.text(c, r.text, 16f, if (r.checked) Ui.TEXT3 else Ui.TEXT, lineHeight = 21f)
        if (r.checked) main.paintFlags = main.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        texts.addView(main)
        if (r.from.isNotEmpty()) texts.addView(Ui.ellipsize(Ui.text(c, r.from, 12f, Ui.TEXT2)), Ui.lp(t = 2))
        line.addView(texts, Ui.lp(0, Ui.WRAP, 1f))

        line.addView(Ui.iconButton(c, Icon.Kind.CLOSE, tr("Retirer %s", r.text), visible = 36, iconSize = 18, style = Ui.Style.GHOST) {
            actions.removeItem(r.id)
        })
        return line
    }

    override fun onBack(): Boolean {
        if (confirm.showing) { confirm.hide(); return true }
        return false
    }
}
