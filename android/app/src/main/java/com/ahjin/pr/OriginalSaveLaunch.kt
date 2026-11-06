package com.ahjin.pr

import java.io.File

/**
 * Writes the one-shot request for the game's own loading routine. A normal launch writes an empty
 * file as well, so an old Continue request can never leak into a later intro/new-game launch.
 */
fun writeOriginalLaunchFile(filesDir: File, slot: Int?) {
    require(slot == null || slot in 1..99) { "Ranura original fuera de rango: $slot" }
    val text = slot?.let { "slot=$it\n" }.orEmpty()
    File(filesDir, "continue.conf").writeText(text)
}
