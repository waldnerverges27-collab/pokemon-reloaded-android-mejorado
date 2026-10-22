package com.ahjin.pr

import android.content.Context
import android.content.Intent

/** Starts the native activity that runs the bundled `libgm8emulator.so`. */
object Emulator {
    private const val GAME_ACTIVITY = "com.ahjin.pr.GameActivity"

    /** [quickLoad]: the game loads the quick-save slot by itself as soon as it is up (the launcher shortcut). */
    fun start(context: Context, quickLoad: Boolean = false) {
        // The emulator reads the pad layout and the video mode once, at startup, so they have to be
        // on disk by now.
        val prefs = Preferences(context)
        prefs.writeControls(context.filesDir, connectedPads(context).isNotEmpty())
        prefs.writeVideo(context.filesDir)
        prefs.writeExtras(context.filesDir, quickLoad)
        context.startActivity(Intent().setClassName(context.packageName, GAME_ACTIVITY))
    }
}
