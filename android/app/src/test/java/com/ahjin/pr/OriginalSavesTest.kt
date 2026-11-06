package com.ahjin.pr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OriginalSavesTest {
    private fun slot(index: Int, savedAt: Long?) = GameRepository.SaveSlot(
        index = index,
        name = "Entrenador $index",
        seen = "10",
        caught = "5",
        place = "Pueblo $index",
        savedAt = savedAt,
    )

    @Test
    fun `only a real savc has a continuation date`() {
        val folder = Files.createTempDirectory("original-saves").toFile()
        try {
            assertNull(originalSaveTimestamp(folder, 2))
            folder.resolve("reloaded2.nfo").writeText("Nombre\n\n1\n2\nLugar\n")
            assertNull("metadata alone is not a loadable save", originalSaveTimestamp(folder, 2))
            val save = folder.resolve("reloaded2.savc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertTrue(save.setLastModified(1_700_000_123_000L))
            assertEquals(1_700_000_123_000L, originalSaveTimestamp(folder, 2))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `the latest original save is chosen by date rather than slot number`() {
        val slots = listOf(slot(3, 300L), slot(1, 500L), slot(2, 400L))
        assertEquals(1, latestOriginalSave(slots)?.index)
    }

    @Test
    fun `metadata only and empty slots are not continuable`() {
        assertNull(latestOriginalSave(listOf(slot(1, null), slot(3, null))))
    }

    @Test
    fun `the home date is local and readable`() {
        assertNotNull(originalSaveDate(1_700_000_123_000L))
        assertTrue(originalSaveDate(1_700_000_123_000L).isNotBlank())
    }
}
