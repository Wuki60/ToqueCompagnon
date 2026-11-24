package app.toque.companion.flows

import app.toque.core.companion.StateWire
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Planifie [Runnable] dans [Long] ms sur le fil principal ; renvoie de quoi l'annuler. */
typealias Scheduler = (Long, Runnable) -> () -> Unit

/**
 * Heures du robot vues sur l'horloge du téléphone. Le robot date tout avec sa propre horloge ;
 * l'écart avec le téléphone se mesure sur le dernier état reçu ([StateWire.serverTime] au moment
 * où il est arrivé). Sans état, l'heure du robot est prise telle quelle.
 */
class PhoneClock(
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    val zone: () -> TimeZone = { TimeZone.getDefault() },
) {
    @Volatile private var skewMs = 0L

    /** Dernier état reçu : écart entre l'horloge du robot et celle du téléphone. */
    fun observe(s: StateWire, receivedWall: Long = wallMs()) {
        if (s.serverTime > 0) skewMs = s.serverTime - receivedWall
    }

    fun now(): Long = wallMs()

    /** Heure du téléphone correspondant à l'heure [robotWall] du robot. */
    fun phone(robotWall: Long): Long = robotWall - skewMs

    /** « 12:02 » (vide si [robotWall] est inconnu). */
    fun hhmm(robotWall: Long): String = if (robotWall <= 0) "" else format("HH:mm", Locale.ROOT, phone(robotWall))

    fun format(pattern: String, locale: Locale, phoneWall: Long): String {
        val f = SimpleDateFormat(pattern, locale)
        f.timeZone = zone()
        return f.format(Date(phoneWall))
    }
}
