package app.toque.companion.flows

import app.toque.companion.ui.Fmt
import app.toque.companion.ui.screens.ReglageRow
import app.toque.companion.ui.screens.ReglageState
import app.toque.core.companion.StepSettingsWire
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualSettings
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.StepSettings

/**
 * Le réglage du robot en cours de choix dans la feuille « Réglage du robot » d'une étape : son genre
 * ([program], null : aucun), ses valeurs ([manual] pour la cuisson manuelle, avec les règles de saisie de
 * l'écran du robot, [ManualSettings] ; [other] pour un programme) et la dernière correction automatique
 * ([message]). Les bornes sont celles que le robot annonce ([StepSettingsWire]) : rien n'est fixé ici, et
 * le robot juge encore à l'enregistrement. Pur, immuable.
 */
data class SettingChoice(
    val program: StepProgram? = null,
    val manual: ManualSettings? = null,
    val other: StepMachine? = null,
    val message: String = "",
    /** Réglage enregistré connu seulement par son résumé (brouillon de 0.3.9) : rien de choisi, « Utiliser » ne change rien. */
    val keep: Boolean = false,
) {
    /** Le réglage d'étape choisi (null : aucun). */
    val machine: StepMachine? get() = when (program) {
        null -> null
        StepProgram.MANUAL -> manual?.let { StepSettings.manualPicked(it) }
        else -> other
    }

    /**
     * Le genre [p] touché (null : aucun) : le même garde ses valeurs ; un autre part du réglage de départ que
     * le robot annonce, comme son éditeur d'étape. Un genre que le robot ne propose pas ne se choisit pas.
     */
    fun pick(settings: StepSettingsWire, p: StepProgram?): SettingChoice {
        if (p == program && !keep) return copy(message = "")
        if (p == null) return SettingChoice()
        val k = settings.kind(p) ?: return this
        return if (p == StepProgram.MANUAL) SettingChoice(p, StepSettings.manualStart(k.default, settings.manual.limits()))
        else SettingChoice(p, other = k.default)
    }

    /** Un cran de plus ([dir] = 1) ou de moins (−1) sur [field] (seconds, tempC, speed, pulses). */
    fun step(settings: StepSettingsWire, field: String, dir: Int): SettingChoice {
        val m = manual
        if (program == StepProgram.MANUAL && m != null) {
            val card = when (field) {
                StepSettings.SECONDS -> ManualSettings.CARD_TIME
                StepSettings.TEMP -> ManualSettings.CARD_TEMP
                StepSettings.SPEED -> ManualSettings.CARD_SPEED
                else -> return this
            }
            val e = m.change(card, dir)
            return copy(manual = e.settings, message = e.messages.lastOrNull() ?: "")
        }
        val k = settings.kind(program) ?: return this
        val o = other ?: return this
        if (field !in k.params) return this
        val d = if (dir > 0) 1 else -1
        val n = when (field) {
            StepSettings.SECONDS -> o.copy(seconds = o.seconds + d * (if (k.secondsStep > 0) k.secondsStep else 60))
            StepSettings.TEMP -> o.copy(tempC = o.tempC + d)
            StepSettings.PULSES -> o.copy(pulses = o.pulses + d)
            else -> return this
        }
        // Dès qu'on y touche, le réglage est celui de l'éditeur du robot : valeurs dans ses bornes, et le reste
        // comme lui (un pétrissage Lidl n'a pas de vitesse, un Turbo Lidl compte en secondes : le robot les
        // refuserait tels quels).
        return copy(other = k.fit(n), message = "")
    }

    /**
     * Le réglage choisi est celui enregistré ([stored]) : rien à envoyer, rien à confirmer. Pour un programme,
     * seul compte ce que l'éditeur du robot y règle (revenir à la même durée, c'est ne rien avoir changé).
     */
    fun unchanged(settings: StepSettingsWire, stored: StepMachine?): Boolean {
        val m = machine
        if (m == stored) return true
        if (m == null || stored == null || program == StepProgram.MANUAL) return false
        return settings.kind(program)?.same(m, stored) == true
    }

    /** Sens de rotation (cuisson manuelle) ; sans mélange, rien ne change. */
    fun reverse(r: Boolean): SettingChoice {
        val m = manual ?: return this
        val e = m.withReverse(r) ?: return this
        return copy(manual = e.settings, message = e.messages.lastOrNull() ?: "")
    }

    /** Lame ou fouet (cuisson manuelle). */
    fun whisk(w: Boolean): SettingChoice {
        val m = manual ?: return this
        val e = m.withAccessory(if (w) Accessory.WHISK else Accessory.BLADE)
        return copy(manual = e.settings, message = e.messages.lastOrNull() ?: "")
    }

    companion object {
        /** [ReglageState.selected] d'un réglage connu seulement par son résumé : aucune puce choisie. */
        const val KEEP = "?"

        /** La feuille ouverte sur le réglage [current] de l'étape (null : aucun). */
        fun start(settings: StepSettingsWire, current: StepMachine?): SettingChoice = when (current?.program) {
            null -> SettingChoice()
            StepProgram.MANUAL -> SettingChoice(StepProgram.MANUAL, StepSettings.manualStart(current, settings.manual.limits()))
            else -> SettingChoice(current.program, other = current)
        }

        /**
         * La feuille telle que l'écran la montre : les genres (« Aucun », ceux que le robot propose, et celui de
         * l'étape s'il ne le propose pas), les crans du genre choisi, le rappel de l'éditeur du robot. [stored] :
         * le réglage enregistré de l'étape (null : aucun, ou étape nouvelle) ; [shown] : le résumé montré sur
         * l'étape (celui d'un réglage connu seulement par son résumé, [keep]).
         */
        fun state(number: Int, settings: StepSettingsWire, c: SettingChoice, stored: StepMachine?, shown: String?, canRestore: Boolean): ReglageState {
            val kinds = ArrayList<Pair<String, String>>()
            kinds += "" to tr("Aucun")
            for (k in settings.kinds) kinds += k.program to k.label
            val p = c.program
            if (p != null && settings.kind(p) == null) kinds += p.key to p.label
            if (c.keep) return ReglageState(number, kinds, KEEP, shown ?: tr("Aucun réglage"), canRestore = canRestore)
            val m = c.machine
            val rows = ArrayList<ReglageRow>()
            var reverse = -1
            var dimmed = false
            var whisk = -1
            var hint = ""
            val manual = c.manual
            val kind = settings.kind(p)
            when {
                p == null -> hint = tr("Étape sans le robot : couper, verser, réserver…")
                kind == null -> hint = tr("Ce robot ne propose pas ce réglage : choisissez-en un autre, ou gardez-le tel quel.")
                p == StepProgram.MANUAL && manual != null -> {
                    fun ok(field: String, dir: Int): Boolean {
                        val n = c.step(settings, field, dir)
                        return n.manual != manual || n.message.isNotEmpty()
                    }
                    rows += ReglageRow(StepSettings.SECONDS, tr("Durée"), Fmt.clock(manual.seconds), "max ${manual.maxSeconds / 60} min",
                        ok(StepSettings.SECONDS, -1), ok(StepSettings.SECONDS, 1))
                    val t = manual.tempC
                    rows += ReglageRow(StepSettings.TEMP, tr("Température"), if (t > 0) tr("%d °C", t) else "—", Fmt.tempName(t),
                        ok(StepSettings.TEMP, -1), ok(StepSettings.TEMP, 1), tempC = t)
                    val speedNote = Fmt.speedName(manual.speed) +
                        if (manual.tempLevel > 0) " · " + tr("%d maximum en chauffe", manual.limits.maxSpeedHeated) else ""
                    rows += ReglageRow(StepSettings.SPEED, tr("Vitesse"), "${manual.speed}", speedNote, ok(StepSettings.SPEED, -1), ok(StepSettings.SPEED, 1))
                    if (settings.manual.reverse) {
                        reverse = if (manual.reverse) 1 else 0
                        dimmed = manual.speed == 0
                    }
                    if (Accessory.WHISK.name in settings.manual.accessories) whisk = if (manual.accessory == Accessory.WHISK) 1 else 0
                    if (manual.speed == 0 && manual.tempLevel > 0) hint = tr("Sans mélange : surveillez la cuisson")
                }
                m != null -> {
                    if (StepSettings.SECONDS in kind.params) rows += ReglageRow(StepSettings.SECONDS, tr("Durée"), Fmt.clock(m.seconds), "",
                        m.seconds > kind.minSeconds, m.seconds < kind.maxSeconds)
                    if (StepSettings.TEMP in kind.params) rows += ReglageRow(StepSettings.TEMP, tr("Température"), tr("%d °C", m.tempC), "",
                        m.tempC > kind.minTempC, m.tempC < kind.maxTempC, tempC = m.tempC)
                    if (StepSettings.PULSES in kind.params) rows += ReglageRow(StepSettings.PULSES, tr("Impulsions"), "× ${m.pulses}", "",
                        m.pulses > kind.minPulses, m.pulses < kind.maxPulses)
                    hint = kind.hint
                }
            }
            return ReglageState(number, kinds, p?.key ?: "", m?.summary ?: tr("Aucun réglage"), rows, reverse, dimmed, whisk, hint, c.message,
                confirm = m != null && !c.unchanged(settings, stored), canRestore = canRestore)
        }
    }
}
