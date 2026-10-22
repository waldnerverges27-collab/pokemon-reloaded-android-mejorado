package com.ahjin.pr

import java.io.File
import java.text.DateFormat
import java.util.Date

const val QUICK_SLOTS = 3

/** The speeds the game can be set to, as a percentage of normal. Under 100 is slow motion. */
val SPEED_CHOICES: List<Int> = listOf(50, 75, 150, 200, 300, 400)

/** A speed as the player reads it: x0,5, x1,5, x2. */
fun speedLabel(percent: Int): String =
    if (percent % 100 == 0) "x${percent / 100}" else "x${(percent / 100.0).toString().replace('.', ',')}"

/** Everything in the Extras screen, as the engine takes it. */
data class ExtraSettings(
    val quickSlot: Int = 1,
    val speedPercent: Int = 200,
    val ffwdMute: Boolean = true,
    val autoHideSeconds: Int = 0,
    val smoothFilter: Boolean = false,
    val screenEffect: Int = 0,
    val safeArea: Boolean = true,
    val showStats: Boolean = false,
    val batteryMode: Int = 0,
    val padRumble: Int = 1,
    /** Opened from the "load quick save" shortcut: the game loads the slot by itself once it is up. */
    val quickLoadAtStart: Boolean = false,
)

/** Writes the file the emulator reads when it starts (`extras.conf`). */
fun writeExtrasFile(filesDir: File, extras: ExtraSettings) {
    val text = buildString {
        appendLine("# Escrito por la pantalla Extras. El emulador lo lee al arrancar.")
        appendLine("quick_slot=${extras.quickSlot}")
        appendLine("speed_percent=${extras.speedPercent}")
        appendLine("ffwd_mute=${if (extras.ffwdMute) 1 else 0}")
        appendLine("autohide_ms=${extras.autoHideSeconds * 1000}")
        appendLine("filter=${if (extras.smoothFilter) "smooth" else "nearest"}")
        appendLine("effect=${listOf("none", "lcd", "crt")[extras.screenEffect.coerceIn(0, 2)]}")
        appendLine("safe_area=${if (extras.safeArea) 1 else 0}")
        appendLine("stats=${if (extras.showStats) 1 else 0}")
        appendLine("battery=${extras.batteryMode}")
        appendLine("rumble=${extras.padRumble}")
        if (extras.quickLoadAtStart) appendLine("startup=qload")
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
