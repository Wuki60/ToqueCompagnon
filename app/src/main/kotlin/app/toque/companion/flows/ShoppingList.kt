package app.toque.companion.flows

import app.toque.core.companion.IngredientWire
import app.toque.core.companion.RecipeDetail
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import java.util.Locale

/**
 * La liste de courses d'une fiche, en texte simple à partager (notes, messages…) : le titre et le
 * nombre de personnes, puis une ligne par ingrédient (« - 20 g de beurre »), groupes gardés avec leur
 * titre. Les quantités sont celles de la fiche telle qu'elle est affichée (nombre de personnes choisi,
 * corrections du robot comprises). Kotlin pur.
 */
object ShoppingList {
    fun text(d: RecipeDetail): String {
        val sb = StringBuilder()
        val people = when {
            d.servings <= 0 -> null
            d.servingUnit.isNotBlank() -> "${d.servings} ${d.servingUnit.trim()}"
            d.servings == 1 -> tr("1 personne")
            else -> tr("%d personnes", d.servings)
        }
        sb.append(d.title.trim())
        if (people != null) sb.append(" (").append(people).append(')')
        // Les nombres et les noms sont écrits dans la langue de la recette (celle du robot) : « de » en français.
        val french = d.language.ifBlank { I18n.code }.lowercase(Locale.ROOT).startsWith("fr")
        var first = true
        for (g in d.ingredients) {
            val items = g.items.map { line(it, french) }.filter { it.isNotEmpty() }
            if (items.isEmpty()) continue
            val title = g.title.trim()
            if (title.isNotEmpty()) {
                sb.append("\n\n").append(title)
            } else if (!first) sb.append('\n')
            for (i in items) sb.append("\n- ").append(i)
            first = false
        }
        return sb.toString()
    }

    /** « 20 g de beurre », « 500 g d'eau », « 1 oignon », « sel, poivre » ; en anglais « 20 g butter ». */
    fun line(i: IngredientWire, french: Boolean): String {
        val qty = i.qty.trim()
        val unit = i.unit.trim()
        var name = i.name.trim()
        if (name.isEmpty()) return i.display.trim()
        if (french) name = lowerFirst(name)
        val sb = StringBuilder()
        if (qty.isNotEmpty()) sb.append(qty).append(' ')
        if (unit.isNotEmpty()) {
            sb.append(unit).append(' ')
            if (french) sb.append(if (elides(name)) "d'" else "de ")
        }
        sb.append(name)
        if (i.note.isNotBlank()) sb.append(", ").append(i.note.trim())
        return sb.toString()
    }

    /** « Beurre » → « beurre », mais pas un sigle (« AOP ») ni un nom propre écrit en capitales. */
    private fun lowerFirst(s: String): String {
        val c = s[0]
        if (!c.isUpperCase() || (s.length > 1 && !s[1].isLowerCase())) return s
        return c.lowercase(Locale.FRENCH) + s.substring(1)
    }

    /** « d' » devant une voyelle et quelques h muets courants (huile, herbes) ; « de » sinon (haricots). */
    private fun elides(name: String): Boolean {
        val n = name.lowercase(Locale.FRENCH)
        return n[0] in "aeiouàâäéèêëîïôöùûüœæ" || H_MUET.any { n.startsWith(it) }
    }

    private val H_MUET = listOf("hui", "herbe")
}
