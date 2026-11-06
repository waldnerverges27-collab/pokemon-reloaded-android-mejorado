package com.ahjin.pr

import java.io.File

/** The speeds the game can be set to, as a percentage of normal. Under 100 is slow motion. */
val SPEED_CHOICES: List<Int> = listOf(50, 75, 150, 200, 300, 400)

/** A speed as the player reads it: x0,5, x1,5, x2. */
fun speedLabel(percent: Int): String =
    if (percent % 100 == 0) "x${percent / 100}" else "x${(percent / 100.0).toString().replace('.', ',')}"

/** Everything in the Extras screen, as the engine takes it. */
data class ExtraSettings(
    val speedPercent: Int = 200,
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
        appendLine("speed_percent=${extras.speedPercent}")
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
