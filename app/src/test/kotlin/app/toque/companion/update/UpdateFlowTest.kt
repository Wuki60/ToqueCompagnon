package app.toque.companion.update

import app.toque.core.update.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mises à jour du compagnon : vérification au démarrage et à la demande, installation (source inventée). */
class UpdateFlowTest {
    private class Source(var release: Release? = null, var fail: String? = null) : UpdateSource {
        var checks = 0
        var installed: Any? = null
        override fun latest(): Release {
            checks++
            fail?.let { throw UpdateFailure(it) }
            return release ?: throw UpdateFailure("aucune")
        }
        override fun download(r: Release, progress: (Int) -> Unit): Any { progress(50); progress(100); return "apk-" + r.version }
        override fun install(apk: Any) { installed = apk }
    }

    private class Prefs(override var autoCheck: Boolean = true, override var lastCheckMs: Long = 0) : UpdatePrefs

    private fun release(v: String, url: String? = "https://github.com/Wuki60/ToqueCompagnon/releases/download/v$v/ToqueCompagnon-$v.apk") =
        Release("v$v", v, "Toque Compagnon $v", "Notes $v", url?.let { "ToqueCompagnon-$v.apk" }, url, 1000)

    private var now = 10_000_000L
    private fun flow(source: Source, prefs: Prefs = Prefs()) =
        UpdateFlow("0.5.0", source, prefs, background = { it() }, main = { it() }, clock = { now })

    @Test fun offersANewerVersionAtStartOnce() {
        val s = Source(release("0.5.1"))
        val f = flow(s)
        var offers = 0
        f.offer = { offers++ }
        f.start()
        assertEquals(UpdatePhase.AVAILABLE, f.view.phase)
        assertEquals("0.5.1", f.view.version)
        assertEquals(1, offers)
        now += 60 * 60_000L
        f.start()
        assertEquals(1, offers)
        f.install()
        assertEquals("apk-0.5.1", s.installed)
        assertEquals(UpdatePhase.INSTALLING, f.view.phase)
    }

    @Test fun startChecksAtMostEveryHalfHourAndNotWhenOff() {
        val s = Source(release("0.5.0"))
        val prefs = Prefs()
        val f = flow(s, prefs)
        f.start()
        assertEquals(UpdatePhase.UP_TO_DATE, f.view.phase)
        f.start()
        assertEquals(1, s.checks)
        now += 31 * 60_000L
        f.start()
        assertEquals(2, s.checks)
        f.setAutoCheck(false)
        assertTrue(!prefs.autoCheck)
        now += 31 * 60_000L
        f.start()
        assertEquals(2, s.checks)
        // À la demande, toujours.
        f.check()
        assertEquals(3, s.checks)
    }

    @Test fun failureIsSilentAtStartButShownOnDemand() {
        val s = Source(fail = "GitHub limite les vérifications")
        val f = flow(s)
        f.start()
        assertEquals(UpdatePhase.IDLE, f.view.phase)
        f.check()
        assertEquals(UpdatePhase.ERROR, f.view.phase)
        assertEquals("GitHub limite les vérifications", f.view.error)
    }

    @Test fun versionWithoutApk() {
        val f = flow(Source(release("0.6.0", url = null)))
        var offered = false
        f.offer = { offered = true }
        f.start()
        assertEquals(UpdatePhase.IDLE, f.view.phase)
        assertTrue(!offered)
        f.check()
        assertEquals(UpdatePhase.ERROR, f.view.phase)
    }

    @Test fun installerRefusalCanBeRetried() {
        val s = Source(release("0.5.2"))
        val f = flow(s)
        f.check()
        f.install()
        f.installFailed("Installation annulée")
        assertEquals(UpdatePhase.ERROR, f.view.phase)
        s.installed = null
        f.retry()
        assertEquals("apk-0.5.2", s.installed)
    }

    @Test fun nothingToInstallWithoutAVersion() {
        val s = Source(release("0.5.0"))
        val f = flow(s)
        f.install()
        assertNull(s.installed)
    }
}
