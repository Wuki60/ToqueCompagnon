package app.toque.companion

import app.toque.core.i18n.I18n
import app.toque.core.i18n.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Vérifie les fichiers de langue du compagnon (app/src/main/assets/lang/<code>.json) par rapport aux
 * textes passés à tr()/trKey() dans le code de l'appli et de core : l'anglais doit être complet ;
 * toute langue ne doit contenir que des textes existants, avec les mêmes paramètres (%s, %d…).
 * Un texte manquant reste en français. Même lecture du code que I18nTest dans le dépôt de Toque.
 */
class LanguageFilesTest {
    // Les tests tournent dans le dossier du module (app/).
    private val roots = listOf(File("src/main/kotlin"), File("../core/src/main/kotlin"))
    private val langDir = File("src/main/assets/lang")
    private val call = Regex("""\btr(?:Key)?\(\s*""")
    private val placeholder = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")

    private fun languageFiles(): List<Language> {
        assertTrue("dossier introuvable : ${langDir.absolutePath}", langDir.isDirectory)
        return langDir.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }
            .map { I18n.parse(it.name.removeSuffix(".json"), it.readText()) }
    }

    /** Textes du code → fichiers où ils apparaissent. */
    private fun sourceKeys(): Map<String, MutableList<String>> {
        val keys = LinkedHashMap<String, MutableList<String>>()
        for (root in roots) {
            assertTrue("dossier introuvable : ${root.absolutePath}", root.isDirectory)
            root.walk().filter { it.extension == "kt" }.forEach { f ->
                val src = f.readText()
                for (m in call.findAll(src)) {
                    var i = m.range.last + 1
                    if (src[i] != '"') continue
                    val key = StringBuilder()
                    while (true) {
                        val (raw, end) = literal(src, i)
                        assertTrue("gabarit \$ dans un texte à traduire (${f.name}) : $raw", !Regex("""(?<!\\)\$[{A-Za-z_]""").containsMatchIn(raw))
                        key.append(unescape(raw))
                        var k = end
                        while (src[k].isWhitespace()) k++
                        if (src[k] == '+') {
                            k++
                            while (src[k].isWhitespace()) k++
                            if (src[k] == '"') { i = k; continue }
                        }
                        break
                    }
                    keys.getOrPut(key.toString()) { ArrayList() } += f.name
                }
            }
        }
        return keys
    }

    /** Littéral entre guillemets à partir de [start] (sur le guillemet ouvrant) : brut, et la position après lui. */
    private fun literal(src: String, start: Int): Pair<String, Int> {
        var j = start + 1
        val b = StringBuilder()
        while (true) {
            val ch = src[j]
            if (ch == '\\') { b.append(src, j, j + 2); j += 2; continue }
            if (ch == '"') break
            b.append(ch); j++
        }
        return b.toString() to j + 1
    }

    private fun unescape(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\') {
                b.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> s[i + 1] })
                i += 2
            } else { b.append(ch); i++ }
        }
        return b.toString()
    }

    private fun params(s: String) = placeholder.findAll(s).map { it.value }.toList()

    @Test fun languageFilesMatchTheCode() {
        val keys = sourceKeys()
        assertTrue("trop peu de textes trouvés (${keys.size})", keys.size > 300)
        val files = languageFiles()
        assertTrue("lang/en.json manquant", files.any { it.code == "en" })
        for (l in files) {
            val unknown = l.texts.keys.filter { it !in keys }
            assertEquals("${l.code}.json : textes inconnus dans le code :\n" + unknown.joinToString("\n"), 0, unknown.size)
            val bad = l.texts.filter { (fr, t) -> params(fr) != params(t) }
            assertEquals("${l.code}.json : paramètres différents :\n" + bad.entries.joinToString("\n"), 0, bad.size)
            val missing = keys.keys.filter { it !in l.texts }
            if (l.code == "en") assertEquals("en.json : textes sans traduction :\n" + missing.joinToString("\n"), 0, missing.size)
            else if (missing.isNotEmpty()) println("${l.code}.json : ${missing.size} texte(s) restent en français")
        }
    }
}
