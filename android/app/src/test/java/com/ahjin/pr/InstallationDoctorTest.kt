package com.ahjin.pr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallationDoctorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val installed = InstallationDoctor.Installed("Reloaded.exe", "1.9.2.2")
    private val plenty = 1024L * 1024 * 1024

    private fun write(root: File, path: String, text: String = "datos") {
        File(root, path).apply {
            parentFile!!.mkdirs()
            writeText(text)
        }
    }

    private fun completeGame(root: File) {
        write(root, "game/Reloaded.exe", "exe")
        write(root, "game/data/text/lan.txt", "es\nen\n")
        write(root, "game/data/text/es/texto.dat")
        write(root, "game/data/audio/musica/tema.mid")
        write(root, "game/data/graphics/imagen.png")
        write(root, "game/data/partidas/reloaded1.nfo", "Entrenador")
        write(root, "game/soundfont.sf2", "sound")
    }

    private fun inspect(
        root: File,
        game: InstallationDoctor.Installed? = installed,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        free: Long = plenty,
    ) = InstallationDoctor.inspect(root, game, abis, free)

    private fun check(report: InstallationDoctor.Report, id: InstallationDoctor.CheckId) =
        report.checks.single { it.id == id }

    @Test
    fun `a complete installation passes without modifying anything`() {
        val root = temporary.newFolder()
        completeGame(root)
        write(root, "game/data/partidas/reloaded1.savc", "partida")
        write(root, "game/data/partidas/reloaded1_bk.savc", "copia")
        val before = root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to it.readText() }

        val report = inspect(root)

        val after = root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to it.readText() }
        assertEquals(before, after)
        assertTrue(report.isClear)
        assertEquals(0, report.errors)
        assertEquals(0, report.warnings)
        assertFalse(report.needsImport)
        assertFalse(report.canRepair)
        assertTrue(check(report, InstallationDoctor.CheckId.SAVES).detail.contains("1 partida"))
    }

    @Test
    fun `missing game asks for an import without irrelevant file errors`() {
        val root = temporary.newFolder()
        val report = inspect(root, game = null)
        assertTrue(report.needsImport)
        assertEquals(InstallationDoctor.Level.ERROR, check(report, InstallationDoctor.CheckId.GAME).level)
        assertFalse(report.checks.any { it.id == InstallationDoctor.CheckId.ESSENTIALS })
        assertFalse(report.checks.any { it.id == InstallationDoctor.CheckId.AUDIO })
    }

    @Test
    fun `an interrupted swap explains the recovery path`() {
        val root = temporary.newFolder()
        write(root, "game.old/Reloaded.exe", "anterior")
        val report = inspect(root, game = null)
        assertTrue(check(report, InstallationDoctor.CheckId.GAME).detail.contains("interrumpida"))
        assertEquals(InstallationDoctor.Level.WARNING, check(report, InstallationDoctor.CheckId.LEFTOVERS).level)
    }

    @Test
    fun `missing essential data needs import and missing soundfont has a safe repair`() {
        val root = temporary.newFolder()
        write(root, "game/Reloaded.exe", "exe")
        val report = inspect(root)
        assertTrue(report.needsImport)
        assertTrue(report.canRepair)
        assertEquals(InstallationDoctor.Level.ERROR, check(report, InstallationDoctor.CheckId.ESSENTIALS).level)
        assertEquals(InstallationDoctor.Level.WARNING, check(report, InstallationDoctor.CheckId.AUDIO).level)
    }

    @Test
    fun `an empty executable is not accepted as essential data`() {
        val root = temporary.newFolder()
        completeGame(root)
        File(root, "game/Reloaded.exe").writeBytes(ByteArray(0))
        val report = inspect(root)
        assertEquals(InstallationDoctor.Level.ERROR, check(report, InstallationDoctor.CheckId.ESSENTIALS).level)
        assertTrue(report.needsImport)
    }

    @Test
    fun `low storage and unsupported processors are visible`() {
        val root = temporary.newFolder()
        completeGame(root)
        val report = inspect(root, abis = listOf("x86_64"), free = InstallationDoctor.LOW_STORAGE_BYTES - 1)
        assertEquals(InstallationDoctor.Level.ERROR, check(report, InstallationDoctor.CheckId.COMPATIBILITY).level)
        assertEquals(InstallationDoctor.Level.WARNING, check(report, InstallationDoctor.CheckId.STORAGE).level)
    }

    @Test
    fun `the write probe always cleans up its own file`() {
        val root = temporary.newFolder()
        completeGame(root)
        val report = inspect(root)
        assertEquals(InstallationDoctor.Level.OK, check(report, InstallationDoctor.CheckId.WRITE).level)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".doctor-") })
    }

    @Test
    fun `temporary installation files produce a warning not an automatic deletion`() {
        val root = temporary.newFolder()
        completeGame(root)
        write(root, "import.zip", "parcial")
        write(root, "game.staging/data/tmp", "parcial")
        val before = File(root, "import.zip").readText()
        val report = inspect(root)
        assertEquals(InstallationDoctor.Level.WARNING, check(report, InstallationDoctor.CheckId.LEFTOVERS).level)
        assertEquals(before, File(root, "import.zip").readText())
        assertTrue(File(root, "game.staging").isDirectory)
    }
}
