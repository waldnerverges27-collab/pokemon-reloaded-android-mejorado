package com.ahjin.pr

import android.content.Context
import android.content.Intent

/** Starts the native activity that runs the bundled `libgm8emulator.so`. */
object Emulator {
    private const val GAME_ACTIVITY = "com.ahjin.pr.GameActivity"

    /** Kept outside GameActivity so checking it does not initialise the native library. */
    @Volatile
    var isRunning: Boolean = false
        internal set

    /**
     * Starts the game. With [originalSlot], the engine invokes the original loader for that save;
     * with null, it keeps the ordinary intro and menu. The request is always rewritten per launch.
     */
    fun start(context: Context, originalSlot: Int? = null) {
        // The emulator reads the pad layout and the video mode once, at startup, so they have to be
        // on disk by now.
        val prefs = Preferences(context)
        prefs.writeControls(context.filesDir, connectedPads(context).isNotEmpty())
        prefs.writeVideo(context.filesDir)
        prefs.writeExtras(context.filesDir)
        writeOriginalLaunchFile(context.filesDir, originalSlot)
        val intent = Intent().setClassName(context.packageName, GAME_ACTIVITY)
        // From anything that is not an activity (the application context) Android refuses to start
        // one without a task of its own; asking for it is harmless from an activity too.
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
