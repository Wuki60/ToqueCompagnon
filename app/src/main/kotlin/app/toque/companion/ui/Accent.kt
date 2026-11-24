package app.toque.companion.ui

/**
 * Teintes tirées d'une couleur d'accent, posées sur les couleurs de fond données (jamais figées ici :
 * un thème clair n'aura qu'à passer ses propres fonds).
 */
data class AccentShades(
    /** L'accent lui-même, en fond (bouton principal, puce choisie, pastille pleine, anneau). */
    val accent: Int,
    /** L'accent en texte ou en icône sur les surfaces : [accent] sur le sombre, plus foncé sur le clair. */
    val ink: Int,
    /** Accent transparent à 20 % (fond discret d'une note ou d'une pastille). */
    val dim: Int,
    /** Étape faite : accent à 45 % sur le fond de l'écran. */
    val done: Int,
    /** Fond discret d'un élément en cours : accent à 35 % sur le fond de l'écran. */
    val soft: Int,
    /** Contour d'une carte en cours : accent (encre sur le clair) à 60 % sur la surface des cartes. */
    val line: Int,
    /** Texte ou icône posé sur l'accent. */
    val onAccent: Int,
)

/**
 * La couleur du robot sur le téléphone (state.robot.accent) : lecture, mise en lisibilité et teintes.
 * Pur Kotlin, testé sur la JVM (AccentTest).
 */
object Accent {
    /** L'orange d'origine de Toque (le premier accent du robot). */
    const val DEFAULT = 0xFFFF8A3D.toInt()
    /** Texte sur l'orange d'origine, celui de la maquette ; les autres couleurs suivent la règle du robot ([onAccent]). */
    const val ON_DEFAULT = 0xFF1A0E05.toInt()
    /** Contraste minimal d'un texte (WCAG AA). */
    const val MIN_CONTRAST = 4.5

    // Parts de l'accent dans chaque teinte : avec l'orange d'origine, elles redonnent au pixel près
    // les teintes de la maquette (ACCENT_DONE 0xFF78492E, ACCENT_SOFT 0xFF603D2B, ACCENT_LINE 0xFFA15E37).
    const val DONE_PART = 0.45
    const val SOFT_PART = 0.35
    const val LINE_PART = 0.60
    /** Opacité de [AccentShades.dim] (0x33), comme le robot. */
    const val DIM_ALPHA = 0x33

    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    /** « #RRGGBB » → couleur opaque ; null pour tout le reste (robot plus ancien, texte illisible). */
    fun parse(s: String?): Int? {
        if (s == null || !HEX.matches(s)) return null
        return (0xFF shl 24) or s.substring(1).toInt(16)
    }

    /**
     * La couleur reçue, rendue lisible sur les [surfaces] : texte ou icône à [MIN_CONTRAST] au moins sur
     * chacune, et texte [onAccent] à [MIN_CONTRAST] sur elle. Une couleur trop sombre (#1A237E…) est
     * éclaircie vers le blanc par pas de 5 % jusqu'à y arriver : elle garde sa teinte. Les dix couleurs
     * du robot passent telles quelles.
     */
    fun readable(color: Int, surfaces: IntArray): Int {
        val c = color or (0xFF shl 24)
        for (step in 0..20) {
            val t = if (step == 0) c else blend(c, 0xFFFFFFFF.toInt(), step * 0.05)
            if (surfaces.all { contrast(t, it) >= MIN_CONTRAST } && contrast(onAccent(t), t) >= MIN_CONTRAST) return t
        }
        return DEFAULT
    }

    /** Texte posé sur [accent] : la règle du robot ([robotRule]) ; l'orange garde celui de la maquette. */
    fun onAccent(accent: Int): Int = if (accent == DEFAULT) ON_DEFAULT else robotRule(accent)

    /** 12 % de chaque canal, comme `Ui.setAccent` du robot (#1E1007 pour l'orange). */
    fun robotRule(accent: Int): Int {
        fun dark(shift: Int) = (((accent shr shift) and 0xFF) * 0.12f).toInt() shl shift
        return (0xFF shl 24) or dark(16) or dark(8) or dark(0)
    }

    /**
     * Les teintes de [color] (null : l'orange d'origine) sur le thème [p].
     * - Sombre : la couleur rendue lisible ([readable]) sert partout, fond comme texte.
     * - Clair : la couleur telle quelle pour les fonds, avec un texte posé dessus lisible (règle du robot,
     *   sinon le blanc) ; pour le texte et les icônes, une encre de même teinte assombrie jusqu'à
     *   [MIN_CONTRAST] sur les fonds clairs ([darkInk]).
     */
    fun look(color: Int?, p: Palette): AccentShades {
        if (!p.light) {
            val c = color?.let { readable(it, intArrayOf(p.bg, p.surface, p.surface2)) } ?: DEFAULT
            // Encre : la couleur, éclaircie s'il le faut pour rester lisible aussi sur SURFACE3 et sur le fond
            // discret de l'accent (l'orange y passe tel quel).
            val ink = towards(c, 0xFFFFFFFF.toInt(), intArrayOf(p.bg, p.surface, p.surface2, p.surface3, blend(p.surface2, c, DIM_ALPHA / 255.0)))
            return AccentShades(c, ink, dim(c), blend(p.bg, c, DONE_PART), blend(p.bg, c, SOFT_PART), blend(p.surface, c, LINE_PART), onAccent(c))
        }
        val c = color?.let { it or (0xFF shl 24) } ?: DEFAULT
        val ink = towards(c, 0xFF000000.toInt(), intArrayOf(p.bg, p.surface, p.surface2, p.surface3, blend(p.surface2, c, DIM_ALPHA / 255.0)))
        val rule = onAccent(c)
        val white = 0xFFFFFFFF.toInt()
        val on = if (contrast(rule, c) >= MIN_CONTRAST || contrast(rule, c) >= contrast(white, c)) rule else white
        return AccentShades(c, ink, dim(c), blend(p.bg, c, DONE_PART), blend(p.bg, c, SOFT_PART), blend(p.surface, ink, LINE_PART), on)
    }

    /** Sombre, orange d'origine : les teintes de la maquette (tests). */
    fun shades(accent: Int, bg: Int, surface: Int): AccentShades =
        AccentShades(accent, accent, dim(accent), blend(bg, accent, DONE_PART), blend(bg, accent, SOFT_PART), blend(surface, accent, LINE_PART), onAccent(accent))

    private fun dim(c: Int) = (c and 0x00FFFFFF) or (DIM_ALPHA shl 24)

    /** [color] menée vers [end] (noir ou blanc) par pas de 5 % jusqu'à [MIN_CONTRAST] sur chacun des [backs]. */
    fun towards(color: Int, end: Int, backs: IntArray): Int {
        for (step in 0..20) {
            val t = if (step == 0) color else blend(color, end, step * 0.05)
            if (backs.all { contrast(t, it) >= MIN_CONTRAST }) return t
        }
        return end
    }

    /** [top] posé à [part] (0…1) sur [base], canal par canal, arrondi ; opaque. */
    fun blend(base: Int, top: Int, part: Double): Int {
        fun ch(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val t = (top shr shift) and 0xFF
            return Math.round(b + (t - b) * part).toInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** Luminance relative (WCAG 2.x) d'une couleur opaque. */
    fun luminance(color: Int): Double {
        fun lin(shift: Int): Double {
            val v = ((color shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    /** Rapport de contraste (1 à 21) entre deux couleurs opaques. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
