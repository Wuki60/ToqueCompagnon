package app.toque.companion.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le diagnostic des alertes : borné, et jamais de secret ni d'adresse. */
class DiagLogTest {
    @Test fun keepsOnlyTheLastLines() {
        var t = 0L
        val written = ArrayList<String>()
        val d = DiagLog(capacity = 5, wallMs = { t }, format = { "t$it" })
        d.onLine = { written += it }
        for (i in 1..12) {
            t = i.toLong()
            d.add("ligne $i")
        }
        assertEquals(listOf("t8  ligne 8", "t9  ligne 9", "t10  ligne 10", "t11  ligne 11", "t12  ligne 12"), d.lines())
        assertEquals(12, written.size)
        d.restore((1..9).map { "ancienne $it" })
        assertEquals(listOf("ancienne 5", "ancienne 6", "ancienne 7", "ancienne 8", "ancienne 9"), d.lines())
    }

    @Test fun noTokenSecretOrAddress() {
        val token = "3f9a0c" + "b1".repeat(29)
        val d = DiagLog(format = { "" })
        d.add("échec vers 192.168.1.12:8088 avec Bearer $token")
        d.add("connect failed: /fe80::1c2b:3d4e:5f60:7182 secret=Ab12Cd34Ef56Gh78Ij90")
        d.add("ligne 39 (job_finished) écartée : trop ancienne (130 s)\nsuite")
        val text = d.text()
        assertFalse(text, text.contains("192.168"))
        assertFalse(text, text.contains(token))
        assertFalse(text, text.contains("Ab12Cd34Ef56Gh78Ij90"))
        assertFalse(text, text.contains("fe80"))
        assertTrue(text, text.contains("ligne 39 (job_finished) écartée : trop ancienne (130 s) suite"))
        assertEquals(3, d.lines().size)
        // Les heures et les mots ordinaires restent lisibles.
        assertEquals("fin à 19:04:13, programme MANUAL RUNNING", DiagLog.scrub("fin à 19:04:13, programme MANUAL RUNNING"))
    }
}
