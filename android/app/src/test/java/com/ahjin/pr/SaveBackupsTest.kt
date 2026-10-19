package com.ahjin.pr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SaveBackupsTest {
    private lateinit var root: File
    private lateinit var data: File
    private lateinit var dir: File
    private var time = 1_700_000_000_000L
    private lateinit var backups: SaveBackups

    @Before
    fun setUp() {
        root = Files.createTempDirectory("backups-test").toFile()
        data = File(root, "game/data").apply { mkdirs() }
        dir = File(root, "backups")
        backups = SaveBackups(data, dir) { time.also { time += 1000 } }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(path: String, text: String) {
        File(data, path).apply { parentFile!!.mkdirs() }.writeText(text)
    }

    private fun read(path: String): String? = File(data, path).takeIf { it.isFile }?.readText()

    private fun twoSaves() {
        write("partidas/reloaded1.savc", "uno")
        write("partidas/reloaded1.nfo", "Ash\n\n10\n5\nPueblo")
        write("partidas/reloaded1_bk.savc", "uno-bk")
        write("partidas/reloaded2.savc", "dos")
        write("equipos/equipo1.dat", "equipo")
    }

    @Test
    fun `nothing to back up is not an error and creates nothing`() {
        val result = backups.create(SaveBackups.Reason.MANUAL, force = true)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertTrue(backups.list().isEmpty())
        assertFalse(backups.hasSaves())
    }

    @Test
    fun `a copy holds the saves with the layout the importer expects`() {
        twoSaves()
        val backup = backups.create(SaveBackups.Reason.MANUAL).getOrThrow()
        assertNotNull(backup)
        backup!!
        assertEquals(2, backup.saves) // the _bk file is not a slot of its own
        assertEquals(5, backup.files)
        assertTrue(backup.manual)
        ZipFile(backup.file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("partidas/reloaded1.savc" in names)
            assertTrue("equipos/equipo1.dat" in names)
            assertTrue("backup.info" in names)
        }
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `an unchanged state is not copied twice but a forced copy is`() {
        twoSaves()
        assertNotNull(backups.create(SaveBackups.Reason.PLAY).getOrThrow())
        assertNull(backups.create(SaveBackups.Reason.PLAY).getOrThrow())
        assertEquals(1, backups.list().size)
        assertNotNull(backups.create(SaveBackups.Reason.MANUAL, force = true).getOrThrow())
        assertEquals(2, backups.list().size)
        write("partidas/reloaded2.savc", "dos-cambiada")
        assertNotNull(backups.create(SaveBackups.Reason.PLAY).getOrThrow())
        assertEquals(3, backups.list().size)
    }

    @Test
    fun `the list is newest first`() {
        twoSaves()
        backups.create(SaveBackups.Reason.MANUAL, force = true)
        write("partidas/reloaded2.savc", "otra")
        backups.create(SaveBackups.Reason.BEFORE_IMPORT)
        val list = backups.list()
        assertEquals(SaveBackups.Reason.BEFORE_IMPORT, list[0].reason)
        assertTrue(list[0].createdAt > list[1].createdAt)
    }

    @Test
    fun `restore brings back the old saves and keeps the current ones in a safety copy`() {
        twoSaves()
        val old = backups.create(SaveBackups.Reason.MANUAL).getOrThrow()!!
        write("partidas/reloaded1.savc", "ESTROPEADA")
        File(data, "partidas/reloaded2.savc").delete()
        write("partidas/reloaded3.savc", "nueva")

        val restored = backups.restore(old).getOrThrow()
        assertEquals(2, restored)
        assertEquals("uno", read("partidas/reloaded1.savc"))
        assertEquals("dos", read("partidas/reloaded2.savc"))
        assertNull("what was not in the copy goes away", read("partidas/reloaded3.savc"))
        assertEquals("equipo", read("equipos/equipo1.dat"))

        val safety = backups.list().first { it.reason == SaveBackups.Reason.BEFORE_RESTORE }
        assertEquals(2, safety.saves)
        // And undoing the restore works.
        backups.restore(safety).getOrThrow()
        assertEquals("ESTROPEADA", read("partidas/reloaded1.savc"))
        assertEquals("nueva", read("partidas/reloaded3.savc"))
        assertFalse(File(data, ".restore-staging").exists())
        assertFalse(File(data, ".restore-aside").exists())
    }

    @Test
    fun `restoring a copy without a folder empties that folder`() {
        write("partidas/reloaded1.savc", "uno")
        val onlySaves = backups.create(SaveBackups.Reason.MANUAL).getOrThrow()!!
        write("equipos/equipo1.dat", "equipo nuevo")
        backups.restore(onlySaves).getOrThrow()
        assertNull(read("equipos/equipo1.dat"))
        assertEquals("uno", read("partidas/reloaded1.savc"))
    }

    @Test
    fun `a damaged copy changes nothing`() {
        twoSaves()
        val good = backups.create(SaveBackups.Reason.MANUAL).getOrThrow()!!
        val bytes = good.file.readBytes()
        good.file.writeBytes(bytes.copyOf(bytes.size / 2))
        write("partidas/reloaded1.savc", "actual")
        val result = backups.restore(good)
        assertTrue(result.isFailure)
        assertEquals("actual", read("partidas/reloaded1.savc"))
        assertEquals("dos", read("partidas/reloaded2.savc"))
        assertFalse(File(data, ".restore-staging").exists())
    }

    @Test
    fun `entries that try to leave the data folder are refused`() {
        twoSaves()
        val evil = File(dir, "copia-20240101-000000-manual.zip").also { dir.mkdirs() }
        ZipOutputStream(evil.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("partidas/../../escapado.txt")); zip.write(1); zip.closeEntry()
            zip.putNextEntry(ZipEntry("backup.info"))
            zip.write("created=1\nreason=manual\nfiles=1\nsaves=0\nfingerprint=x\n".toByteArray())
            zip.closeEntry()
        }
        val backup = backups.list().first { it.file == evil }
        val result = backups.restore(backup)
        assertTrue(result.isFailure)
        assertFalse(File(root, "game/escapado.txt").exists())
        assertFalse(File(data.parentFile, "escapado.txt").exists())
        assertEquals("uno", read("partidas/reloaded1.savc"))
    }

    @Test
    fun `files that are not copies are ignored by the list`() {
        twoSaves()
        dir.mkdirs()
        File(dir, "copia-basura.zip").writeText("no soy un zip")
        File(dir, "copia-sin-info.zip").also { f ->
            ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("x")); it.write(1); it.closeEntry() }
        }
        File(dir, "otra-cosa.txt").writeText("hola")
        assertTrue(backups.list().isEmpty())
        assertNotNull(backups.create(SaveBackups.Reason.MANUAL).getOrThrow())
        assertEquals(1, backups.list().size)
    }

    @Test
    fun `old copies made by playing are pruned but manual and safety ones are kept`() {
        write("partidas/reloaded1.savc", "0")
        val manual = backups.create(SaveBackups.Reason.MANUAL, force = true).getOrThrow()!!
        repeat(9) { i ->
            write("partidas/reloaded1.savc", "v$i")
            backups.create(SaveBackups.Reason.PLAY)
        }
        write("partidas/reloaded1.savc", "antes")
        backups.create(SaveBackups.Reason.BEFORE_DELETE)
        val list = backups.list()
        assertEquals(5, list.count { it.reason == SaveBackups.Reason.PLAY })
        assertEquals(1, list.count { it.reason == SaveBackups.Reason.BEFORE_DELETE })
        assertTrue(list.any { it.file == manual.file })
    }

    @Test
    fun `leftovers of an interrupted write are cleaned and do not show up`() {
        twoSaves()
        dir.mkdirs()
        File(dir, "copia-20240101-000000-manual.zip.tmp").writeText("a medias")
        assertTrue(backups.list().isEmpty())
        backups.create(SaveBackups.Reason.MANUAL)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `delete only removes files inside the backup folder`() {
        twoSaves()
        val backup = backups.create(SaveBackups.Reason.MANUAL).getOrThrow()!!
        val outside = File(root, "fuera.zip").apply { writeText("x") }
        assertFalse(backups.delete(backup.copy(file = outside)))
        assertTrue(outside.exists())
        assertTrue(backups.delete(backup))
        assertTrue(backups.list().isEmpty())
    }

    @Test
    fun `restore without an installed game fails cleanly`() {
        twoSaves()
        val backup = backups.create(SaveBackups.Reason.MANUAL).getOrThrow()!!
        data.deleteRecursively()
        assertTrue(backups.restore(backup).isFailure)
    }

    @Test
    fun `a copy survives deleting the game folder`() {
        twoSaves()
        val backup = backups.create(SaveBackups.Reason.BEFORE_DELETE).getOrThrow()!!
        File(root, "game").deleteRecursively()
        assertTrue(backup.file.exists())
        data.mkdirs()
        assertEquals(2, backups.restore(backup).getOrThrow())
        assertEquals("uno", read("partidas/reloaded1.savc"))
    }
}
