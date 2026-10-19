package com.ahjin.pr

import java.io.File
import java.text.DateFormat
import java.util.Date

const val QUICK_SLOTS = 3

/** Everything in the Extras screen, as the engine takes it. */
data class ExtraSettings(
    val quickSlot: Int = 1,
    val ffwdSpeed: Int = 2,
    val ffwdMute: Boolean = true,
    val autoHideSeconds: Int = 0,
    val smoothFilter: Boolean = false,
    val screenEffect: Int = 0,
    val safeArea: Boolean = true,
    val showStats: Boolean = false,
    val batteryMode: Int = 0,
    val padRumble: Int = 1,
)

/** Writes the file the emulator reads when it starts (`extras.conf`). */
fun writeExtrasFile(filesDir: File, extras: ExtraSettings) {
    val text = buildString {
        appendLine("# Escrito por la pantalla Extras. El emulador lo lee al arrancar.")
        appendLine("quick_slot=${extras.quickSlot}")
        appendLine("ffwd_speed=${extras.ffwdSpeed}")
        appendLine("ffwd_mute=${if (extras.ffwdMute) 1 else 0}")
        appendLine("autohide_ms=${extras.autoHideSeconds * 1000}")
        appendLine("filter=${if (extras.smoothFilter) "smooth" else "nearest"}")
        appendLine("effect=${listOf("none", "lcd", "crt")[extras.screenEffect.coerceIn(0, 2)]}")
        appendLine("safe_area=${if (extras.safeArea) 1 else 0}")
        appendLine("stats=${if (extras.showStats) 1 else 0}")
        appendLine("battery=${extras.batteryMode}")
        appendLine("rumble=${extras.padRumble}")
    }
    runCatching { File(filesDir, "extras.conf").writeText(text) }
}

/**
 * The quick saves the engine keeps in `files/quicksaves`. A slot counts as taken only when its
 * `.info` file exists, because the engine writes that last, after the state itself.
 */
object QuickSaves {
    private fun dir(filesDir: File) = File(filesDir, "quicksaves")

    /** When the slot was saved (milliseconds), or null if it is empty or unreadable. */
    fun savedAt(filesDir: File, slot: Int): Long? {
        val info = runCatching { File(dir(filesDir), "slot$slot.info").readText() }.getOrNull() ?: return null
        val seconds = info.lines().firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("time=") }?.substringAfter('=')?.trim()?.toLongOrNull()
        } ?: return null
        return seconds * 1000
    }

    fun describe(filesDir: File, slot: Int): String {
        val at = savedAt(filesDir, slot) ?: return "Vacia"
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return "Guardada el ${format.format(Date(at))}"
    }

    fun size(filesDir: File, slot: Int): Long = File(dir(filesDir), "slot$slot.state").takeIf { it.isFile }?.length() ?: 0L

    fun delete(filesDir: File, slot: Int) {
        // The info goes first, so a slot that is half-deleted already reads as empty.
        File(dir(filesDir), "slot$slot.info").delete()
        File(dir(filesDir), "slot$slot.state").delete()
        File(dir(filesDir), "slot$slot.state.tmp").delete()
    }
}
