package app.toque.companion.flows

import app.toque.core.companion.EditGroupWire
import app.toque.core.companion.EditStepRequest
import app.toque.core.companion.EditableRecipe
import app.toque.core.companion.RecipeEditRequest
import app.toque.core.companion.SettingEdit
import app.toque.core.companion.SettingWire
import app.toque.core.json.Json
import app.toque.core.recipes.StepMachine
import java.io.File
import java.security.MessageDigest

/** Un groupe d'ingrédients du brouillon : son titre (celui d'une recette copiée, jamais créé ici) et ses lignes. */
data class DraftGroup(val title: String = "", val items: List<String> = emptyList())

/**
 * Une étape du brouillon. [from] : l'étape enregistrée sur le robot d'où elle vient (null : écrite ici) ;
 * [machine] : le résumé de son réglage enregistré, tel que le robot l'écrit (null sans réglage) ; [review] :
 * à vérifier sur le robot avant le premier lancement. [stored] : ce réglage en entier (null sans réglage,
 * ou brouillon de 0.3.9 qui ne l'avait pas). [edit] : le réglage tapé ici (null : pas touché, le robot
 * garde celui de l'étape d'origine ; [SettingEdit] de null : retiré).
 */
data class DraftStep(
    val from: Int? = null,
    val text: String = "",
    val grams: Int = 0,
    val machine: String? = null,
    val review: Boolean = false,
    val stored: StepMachine? = null,
    val edit: SettingEdit? = null,
) {
    /** Le réglage qui partira (tapé ici, sinon celui enregistré), null sans réglage ou s'il n'est pas connu en entier. */
    val setting: StepMachine? get() = if (edit != null) edit.machine else stored

    /** Un réglage tapé ici, différent de celui enregistré : à confirmer sur le robot avant le premier lancement. */
    val typed: Boolean get() = edit?.machine != null && edit.machine != stored

    /** Le réglage enregistré retiré ici. */
    val removed: Boolean get() = edit != null && edit.machine == null && (stored != null || machine != null)

    /** Résumé du réglage montré (celui du robot quand il n'est connu que par son résumé), null sans réglage. */
    val summary: String? get() = setting?.summary ?: if (edit == null) machine else null
}

/**
 * Une recette en cours d'écriture sur le téléphone. [recipeId] null : pas encore créée sur le robot
 * ([draftId] la reconnaît si la réponse de la création s'est perdue). [version] : la version du robot
 * sur laquelle les changements ont été faits. Gardée sur le téléphone jusqu'à ce que le robot l'ait enregistrée.
 */
data class Draft(
    val draftId: String,
    val recipeId: String? = null,
    val version: String? = null,
    val title: String = "",
    val servings: Int = 4,
    val minutes: Int = 0,
    val difficulty: Int = 1,
    val groups: List<DraftGroup> = emptyList(),
    val steps: List<DraftStep> = emptyList(),
    /** Dernier changement (horloge du téléphone). */
    val changedAt: Long = 0,
) {
    /** Clé du fichier : la recette du robot, ou le brouillon d'une recette nouvelle. */
    val key: String get() = recipeId ?: "new:$draftId"

    /** Ingrédients à plat (groupe, rang dans le groupe). */
    val lines: List<Pair<Int, Int>> get() = groups.flatMapIndexed { g, group -> group.items.indices.map { g to it } }

    /**
     * La demande au robot : lignes vides retirées ; chaque étape dit son étape d'origine, et porte « machine »
     * seulement si son réglage a été touché ici (sans lui, le robot garde celui de l'étape d'origine).
     */
    fun request(): RecipeEditRequest = RecipeEditRequest(
        version = if (recipeId != null) version else null,
        draft = if (recipeId == null) draftId else null,
        title = title.trim(), servings = servings, totalMinutes = minutes, difficulty = difficulty,
        ingredients = groups.map { g -> EditGroupWire(g.title, g.items.map { it.trim() }.filter { it.isNotEmpty() }) }.filter { it.items.isNotEmpty() },
        steps = steps.map { EditStepRequest(it.from, it.text.trim(), it.grams, it.edit) },
    )

    fun toMap(): Map<String, Any?> = linkedMapOf("draftId" to draftId, "recipeId" to recipeId, "version" to version, "title" to title,
        "servings" to servings, "minutes" to minutes, "difficulty" to difficulty,
        "groups" to groups.map { linkedMapOf("title" to it.title, "items" to it.items) },
        "steps" to steps.map { st ->
            linkedMapOf<String, Any?>("from" to st.from, "text" to st.text, "grams" to st.grams, "machine" to st.machine, "review" to st.review).apply {
                if (st.stored != null) put("stored", SettingWire.toMap(st.stored))
                // Réglage tapé ici : {"machine": {...}} ou {"machine": null} (retiré) ; absent : pas touché.
                if (st.edit != null) put("edit", linkedMapOf("machine" to st.edit.machine?.let { SettingWire.toMap(it) }))
            }
        },
        "changedAt" to changedAt)

    companion object {
        /** La recette telle que le robot l'a enregistrée, prête à modifier. */
        fun of(e: EditableRecipe, draftId: String): Draft = Draft(draftId, e.id, e.version, e.title, e.servings, e.totalMinutes, e.difficulty,
            e.ingredients.map { DraftGroup(it.title, it.items) },
            e.steps.map { DraftStep(it.index, it.text, it.grams, it.machine?.summary?.ifBlank { null }, it.machine != null && it.needsReview,
                SettingWire.of(it.machine)) })

        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): Draft? {
            val id = m["draftId"] as? String ?: return null
            fun i(k: String, d: Int) = (m[k] as? Number)?.toInt() ?: d
            return Draft(id, m["recipeId"] as? String, m["version"] as? String, m["title"] as? String ?: "", i("servings", 4), i("minutes", 0),
                i("difficulty", 1),
                (m["groups"] as? List<*>).orEmpty().mapNotNull { g ->
                    val gm = g as? Map<String, Any?> ?: return@mapNotNull null
                    DraftGroup(gm["title"] as? String ?: "", (gm["items"] as? List<*>).orEmpty().mapNotNull { it as? String })
                },
                (m["steps"] as? List<*>).orEmpty().mapNotNull { s ->
                    val sm = s as? Map<String, Any?> ?: return@mapNotNull null
                    // 0.3.9 n'écrivait ni « stored » ni « edit » : rien de tapé, le robot garde chaque réglage.
                    val em = sm["edit"] as? Map<String, Any?>
                    val edit = when {
                        em == null -> null
                        em["machine"] == null -> SettingEdit(null)
                        else -> SettingWire.read(em["machine"] as? Map<String, Any?>)?.let { SettingEdit(it) }
                    }
                    DraftStep((sm["from"] as? Number)?.toInt(), sm["text"] as? String ?: "", (sm["grams"] as? Number)?.toInt() ?: 0,
                        sm["machine"] as? String, sm["review"] as? Boolean ?: false, SettingWire.read(sm["stored"] as? Map<String, Any?>), edit)
                },
                (m["changedAt"] as? Number)?.toLong() ?: 0L)
        }

        /**
         * Garder le brouillon sur la version que le robot a maintenant ([e]) : chaque étape à réglage enregistré
         * (pas touché ici) retrouve l'étape du robot qui a le même réglage (de préférence au même rang) ; si le
         * robot ne l'a plus, elle reste avec son texte, sans réglage. Une étape au réglage tapé ici le garde et
         * devient une étape écrite ici (le robot le jugera). Les autres étapes deviennent des étapes écrites ici ;
         * un titre de groupe que le robot n'a plus rejoint les ingrédients sans groupe.
         */
        fun rebase(d: Draft, e: EditableRecipe): Draft {
            val used = HashSet<Int>()
            val steps = d.steps.map { s ->
                if (s.edit != null) return@map s.copy(from = null, machine = null, review = false, stored = null)
                if (s.from == null || s.machine == null) return@map s.copy(from = null, machine = null, review = false, stored = null)
                val same = e.steps.filter { it.machine?.summary == s.machine && it.index !in used }
                val pick = same.firstOrNull { it.index == s.from } ?: same.firstOrNull()
                if (pick == null) s.copy(from = null, machine = null, review = false, stored = null)
                else {
                    used += pick.index
                    s.copy(from = pick.index, review = pick.needsReview, stored = SettingWire.of(pick.machine))
                }
            }
            val titles = e.ingredients.map { it.title }.toSet()
            val groups = d.groups.map { if (it.title.isEmpty() || it.title in titles) it else it.copy(title = "") }
            return d.copy(version = e.version, steps = steps, groups = groups)
        }
    }
}

/**
 * Brouillons gardés sur le téléphone, un fichier par recette en cours d'écriture (une recette nouvelle
 * comprise), dans [dir] (`noBackupFilesDir/brouillons` : ni sauvegarde ni transfert). Écrit dans un
 * fichier temporaire puis renommé : un arrêt brutal ne laisse jamais un brouillon à moitié écrit.
 */
class DraftStore(private val dir: File) {
    private fun file(key: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder("d-")
        for (i in 0 until 12) sb.append(String.format(java.util.Locale.ROOT, "%02x", h[i].toInt() and 0xFF))
        return File(dir, "$sb.json")
    }

    @Synchronized fun save(d: Draft) {
        try {
            dir.mkdirs()
            val f = file(d.key)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Json.write(d.toMap()))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized fun load(key: String): Draft? = read(file(key))?.takeIf { it.key == key }

    @Synchronized fun delete(key: String) {
        file(key).delete()
    }

    /** Le dernier brouillon d'une recette nouvelle (pas encore enregistrée sur le robot), ou null. */
    @Synchronized fun latestNew(): Draft? = all().filter { it.recipeId == null }.maxByOrNull { it.changedAt }

    @Synchronized fun all(): List<Draft> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("d-") && f.name.endsWith(".json") }.orEmpty().mapNotNull { read(it) }

    private fun read(f: File): Draft? = try {
        if (!f.isFile) null else {
            @Suppress("UNCHECKED_CAST")
            (Json.parse(f.readText()) as? Map<String, Any?>)?.let { Draft.fromMap(it) }
        }
    } catch (_: Exception) {
        null
    }
}
