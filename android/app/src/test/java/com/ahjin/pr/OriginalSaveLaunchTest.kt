package com.ahjin.pr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginalSaveLaunchTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun continueWritesTheRequestedOriginalSlot() {
        val directory = temporary.newFolder()
        writeOriginalLaunchFile(directory, 3)
        assertEquals("slot=3\n", File(directory, "continue.conf").readText())
    }

    @Test
    fun normalLaunchClearsAnOlderContinueRequest() {
        val directory = temporary.newFolder()
        writeOriginalLaunchFile(directory, 7)
        writeOriginalLaunchFile(directory, null)
        assertEquals("", File(directory, "continue.conf").readText())
    }

    @Test
    fun invalidSlotsAreRejectedBeforeStartingTheGame() {
        val directory = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            writeOriginalLaunchFile(directory, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            writeOriginalLaunchFile(directory, 100)
        }
    }
}
