package com.ahjin.pr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CryPackStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: File
    private lateinit var cache: File
    private lateinit var gameCries: File

    private fun pcm(channels: Int = 2, rate: Int = 32_000, bits: Int = 16, marker: Byte = 1): ByteArray {
        val data = ByteArray(if (bits == 8) 8 else 16) { (marker + it).toByte() }
        val out = ByteArrayOutputStream()
        fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun le16(value: Int) {
            out.write(value and 255); out.write((value ushr 8) and 255)
        }
        fun le32(value: Long) {
            repeat(4) { out.write(((value ushr (it * 8)) and 255).toInt()) }
        }
        ascii("RIFF"); le32(36L + data.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels); le32(rate.toLong())
        val blockAlign = channels * bits / 8
        le32((rate * blockAlign).toLong()); le16(blockAlign); le16(bits)
        ascii("data"); le32(data.size.toLong()); out.write(data)
        return out.toByteArray()
    }

    private data class Archive(val file: File, val definition: CryPackDefinition)

    private fun archive(entries: Map<String, ByteArray>, badHash: Boolean = false): Archive {
        val file = File(tmp.root, "pack-${System.nanoTime()}.zip")
        var uncompressed = 0L
        var wavs = 0
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith('/')) zip.write(bytes)
                zip.closeEntry()
                if (!name.endsWith('/')) uncompressed += bytes.size
                if (name.endsWith(".wav", ignoreCase = true)) wavs++
            }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return Archive(
            file,
            CryPackDefinition(
                id = "test-cries",
                title = "Prueba",
                url = "https://example.invalid/pack.zip",
                pageUrl = "https://example.invalid/",
                archiveBytes = file.length(),
                uncompressedBytes = uncompressed,
                wavCount = wavs,
                sha256 = if (badHash) "0".repeat(64) else sha,
                extraFiles = emptySet(),
            ),
        )
    }

    private fun setupGame(key: Long = 1L) {
        files = tmp.newFolder("files-${System.nanoTime()}")
        cache = tmp.newFolder("cache-${System.nanoTime()}")
        gameCries = File(files, "game/data/audio/grito")
        gameCries.mkdirs()
        File(files, "game.json").writeText("{\"installedAt\":$key}")
    }

    private fun writeOriginal(name: String, marker: Byte) {
        File(gameCries, name).writeBytes(pcm(marker = marker))
    }

    private fun replaceGame(key: Long) {
        File(files, "game").deleteRecursively()
        gameCries = File(files, "game/data/audio/grito")
        gameCries.mkdirs()
        File(files, "game.json").writeText("{\"installedAt\":$key}")
    }

    private fun store(archive: Archive) = CryPackStore(files, cache, archive.definition)

    @Test
    fun `install merges, backs up and restores the complete original folder`() {
        setupGame()
        writeOriginal("001.wav", 10)
        writeOriginal("003.wav", 30)
        val pack001 = pcm(marker = 101)
        val pack002 = pcm(channels = 1, rate = 16_000, bits = 8, marker = 102)
        val archive = archive(mapOf("grito/" to byteArrayOf(), "grito/001.wav" to pack001, "grito/002.wav" to pack002))

        val message = store(archive).installVerifiedArchiveForTest(archive.file)

        assertTrue(message.contains("Reinicia"))
        assertArrayEquals(pack001, File(gameCries, "001.wav").readBytes())
        assertArrayEquals(pack002, File(gameCries, "002.wav").readBytes())
        // A base cry absent from the pack is not deleted.
        assertArrayEquals(pcm(marker = 30), File(gameCries, "003.wav").readBytes())
        val backup = File(files, "anime-cries/test-cries/originals/grito")
        assertArrayEquals(pcm(marker = 10), File(backup, "001.wav").readBytes())
        assertArrayEquals(pcm(marker = 30), File(backup, "003.wav").readBytes())
        assertTrue(store(archive).status().active)

        store(archive).restore()

        assertArrayEquals(pcm(marker = 10), File(gameCries, "001.wav").readBytes())
        assertArrayEquals(pcm(marker = 30), File(gameCries, "003.wav").readBytes())
        assertFalse(File(gameCries, "002.wav").exists())
        assertFalse(store(archive).status().active)
        assertTrue(store(archive).status().downloaded)
    }

    @Test
    fun `an enabled pack is reapplied and the backup is refreshed after a game update`() {
        setupGame(key = 1)
        writeOriginal("001.wav", 10)
        writeOriginal("old.wav", 11)
        val pack = pcm(marker = 100)
        val archive = archive(mapOf("grito/001.wav" to pack))
        store(archive).installVerifiedArchiveForTest(archive.file)

        replaceGame(key = 2)
        writeOriginal("001.wav", 20)
        writeOriginal("new.wav", 21)

        assertTrue(store(archive).reapplyAfterGameInstall())
        assertArrayEquals(pack, File(gameCries, "001.wav").readBytes())
        assertArrayEquals(pcm(marker = 21), File(gameCries, "new.wav").readBytes())
        assertFalse(File(gameCries, "old.wav").exists())
        val backup = File(files, "anime-cries/test-cries/originals/grito")
        assertArrayEquals(pcm(marker = 20), File(backup, "001.wav").readBytes())
        assertArrayEquals(pcm(marker = 21), File(backup, "new.wav").readBytes())
        assertTrue(store(archive).status().active)
    }

    @Test
    fun `keeping originals after a game update does not copy the old backup`() {
        setupGame(key = 1)
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/001.wav" to pcm(marker = 100)))
        store(archive).installVerifiedArchiveForTest(archive.file)
        replaceGame(key = 2)
        writeOriginal("001.wav", 20)

        store(archive).restoreOrDisable()

        assertArrayEquals(pcm(marker = 20), File(gameCries, "001.wav").readBytes())
        assertFalse(store(archive).status().enabled)
        assertFalse(store(archive).reapplyAfterGameInstall())
        assertArrayEquals(pcm(marker = 20), File(gameCries, "001.wav").readBytes())
    }

    @Test
    fun `restoring also disables automatic reapplication`() {
        setupGame()
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/001.wav" to pcm(marker = 100)))
        store(archive).installVerifiedArchiveForTest(archive.file)
        store(archive).restore()
        replaceGame(key = 2)
        writeOriginal("001.wav", 20)

        assertFalse(store(archive).reapplyAfterGameInstall())
        assertArrayEquals(pcm(marker = 20), File(gameCries, "001.wav").readBytes())
    }

    @Test
    fun `a later manual apply refreshes the backup for the new game before restoring`() {
        setupGame(key = 1)
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/001.wav" to pcm(marker = 100)))
        store(archive).installVerifiedArchiveForTest(archive.file)
        store(archive).restore()
        replaceGame(key = 2)
        writeOriginal("001.wav", 20)

        store(archive).applyDownloaded()
        store(archive).restore()

        assertArrayEquals(pcm(marker = 20), File(gameCries, "001.wav").readBytes())
    }

    @Test
    fun `losing the small state file does not overwrite the original backup with modified cries`() {
        setupGame(key = 1)
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/001.wav" to pcm(marker = 100)))
        store(archive).installVerifiedArchiveForTest(archive.file)
        File(files, "anime-cries/test-cries/state.json").delete()

        store(archive).applyDownloaded()
        store(archive).restore()

        assertArrayEquals(pcm(marker = 10), File(gameCries, "001.wav").readBytes())
    }

    @Test
    fun `a wrong hash is rejected before the game is touched`() {
        setupGame()
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/001.wav" to pcm(marker = 100)), badHash = true)

        assertThrows(IllegalArgumentException::class.java) {
            store(archive).installVerifiedArchiveForTest(archive.file)
        }
        assertArrayEquals(pcm(marker = 10), File(gameCries, "001.wav").readBytes())
        assertFalse(store(archive).status().active)
    }

    @Test
    fun `zip slip is rejected`() {
        setupGame()
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/../../evil.wav" to pcm(marker = 100)))

        assertThrows(IllegalArgumentException::class.java) {
            store(archive).installVerifiedArchiveForTest(archive.file)
        }
        assertFalse(File(files, "evil.wav").exists())
        assertArrayEquals(pcm(marker = 10), File(gameCries, "001.wav").readBytes())
    }

    @Test
    fun `unexpected executable content is rejected`() {
        setupGame()
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/001.wav" to pcm(), "grito/evil.exe" to byteArrayOf(1, 2, 3)))

        assertThrows(IllegalArgumentException::class.java) {
            store(archive).installVerifiedArchiveForTest(archive.file)
        }
        assertFalse(File(gameCries, "evil.exe").exists())
    }

    @Test
    fun `a damaged WAV is rejected and does not replace the pack`() {
        setupGame()
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/001.wav" to "not a wave".toByteArray()))

        assertThrows(IllegalArgumentException::class.java) {
            store(archive).installVerifiedArchiveForTest(archive.file)
        }
        assertFalse(store(archive).status().downloaded)
        assertArrayEquals(pcm(marker = 10), File(gameCries, "001.wav").readBytes())
    }

    @Test
    fun `names that differ only by case are rejected`() {
        setupGame()
        writeOriginal("001.wav", 10)
        val archive = archive(mapOf("grito/001.wav" to pcm(), "grito/001.WAV" to pcm(marker = 2)))

        assertThrows(IllegalArgumentException::class.java) {
            store(archive).installVerifiedArchiveForTest(archive.file)
        }
        assertFalse(store(archive).status().downloaded)
    }

    @Test
    fun `atypical but safe names are preserved without guessing a rename`() {
        setupGame()
        writeOriginal("001.wav", 10)
        val spaced = pcm(marker = 40)
        val alternate = pcm(marker = 50)
        val archive = archive(mapOf("grito/2164 .wav" to spaced, "grito/2385_2.wav" to alternate))

        store(archive).installVerifiedArchiveForTest(archive.file)

        assertArrayEquals(spaced, File(gameCries, "2164 .wav").readBytes())
        assertArrayEquals(alternate, File(gameCries, "2385_2.wav").readBytes())
    }
}
