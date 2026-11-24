package app.toque.companion.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le rythme du suivi permanent : attente, réessais, recherche du robot, alarmes. */
class FollowPacingTest {
    private var visible = false
    private var busy = false
    private var own: String? = "192.168.1.20"
    private var robot: String? = "192.168.1.12"
    private var now = 0L
    private val p = FollowPacing({ visible }, { busy }, { own }, { robot }, { now })

    @Test fun quietWheneverTheAppIsNotOnScreenLongWaitOnlyWhenIdle() {
        // (visible, busy) → (attente, quiet)
        val seen = ArrayList<String>()
        for (v in listOf(false, true)) for (b in listOf(false, true)) {
            visible = v
            busy = b
            seen += "$v/$b: ${p.waitSeconds()} ${p.quiet()}"
        }
        assertEquals(listOf(
            "false/false: 120 true",
            // Cuisson appli fermée : attente courte (minuteur recalé), mais la télémesure ne réveille pas.
            "false/true: 20 true",
            "true/false: 20 false",
            "true/true: 20 false",
        ), seen)
    }

    @Test fun backgroundRetriesBackOffToMinutes() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 60_000L, 60_000L, 300_000L, 300_000L), (1..9).map { p.backoffMs(it) })
        // À l'écran ou pendant une cuisson : 15 s au plus, comme avant.
        visible = true
        assertEquals(15_000L, p.backoffMs(40))
        visible = false
        busy = true
        assertEquals(15_000L, p.backoffMs(40))
    }

    @Test fun finderAfterThreeFailuresThenEveryFour() {
        assertEquals(listOf(3, 7, 11), (1..12).filter { p.locate(it) })
    }

    @Test fun scansTheNetworkOnlyOnTheRobotsSubnetAndRarely() {
        assertTrue(p.mayScan())
        now += 30 * 60_000L
        assertFalse(p.mayScan())
        now += 31 * 60_000L
        assertTrue(p.mayScan())
        // Wi-Fi d'un bureau (autre /24) : jamais en arrière-plan.
        now += 2 * 3_600_000L
        own = "10.0.4.7"
        assertFalse(p.mayScan())
        own = null
        assertFalse(p.mayScan())
        // À l'écran : comme avant, même ailleurs.
        own = "10.0.4.7"
        visible = true
        assertTrue(p.mayScan())
        assertTrue(p.mayScan())
        robot = null
        visible = false
        own = "192.168.1.20"
        assertFalse(p.mayScan())
    }

    @Test fun alarmsAndSubnets() {
        assertEquals(300_000L, p.alarmMs(reachable = false))
        assertEquals(900_000L, p.alarmMs(reachable = true))
        assertTrue(FollowPacing.sameSubnet24("192.168.1.20", "192.168.1.254"))
        assertFalse(FollowPacing.sameSubnet24("192.168.1.20", "192.168.2.20"))
        assertFalse(FollowPacing.sameSubnet24("192.168.1.20", "robot.local"))
    }
}
