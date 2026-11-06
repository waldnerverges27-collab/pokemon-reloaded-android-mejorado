package com.ahjin.pr

import java.io.File

/** The ways the picture can fill the screen. */
val VIDEO_MODES = listOf("full", "aspect", "integer")

/** Resolves a saved choice without changing it. Only an installation with no choice at all starts
 * with perfect pixels; the old aspect switch is still honoured when it is all an older install has. */
fun resolveVideoMode(saved: String?, hasLegacyAspect: Boolean, keepAspect: Boolean): String =
    saved?.takeIf { it in VIDEO_MODES }
        ?: if (hasLegacyAspect) (if (keepAspect) "aspect" else "full") else "integer"

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
        appendLine("scaling=${if (mode in VIDEO_MODES) mode else "integer"}")
    }
    runCatching { File(filesDir, "video.conf").writeText(text) }
}
