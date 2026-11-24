package app.toque.companion.flows

import app.toque.core.companion.IngredientWire
import app.toque.core.companion.RecipeDetail
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Un article de la liste de courses. [qty] : quantité lue en nombre (null : pas de nombre, ou une
 * quantité écrite autrement, gardée telle quelle dans [qtyText]). [lang] : langue de la recette d'où
 * vient l'article (« de » / « d' » en français), vide pour un article écrit à la main.
 */
data class CartItem(
    val id: Long,
    val name: String,
    val unit: String = "",
    val qty: Double? = null,
    val qtyText: String = "",
    val note: String = "",
    val lang: String = "",
    val checked: Boolean = false,
    /** Recettes d'où vient l'article (titres), sans doublon. */
    val recipes: List<String> = emptyList(),
) {
    /** « 500 g de farine », « 2 oignons », « Lait ». */
    val text: String
        get() {
            val french = lang.lowercase(Locale.ROOT).startsWith("fr")
            val q = qty?.let { ShoppingCart.formatQty(it, lang) } ?: qtyText
            if (lang.isEmpty()) return listOf(q, unit, name).filter { it.isNotBlank() }.joinToString(" ") +
                (if (note.isNotBlank()) ", $note" else "")
            return ShoppingList.line(IngredientWire(q, unit, name, note, name), french)
        }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "unit" to unit, "qty" to qty, "qtyText" to qtyText, "note" to note,
        "lang" to lang, "checked" to checked, "recipes" to recipes,
    )

    companion object {
        fun fromMap(m: Map<*, *>): CartItem? {
            val name = (m["name"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) return null
            return CartItem(
                id = (m["id"] as? Number)?.toLong() ?: return null,
                name = name,
                unit = (m["unit"] as? String).orEmpty(),
                qty = (m["qty"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 },
                qtyText = (m["qtyText"] as? String).orEmpty(),
                note = (m["note"] as? String).orEmpty(),
                lang = (m["lang"] as? String).orEmpty(),
                checked = m["checked"] == true,
                recipes = (m["recipes"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
            )
        }
    }
}

/**
 * La liste de courses du téléphone : rien ne part vers le robot. Les ingrédients d'une recette
 * s'ajoutent aux quantités de la fiche affichée ; un même ingrédient (même nom, même unité) encore à
 * acheter est additionné (200 g + 300 g de farine = 500 g). Les articles cochés passent en bas.
 * Kotlin pur, immuable : chaque changement rend une nouvelle liste.
 */
class ShoppingCart(val items: List<CartItem> = emptyList()) {

    val remaining: Int get() = items.count { !it.checked }
    val checkedCount: Int get() = items.count { it.checked }

    /** À acheter d'abord (ordre d'ajout), puis les articles cochés. */
    val ordered: List<CartItem> get() = items.filter { !it.checked } + items.filter { it.checked }

    private val nextId: Long get() = (items.maxOfOrNull { it.id } ?: 0L) + 1

    /**
     * Ajoute les ingrédients de [d] (telle qu'affichée) ; rend la nouvelle liste et le nombre
     * d'ingrédients ajoutés ou additionnés.
     */
    fun addRecipe(d: RecipeDetail): Pair<ShoppingCart, Int> {
        var cart = this
        var n = 0
        val title = d.title.trim()
        val lang = d.language.ifBlank { "fr" }
        for (g in d.ingredients) for (i in g.items) {
            val name = i.name.trim().ifEmpty { i.display.trim() }
            if (name.isEmpty()) continue
            val rawQty = if (i.name.isBlank()) "" else i.qty.trim()
            val number = parseQty(rawQty)
            cart = cart.add(CartItem(
                id = cart.nextId, name = name, unit = if (i.name.isBlank()) "" else i.unit.trim(),
                qty = number, qtyText = if (number == null) rawQty else "",
                note = i.note.trim(), lang = lang, recipes = if (title.isEmpty()) emptyList() else listOf(title),
            ))
            n++
        }
        return cart to n
    }

    /** Article écrit à la main (« Lait ») ; rien si le texte est vide ou déjà dans la liste à acheter. */
    fun addFree(text: String): ShoppingCart {
        val name = text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT)
        if (name.isEmpty()) return this
        return add(CartItem(id = nextId, name = name))
    }

    private fun add(item: CartItem): ShoppingCart {
        if (items.size >= MAX_ITEMS) return this
        val key = key(item)
        val i = items.indexOfFirst { !it.checked && key(it) == key }
        if (i < 0) return ShoppingCart(items + item)
        val old = items[i]
        val merged = old.copy(
            qty = if (old.qty != null && item.qty != null) old.qty + item.qty else old.qty ?: item.qty,
            note = if (old.note == item.note) old.note else "",
            recipes = (old.recipes + item.recipes).distinct(),
        )
        return ShoppingCart(items.toMutableList().also { it[i] = merged })
    }

    fun toggle(id: Long): ShoppingCart = ShoppingCart(items.map { if (it.id == id) it.copy(checked = !it.checked) else it })
    fun remove(id: Long): ShoppingCart = ShoppingCart(items.filter { it.id != id })
    fun clearChecked(): ShoppingCart = ShoppingCart(items.filter { !it.checked })

    /** La liste à partager (articles à acheter seulement), ou null si elle est vide. */
    fun shareText(): String? {
        val left = items.filter { !it.checked }
        if (left.isEmpty()) return null
        return tr("Liste de courses") + "\n" + left.joinToString("\n") { "- " + it.text }
    }

    fun toJson(): String = Json.write(linkedMapOf("version" to 1, "items" to items.map { it.toMap() }))

    companion object {
        const val MAX_ITEMS = 500
        const val MAX_TEXT = 120

        fun fromJson(text: String?): ShoppingCart {
            if (text.isNullOrBlank()) return ShoppingCart()
            return try {
                val o = Json.parse(text) as? Map<*, *> ?: return ShoppingCart()
                val list = (o["items"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(CartItem::fromMap) }.orEmpty()
                ShoppingCart(list.distinctBy { it.id }.take(MAX_ITEMS))
            } catch (_: Exception) {
                ShoppingCart()
            }
        }

        /** Même article : même nom et même unité (sans casse ni espaces en trop), et une quantité lisible ou aucune. */
        internal fun key(i: CartItem): String =
            if (i.qtyText.isNotEmpty()) "#" + i.id
            else norm(i.name) + "|" + norm(i.unit)

        private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        private val FRACTIONS = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3)

        /** « 20 », « 1,5 », « 1.5 », « 1/2 », « 1 1/2 », « ½ », « 1½ » ; null sinon (« 1-2 », « une pincée »). */
        fun parseQty(s: String): Double? {
            val t = s.trim()
            if (t.isEmpty()) return null
            val v = parsePlain(t) ?: return null
            return v.takeIf { it > 0 && it.isFinite() && it < 1e6 }
        }

        private fun parsePlain(t: String): Double? {
            val last = t.last()
            FRACTIONS[last]?.let { f ->
                val head = t.dropLast(1).trim()
                return if (head.isEmpty()) f else head.toIntOrNull()?.let { it + f }
            }
            val parts = t.split(' ').filter { it.isNotEmpty() }
            if (parts.size == 2) {
                val whole = parts[0].toIntOrNull() ?: return null
                val frac = fraction(parts[1]) ?: return null
                return whole + frac
            }
            if (parts.size != 1) return null
            return fraction(t) ?: decimal(t)
        }

        private fun fraction(t: String): Double? {
            val i = t.indexOf('/')
            if (i <= 0) return null
            val a = t.substring(0, i).toIntOrNull() ?: return null
            val b = t.substring(i + 1).toIntOrNull() ?: return null
            return if (b == 0) null else a.toDouble() / b
        }

        private fun decimal(t: String): Double? {
            if (!Regex("^\\d+([.,]\\d+)?$").matches(t)) return null
            return t.replace(',', '.').toDoubleOrNull()
        }

        /** « 500 », « 1,5 » (« 1.5 » en anglais), au plus deux décimales. */
        fun formatQty(v: Double, lang: String): String {
            val rounded = (v * 100).roundToLong() / 100.0
            if (abs(rounded - rounded.roundToLong()) < 1e-9) return rounded.roundToLong().toString()
            val s = String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.')
            return if (lang.lowercase(Locale.ROOT).startsWith("en")) s else s.replace('.', ',')
        }
    }
}
