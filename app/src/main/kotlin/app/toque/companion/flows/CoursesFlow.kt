package app.toque.companion.flows

import app.toque.companion.ui.screens.CourseRow
import app.toque.companion.ui.screens.CoursesActions
import app.toque.companion.ui.screens.CoursesState
import app.toque.core.companion.RecipeDetail
import app.toque.core.i18n.tr

/**
 * Onglet Courses : la liste du téléphone ([ShoppingCart]), gardée dans un fichier privé à chaque
 * changement ([save], hors du fil principal côté appli). Elle ne passe jamais par le robot et
 * marche sans lui. Kotlin pur, fil principal.
 */
class CoursesFlow(
    load: () -> String?,
    private val save: (String) -> Unit,
    private val render: (CoursesState) -> Unit,
    private val share: (subject: String, text: String) -> Unit = { _, _ -> },
    private val notice: (String) -> Unit = {},
) : CoursesActions {
    var cart: ShoppingCart = ShoppingCart.fromJson(load())
        private set

    /** Rend l'état actuel (à l'ouverture de l'appli, ou quand l'onglet revient). */
    fun show() = render(present(cart))

    private fun change(next: ShoppingCart) {
        if (next.items == cart.items) return
        cart = next
        save(next.toJson())
        show()
    }

    /** « Ajouter à la liste » sur une fiche : ses ingrédients, aux quantités affichées. Rend le nombre ajouté. */
    fun addRecipe(d: RecipeDetail): Int {
        val (next, n) = cart.addRecipe(d)
        change(next)
        return n
    }

    override fun addItem(text: String) = change(cart.addFree(text))
    override fun toggleItem(id: Long) = change(cart.toggle(id))
    override fun removeItem(id: Long) = change(cart.remove(id))
    override fun clearChecked() = change(cart.clearChecked())
    override fun clearAll() = change(ShoppingCart())

    override fun shareList() {
        val text = cart.shareText()
        if (text == null) notice(tr("Rien à acheter pour l'instant."))
        else share(tr("Liste de courses"), text)
    }

    companion object {
        fun present(c: ShoppingCart): CoursesState {
            fun row(i: CartItem) = CourseRow(i.id, i.text, i.recipes.joinToString(" · "), i.checked)
            return CoursesState(
                toBuy = c.items.filter { !it.checked }.map(::row),
                inCart = c.items.filter { it.checked }.map(::row),
            )
        }
    }
}
