package app.toque.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture des versions du compagnon publiées sur GitHub (réponses inventées). */
class CompanionReleasesTest {
    private fun json(tag: String, vararg assets: Pair<String, String>) = """
        {"tag_name": "$tag", "name": "Toque Compagnon $tag", "body": "Notes",
         "assets": [${assets.joinToString(",") { (n, u) -> """{"name": "$n", "size": 1234, "browser_download_url": "$u"}""" }}]}
    """.trimIndent()

    @Test fun picksTheCompanionApk() {
        val r = CompanionReleases.parse(json("v0.5.0",
            "notes.txt" to "https://github.com/Wuki60/ToqueCompagnon/releases/download/v0.5.0/notes.txt",
            "ToqueCompagnon-0.5.0.apk" to "https://github.com/Wuki60/ToqueCompagnon/releases/download/v0.5.0/ToqueCompagnon-0.5.0.apk"))
        assertEquals("0.5.0", r.version)
        assertEquals("ToqueCompagnon-0.5.0.apk", r.apkName)
        assertEquals(1234L, r.apkSize)
        assertEquals("Notes", r.notes)
    }

    @Test fun refusesAnApkOutsideGitHub() {
        val r = CompanionReleases.parse(json("v0.5.1", "ToqueCompagnon-0.5.1.apk" to "https://example.com/ToqueCompagnon-0.5.1.apk"))
        assertNull(r.apkUrl)
        assertNull(r.apkName)
        assertFalse(CompanionReleases.trusted("http://github.com/x.apk"))
        assertFalse(CompanionReleases.trusted("https://github.com.evil.example/x.apk"))
        assertTrue(CompanionReleases.trusted("https://objects.githubusercontent.com/x"))
    }

    @Test fun versions() {
        assertTrue(CompanionReleases.isNewer("0.5.1", "0.5.0"))
        assertTrue(CompanionReleases.isNewer("v0.10.0", "0.9.9"))
        assertFalse(CompanionReleases.isNewer("0.5.0", "0.5.0"))
        assertFalse(CompanionReleases.isNewer("0.4.1", "0.5.0"))
    }

    @Test(expected = IllegalArgumentException::class) fun unreadable() {
        CompanionReleases.parse("[]")
    }
}
