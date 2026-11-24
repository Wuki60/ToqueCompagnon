package app.toque.companion.link

import app.toque.core.companion.StateWire

/**
 * Les états reçus, dans le diagnostic des alertes (400 lignes) : une ligne seulement quand quelque
 * chose qui compte change (programme et son état, étape de la recette, recette en attente, numéro du
 * journal), ou après un changement de la liaison ([reset]). Les autres états (température, poids,
 * minuteur recalé) sont comptés : au plus une ligne par [everyMs] dit combien sont arrivés, pour voir
 * que le suivi vit sans pousser dehors les lignes qui expliquent une alerte manquée.
 * Kotlin pur, sûr entre fils.
 */
class StateDiag(private val everyMs: Long = 60_000L) {
    private var lastKey: String? = null
    /** États reçus sans changement notable depuis la dernière ligne. */
    private var unchanged = 0
    /** Heure de la dernière ligne écrite (horloge de l'appelant). */
    private var lineAt = 0L

    /** Nouveau suivi, robot perdu ou retrouvé : le prochain état est écrit en entier. */
    @Synchronized
    fun reset() {
        lastKey = null
    }

    /** Les lignes à écrire pour l'état [s] reçu à [nowMs] (souvent aucune). */
    @Synchronized
    fun onState(s: StateWire, nowMs: Long): List<String> {
        val k = key(s)
        if (k != lastKey) {
            lastKey = k
            val after = if (unchanged > 0) " ($unchanged sans changement notable avant)" else ""
            unchanged = 0
            lineAt = nowMs
            return listOf(describe(s) + after)
        }
        unchanged++
        val span = nowMs - lineAt
        if (span < everyMs) return emptyList()
        val line = "$unchanged état(s) sans changement notable en ${span / 1000} s (dernier n° ${s.revision})"
        unchanged = 0
        lineAt = nowMs
        return listOf(line)
    }

    companion object {
        /** Ce qui compte pour les alertes et l'écran : le reste n'écrit pas de ligne. */
        fun key(s: StateWire): String = listOf(
            s.job?.kind.orEmpty(), s.job?.state.orEmpty(),
            s.session?.let { "${it.recipeId}:${it.current}:${it.waitingFor}" }.orEmpty(),
            s.waiting?.recipeId.orEmpty(), s.journalSeq,
        ).joinToString("|")

        fun describe(s: StateWire): String = "état n° ${s.revision}, journal ${s.journalSeq}, " +
            (s.job?.let { "programme ${it.kind} ${it.state}" } ?: "pas de programme") +
            (s.session?.let { ", recette étape ${it.current + 1}/${it.stepCount}" + if (it.waitingFor.isNotEmpty() && it.waitingFor != "nothing") " (attend : ${it.waitingFor})" else "" } ?: "") +
            (if (s.waiting != null) ", recette en attente sur le robot" else "")
    }
}
