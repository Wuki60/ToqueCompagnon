package app.toque.companion.flows

import app.toque.core.companion.IngredientGroupWire
import app.toque.core.companion.IngredientWire
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.Wires
import org.junit.Assert.assertEquals
import org.junit.Test

/** La liste de courses partagée depuis la fiche (recettes inventées). */
class ShoppingListTest {
    private fun i(qty: String, unit: String, name: String, note: String = "") =
        IngredientWire(qty, unit, name, note, listOf(qty, unit, name).filter { it.isNotEmpty() }.joinToString(" "))

    private val veloute = RecipeDetail(id = "perso-veloute", title = "Velouté de potimarron", servings = 4, language = "fr",
        baseServings = 4, minServings = 2, maxServings = 8,
        ingredients = listOf(IngredientGroupWire("", listOf(i("1", "", "Oignon"), i("20", "g", "Beurre"), i("600", "g", "Potimarron"),
            i("500", "g", "Eau"), i("100", "g", "Crème")))))

    @Test fun likeTheExample() {
        assertEquals("""
            Velouté de potimarron (4 personnes)
            - 1 oignon
            - 20 g de beurre
            - 600 g de potimarron
            - 500 g d'eau
            - 100 g de crème
        """.trimIndent(), ShoppingList.text(veloute))
    }

    @Test fun groupsKeepTheirTitles() {
        val tarte = RecipeDetail(id = "tarte", title = "Tarte aux pommes inventée", servings = 6, language = "fr", ingredients = listOf(
            IngredientGroupWire("Pâte", listOf(i("200", "g", "Farine"), i("100", "g", "Beurre", "mou"), i("1", "", "œuf"))),
            IngredientGroupWire("Garniture", listOf(i("4", "", "pommes"), i("2", "c. à soupe", "huile"), i("", "", "Sel")))))
        assertEquals("""
            Tarte aux pommes inventée (6 personnes)

            Pâte
            - 200 g de farine
            - 100 g de beurre, mou
            - 1 œuf

            Garniture
            - 4 pommes
            - 2 c. à soupe d'huile
            - sel
        """.trimIndent(), ShoppingList.text(tarte))
    }

    /** Les quantités de la fiche affichée : au nombre choisi (calcul du robot), corrections comprises. */
    @Test fun atTheChosenNumberOfPeople() {
        val six = Wires.forServings(veloute, 6)!!
        val text = ShoppingList.text(six)
        assertEquals("Velouté de potimarron (6 personnes)", text.lines().first())
        assertEquals("- 30 g de beurre", text.lines()[2])
        assertEquals("- 900 g de potimarron", text.lines()[3])
        // Une correction faite sur le robot arrive dans la fiche : elle est partagée telle quelle.
        val corrected = veloute.copy(ingredients = listOf(IngredientGroupWire("", listOf(i("25", "g", "Beurre")))))
        assertEquals("- 25 g de beurre", ShoppingList.text(corrected).lines()[1])
        assertEquals("Velouté de potimarron (1 personne)", ShoppingList.text(veloute.copy(servings = 1)).lines().first())
        assertEquals("Confiture (3 pots)", ShoppingList.text(RecipeDetail(title = "Confiture", servings = 3, servingUnit = "pots")))
    }

    @Test fun withoutQuantitiesOrPeople() {
        val r = RecipeDetail(title = "Salade inventée", language = "fr",
            ingredients = listOf(IngredientGroupWire("", listOf(i("", "", "Laitue"), i("", "", "AOP comté", "râpé"), i("", "", "huile d'olive")))))
        assertEquals("""
            Salade inventée
            - laitue
            - AOP comté, râpé
            - huile d'olive
        """.trimIndent(), ShoppingList.text(r))
        assertEquals("Rien", ShoppingList.text(RecipeDetail(title = "Rien")))
    }

    /** Une recette dans une autre langue : pas de « de », les noms tels quels. */
    @Test fun otherLanguages() {
        val r = RecipeDetail(title = "Invented soup", servings = 2, language = "en",
            ingredients = listOf(IngredientGroupWire("", listOf(i("20", "g", "Butter"), i("1", "", "Onion")))))
        assertEquals("Invented soup (2 personnes)\n- 20 g Butter\n- 1 Onion", ShoppingList.text(r))
    }
}
