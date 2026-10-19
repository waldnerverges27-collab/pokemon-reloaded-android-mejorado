package com.klisee.reloaded

import android.content.Context
import java.io.File

/**
 * The launcher's own settings. The game keeps its separate options screen for in-game things; this
 * only holds what the launcher itself can honour.
 */
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Keep the screen awake while the game is running, so long battles don't lock the phone. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(value) {
            prefs.edit().putBoolean("keepScreenOn", value).apply()
        }

    /** Whether the first-run setup has been completed. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(value) {
            prefs.edit().putBoolean("onboardingDone", value).apply()
        }

    /** Pad buttons the player has rebound, as `ACTION=keyCode`. Absent means the built-in layout. */
    var controlBindings: Map<ControlAction, Int>
        get() = prefs.getStringSet("controlBindings", emptySet()).orEmpty().mapNotNull { entry ->
            val parts = entry.split('=')
            if (parts.size != 2) return@mapNotNull null
            val action = ControlAction.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val code = parts[1].toIntOrNull() ?: return@mapNotNull null
            action to code
        }.toMap()
        set(value) {
            prefs.edit().putStringSet("controlBindings", value.map { "${it.key.name}=${it.value}" }.toSet()).apply()
        }

    /** Whether a pad's sticks move the player. */
    var stickEnabled: Boolean
        get() = prefs.getBoolean("stickEnabled", true)
        set(value) {
            prefs.edit().putBoolean("stickEnabled", value).apply()
        }

    /** How opaque the control artwork is drawn. 1 is solid; lower lets the game show through. */
    var touchOpacity: Float
        get() = prefs.getFloat("touchOpacity", 1f)
        set(value) {
            prefs.edit().putFloat("touchOpacity", value).apply()
        }

    /** Size of the on-screen buttons, 1 = as designed. */
    var touchScale: Float
        get() = prefs.getFloat("touchScale", 1f)
        set(value) {
            prefs.edit().putFloat("touchScale", value).apply()
        }

    /** Haptic feedback on the on-screen buttons: 0 off, 1 light, 2 strong. */
    var hapticLevel: Int
        get() = prefs.getInt("hapticLevel", 1)
        set(value) {
            prefs.edit().putInt("hapticLevel", value).apply()
        }

    /** The stick appears wherever the thumb lands, instead of being fixed in one place. */
    var floatingStick: Boolean
        get() = prefs.getBoolean("floatingStick", false)
        set(value) {
            prefs.edit().putBoolean("floatingStick", value).apply()
        }

    /** How far the stick has to be pushed before it moves the player (fraction of its radius). */
    var stickDeadzone: Float
        get() = prefs.getFloat("stickDeadzone", 0.35f)
        set(value) {
            prefs.edit().putFloat("stickDeadzone", value).apply()
        }

    /** Native-looking buttons (true) or the pixel-art sprites the game came with (false). */
    var nativeStyle: Boolean
        get() = prefs.getBoolean("nativeStyle", true)
        set(value) {
            prefs.edit().putBoolean("nativeStyle", value).apply()
        }

    fun touchSettings() = TouchSettings(touchOpacity, touchScale, hapticLevel, floatingStick, stickDeadzone, nativeStyle)

    /** Mirrors the current controls into the file the emulator reads at startup. */
    fun writeControls(filesDir: File, padConnected: Boolean) =
        writeControlsFile(filesDir, controlBindings, stickEnabled, padConnected, touchSettings())

    /** Keep the shape the game was drawn for, with bands at the sides, instead of filling the screen. */
    var keepAspect: Boolean
        get() = prefs.getBoolean("keepAspect", false)
        set(value) {
            prefs.edit().putBoolean("keepAspect", value).apply()
        }

    /** Mirrors the video mode into the file the emulator reads at startup. */
    fun writeVideo(filesDir: File) = writeVideoFile(filesDir, keepAspect)
}
