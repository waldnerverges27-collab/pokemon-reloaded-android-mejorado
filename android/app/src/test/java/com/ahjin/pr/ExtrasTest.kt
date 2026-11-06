package com.ahjin.pr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ExtrasTest {
    @Test
    fun `the engine file contains no quick save or startup load request`() {
        val folder = Files.createTempDirectory("extras").toFile()
        try {
            writeExtrasFile(folder, ExtraSettings(speedPercent = 150, showStats = true))
            val text = folder.resolve("extras.conf").readText()
            assertTrue("speed_percent=150" in text)
            assertTrue("stats=1" in text)
            assertFalse("quick" in text.lowercase())
            assertFalse("startup" in text.lowercase())
            assertFalse("qload" in text.lowercase())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `quick save actions are no longer assignable to a pad`() {
        val names = ControlAction.entries.map { it.name }.toSet()
        assertFalse("QSAVE" in names)
        assertFalse("QLOAD" in names)
        assertFalse("QSLOT" in names)
        assertTrue("FFWD" in names)
        assertTrue("STATS" in names)
    }
}
