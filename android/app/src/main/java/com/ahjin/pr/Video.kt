package com.ahjin.pr

import java.io.File

/** The ways the picture can fill the screen. */
val VIDEO_MODES = listOf("full", "aspect", "integer")

/**
 * Writes the video mode the emulator reads when it starts.
 *
 * The game was drawn for a 4:3 shape, so `aspect` shows it the way it looked on a PC, with bands at
 * the sides, `full` stretches it to fill the phone's screen, and `integer` keeps the proportion and
 * only uses whole multiples of the game's size so no pixel is wider than its neighbour.
 */
fun writeVideoFile(filesDir: File, mode: String) {
    val text = buildString {
        appendLine("# Escrito por Ajustes. El emulador lo lee al arrancar.")
        appendLine("scaling=${if (mode in VIDEO_MODES) mode else "full"}")
    }
    runCatching { File(filesDir, "video.conf").writeText(text) }
}
