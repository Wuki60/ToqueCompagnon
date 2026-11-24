package app.toque.companion.flows

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Le disque des photos : plafond par usage, écriture atomique, fichiers illisibles, un dossier par robot. */
class PhotoDiskTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000L
    /** Une « image » lisible commence par 7 (sur Android : les dimensions se lisent). */
    private val valid: (ByteArray) -> Boolean = { it.isNotEmpty() && it[0] == 7.toByte() }

    private fun bytes(n: Int, fill: Int = 1) = ByteArray(n) { if (it == 0) 7 else fill.toByte() }

    private fun disk(root: File = tmp.root, cap: Long = 1000, writer: (File, ByteArray) -> Unit = PhotoDisk::writeSynced) =
        PhotoDisk(root, cap, { clock }, valid, writer).also { it.select("robot-a") }

    private fun files(): List<File> = tmp.root.walkTopDown().filter { it.isFile }.toList()

    @Test fun overTheCapTheLeastRecentlyUsedGoFirst() {
        val d = disk()
        for (id in listOf("a", "b", "c")) {
            clock += 1000
            assertEquals(PhotoDisk.Written.NEW, d.write("robot-a", id, bytes(300)))
        }
        // « a » revue : « b » est maintenant la moins récemment utilisée.
        clock += 1000
        assertNotNull(d.read("robot-a", "a"))
        clock += 1000
        d.write("robot-a", "d", bytes(300))
        // 1200 > 1000 : ramené sous 900 en retirant « b » seule.
        assertFalse(d.has("robot-a", "b"))
        for (id in listOf("a", "c", "d")) assertTrue(id, d.has("robot-a", id))
        assertEquals(900L, d.bytes())
        assertEquals(3, files().size)

        // L'ordre survit au redémarrage (date de modification = dernier usage) : « c » sort ensuite.
        val again = disk()
        assertEquals(3, again.count())
        clock += 1000
        again.write("robot-a", "e", bytes(300))
        assertFalse(again.has("robot-a", "c"))
        for (id in listOf("a", "d", "e")) assertTrue(id, again.has("robot-a", id))
    }

    @Test fun anInterruptedWriteLeavesNoVisibleFile() {
        val d = disk(writer = { f, b ->
            f.writeBytes(b.copyOf(b.size / 2))
            throw IOException("coupure")
        })
        assertEquals(PhotoDisk.Written.FAILED, d.write("robot-a", "a", bytes(300)))
        assertFalse(d.has("robot-a", "a"))
        assertNull(d.read("robot-a", "a"))
        assertTrue(files().toString(), files().isEmpty())

        // Arrêt brutal pendant l'écriture : le fichier temporaire est effacé au prochain démarrage.
        val ok = disk()
        ok.write("robot-a", "b", bytes(100))
        val folder = files().single().parentFile
        File(folder, "0123456789abcdef0123456789abcdef.42.tmp").writeBytes(bytes(50))
        val again = disk()
        assertEquals(1, again.count())
        assertEquals(1, files().size)
        assertNull(again.read("robot-a", "a"))
    }

    @Test fun anUnreadableFileIsIgnoredAndRemoved() {
        val d = disk()
        d.write("robot-a", "a", bytes(100))
        d.write("robot-a", "b", bytes(100))
        val (one, two) = files()
        // L'un abîmé sur le disque (même taille, illisible), l'autre tronqué.
        one.writeBytes(ByteArray(100))
        two.writeBytes(bytes(10))
        val again = disk()
        assertNull(again.read("robot-a", "a"))
        assertNull(again.read("robot-a", "b"))
        assertFalse(again.has("robot-a", "a"))
        assertFalse(again.has("robot-a", "b"))
        assertTrue(files().isEmpty())
        // Des octets illisibles ne sont jamais gardés.
        assertEquals(PhotoDisk.Written.NOT_IMAGE, again.write("robot-a", "c", ByteArray(20)))
        assertFalse(again.has("robot-a", "c"))
    }

    @Test fun anotherRobotOrUnpairingDeletesThePhotos() {
        val d = disk()
        d.write("robot-a", "a", bytes(100))
        // Une écriture pour un autre robot que celui ouvert (réponse arrivée trop tard) est refusée.
        assertEquals(PhotoDisk.Written.REFUSED, d.write("robot-b", "x", bytes(100)))
        assertEquals(1, files().size)
        assertEquals(PhotoDisk.folderName("robot-a"), files().single().parentFile!!.name)
        assertFalse(files().single().path.contains("robot-a"))

        d.select("robot-b")
        assertTrue(files().isEmpty())
        assertFalse(d.has("robot-a", "a"))
        d.write("robot-b", "x", bytes(100))
        assertTrue(d.has("robot-b", "x"))

        d.select(null)
        assertTrue(tmp.root.listFiles()!!.isEmpty())
        assertFalse(d.has("robot-b", "x"))
        assertEquals(PhotoDisk.Written.REFUSED, d.write(null, "x", bytes(100)))

        // Au démarrage, appairé à un autre robot : les photos du premier partent aussi.
        disk().write("robot-a", "a", bytes(100))
        PhotoDisk(tmp.root, valid = valid).select("robot-c")
        assertTrue(files().isEmpty())
    }

    @Test fun theSameBytesRenewTheDateOtherBytesReplaceTheFile() {
        val d = disk()
        d.write("robot-a", "a", bytes(100, 1))
        val first = d.read("robot-a", "a")!!.savedAt
        clock += 5000
        assertEquals(PhotoDisk.Written.SAME, d.write("robot-a", "a", bytes(100, 1)))
        assertEquals(first + 5000, d.read("robot-a", "a")!!.savedAt)
        clock += 5000
        assertEquals(PhotoDisk.Written.CHANGED, d.write("robot-a", "a", bytes(120, 2)))
        assertArrayEquals(bytes(120, 2), d.read("robot-a", "a")!!.bytes)
        assertEquals(1, files().size)
        assertEquals(120L, d.bytes())
        d.remove("robot-a", "a")
        assertTrue(files().isEmpty())
        assertEquals(0L, d.bytes())
    }
}
