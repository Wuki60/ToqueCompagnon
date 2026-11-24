package app.toque.companion.flows

import app.toque.core.companion.IngredientGroupWire
import app.toque.core.companion.IngredientWire
import app.toque.core.companion.RecipeDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La liste de courses du téléphone (recettes inventées). */
class ShoppingCartTest {
    private fun i(qty: String, unit: String, name: String, note: String = "") =
        IngredientWire(qty, unit, name, note, listOf(qty, unit, name).filter { it.isNotEmpty() }.joinToString(" "))

    private val crepes = RecipeDetail(id = "a", title = "Crêpes", servings = 4, language = "fr", ingredients = listOf(
        IngredientGroupWire("", listOf(i("250", "g", "Farine"), i("3", "", "Œufs"), i("0,5", "l", "Lait"), i("1", "pincée", "Sel")))))
    private val gateau = RecipeDetail(id = "b", title = "Gâteau", servings = 6, language = "fr", ingredients = listOf(
        IngredientGroupWire("Pâte", listOf(i("200", "g", "farine"), i("1 1/2", "c. à soupe", "huile"), i("1-2", "", "citrons")))))

    @Test fun addsAllIngredientsOfARecipe() {
        val (cart, n) = ShoppingCart().addRecipe(crepes)
        assertEquals(4, n)
        assertEquals(listOf("250 g de farine", "3 œufs", "0,5 l de lait", "1 pincée de sel"), cart.items.map { it.text })
        assertEquals(listOf("Crêpes"), cart.items[0].recipes)
    }

    @Test fun sameIngredientIsAddedUp() {
        val cart = ShoppingCart().addRecipe(crepes).first.addRecipe(gateau).first
        val flour = cart.items.single { it.name.equals("farine", ignoreCase = true) }
        assertEquals(450.0, flour.qty!!, 0.0)
        assertEquals("450 g de farine", flour.text)
        assertEquals(listOf("Crêpes", "Gâteau"), flour.recipes)
        assertEquals("1,5 c. à soupe d'huile", cart.items.single { it.name == "huile" }.text)
        // Une quantité qui n'est pas un nombre reste telle quelle, sans être additionnée.
        assertEquals("1-2 citrons", cart.items.single { it.name == "citrons" }.text)
        assertEquals(6, cart.items.size)
    }

    @Test fun checkedItemIsNotAddedTo() {
        var cart = ShoppingCart().addRecipe(crepes).first
        val flour = cart.items.first()
        cart = cart.toggle(flour.id).addRecipe(gateau).first
        assertEquals(2, cart.items.count { it.name.equals("farine", ignoreCase = true) })
        // Les articles cochés passent en bas.
        assertEquals(flour.id, cart.ordered.last().id)
        assertEquals(6, cart.remaining)
        assertEquals(6, cart.clearChecked().items.size)
    }

    @Test fun freeItems() {
        var cart = ShoppingCart().addFree("  Lait   demi-écrémé ").addFree("")
        assertEquals(listOf("Lait demi-écrémé"), cart.items.map { it.text })
        cart = cart.addFree("lait demi-écrémé")
        assertEquals(1, cart.items.size)
        cart = cart.remove(cart.items[0].id)
        assertEquals(0, cart.items.size)
        assertNull(cart.shareText())
    }

    @Test fun shareListsWhatIsLeftToBuy() {
        var cart = ShoppingCart().addRecipe(crepes).first
        cart = cart.toggle(cart.items[1].id)
        assertEquals("Liste de courses\n- 250 g de farine\n- 0,5 l de lait\n- 1 pincée de sel", cart.shareText())
    }

    @Test fun englishUsesADot() {
        val r = RecipeDetail(id = "c", title = "Pancakes", language = "en", ingredients = listOf(
            IngredientGroupWire("", listOf(i("1/2", "cup", "milk"), i("1.25", "cups", "flour")))))
        assertEquals(listOf("0.5 cup milk", "1.25 cups flour"), ShoppingCart().addRecipe(r).first.items.map { it.text })
    }

    @Test fun quantities() {
        assertEquals(0.5, ShoppingCart.parseQty("½")!!, 0.0)
        assertEquals(1.5, ShoppingCart.parseQty("1½")!!, 0.0)
        assertEquals(2.25, ShoppingCart.parseQty("2,25")!!, 0.0)
        assertNull(ShoppingCart.parseQty("une"))
        assertNull(ShoppingCart.parseQty("1/0"))
        assertNull(ShoppingCart.parseQty("0"))
        assertEquals("0,33", ShoppingCart.formatQty(1.0 / 3, "fr"))
        assertEquals("3", ShoppingCart.formatQty(2.999, "fr"))
    }

    @Test fun savedAndReadBack() {
        val cart = ShoppingCart().addRecipe(crepes).first.addFree("Café").let { it.toggle(it.items[0].id) }
        val back = ShoppingCart.fromJson(cart.toJson())
        assertEquals(cart.items, back.items)
        assertEquals(0, ShoppingCart.fromJson("pas du json").items.size)
        assertEquals(0, ShoppingCart.fromJson(null).items.size)
    }
}
