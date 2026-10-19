package com.ahjin.pr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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

    private var themeModeState by mutableIntStateOf(prefs.getInt("themeMode", 1))

    /** Appearance: 0 follow the system, 1 dark (default), 2 light. Backed by Compose state so the UI follows it live. */
    var themeMode: Int
        get() = themeModeState
        set(value) {
            themeModeState = value
            prefs.edit().putInt("themeMode", value).apply()
        }

    private var accentState by mutableIntStateOf(prefs.getInt("accent", 0))

    /** Accent colour of the app (see `Accent`). 0 is the original gold. Backed by Compose state so the UI follows it live. */
    var accent: Int
        get() = accentState
        set(value) {
            accentState = value
            prefs.edit().putInt("accent", value).apply()
        }

    private var amoledState by mutableStateOf(prefs.getBoolean("amoled", false))

    /** Pure black background in dark mode, for OLED screens. Off by default. */
    var amoled: Boolean
        get() = amoledState
        set(value) {
            amoledState = value
            prefs.edit().putBoolean("amoled", value).apply()
        }

    /** A copy of the saves each time the game is opened (only if they changed). Safety copies before risky actions are always made. */
    var autoBackup: Boolean
        get() = prefs.getBoolean("autoBackup", true)
        set(value) {
            prefs.edit().putBoolean("autoBackup", value).apply()
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

    /**
     * How the picture fills the screen: `full`, `aspect` or `integer` (whole multiples of the game's
     * own size, so every pixel is the same size). Until the player picks one, the old on/off setting
     * decides.
     */
    var videoMode: String
        get() = prefs.getString("videoMode", null)?.takeIf { it in VIDEO_MODES } ?: if (keepAspect) "aspect" else "full"
        set(value) {
            prefs.edit().putString("videoMode", value).apply()
            keepAspect = value != "full"
        }

    /** Mirrors the video mode into the file the emulator reads at startup. */
    fun writeVideo(filesDir: File) = writeVideoFile(filesDir, videoMode)

    // ------------------------------------------------------------------------------------------
    // Extras: quick save, fast-forward, picture, battery, pad. See `Extras.kt`.

    /** Which of the quick-save slots the buttons use (1 to 3). */
    var quickSlot: Int
        get() = prefs.getInt("quickSlot", 1).coerceIn(1, QUICK_SLOTS)
        set(value) {
            prefs.edit().putInt("quickSlot", value.coerceIn(1, QUICK_SLOTS)).apply()
        }

    /** How many times faster the game runs while fast-forwarding (2 to 4). */
    var ffwdSpeed: Int
        get() = prefs.getInt("ffwdSpeed", 2).coerceIn(2, 4)
        set(value) {
            prefs.edit().putInt("ffwdSpeed", value.coerceIn(2, 4)).apply()
        }

    /** Silence while fast-forwarding. */
    var ffwdMute: Boolean
        get() = prefs.getBoolean("ffwdMute", true)
        set(value) {
            prefs.edit().putBoolean("ffwdMute", value).apply()
        }

    /** Seconds without touching the screen before the buttons fade; 0 = never. */
    var autoHideSeconds: Int
        get() = prefs.getInt("autoHideSeconds", 0).coerceIn(0, 30)
        set(value) {
            prefs.edit().putInt("autoHideSeconds", value.coerceIn(0, 30)).apply()
        }

    /** Smooth scaling instead of hard pixels. */
    var smoothFilter: Boolean
        get() = prefs.getBoolean("smoothFilter", false)
        set(value) {
            prefs.edit().putBoolean("smoothFilter", value).apply()
        }

    /** 0 = none, 1 = LCD grid, 2 = CRT scanlines. */
    var screenEffect: Int
        get() = prefs.getInt("screenEffect", 0).coerceIn(0, 2)
        set(value) {
            prefs.edit().putInt("screenEffect", value.coerceIn(0, 2)).apply()
        }

    /** Keep the picture clear of the camera cutout. */
    var safeArea: Boolean
        get() = prefs.getBoolean("safeArea", true)
        set(value) {
            prefs.edit().putBoolean("safeArea", value).apply()
        }

    /** Show frames per second, time per frame and memory over the game. */
    var showStats: Boolean
        get() = prefs.getBoolean("showStats", false)
        set(value) {
            prefs.edit().putBoolean("showStats", value).apply()
        }

    /** 0 = off, 1 = always, 2 = automatic (system battery saver on, or the phone running warm). */
    var batteryMode: Int
        get() = prefs.getInt("batteryMode", 0).coerceIn(0, 2)
        set(value) {
            prefs.edit().putInt("batteryMode", value.coerceIn(0, 2)).apply()
        }

    /** Buzz in the pad when one of its buttons is pressed: 0 off, 1 light, 2 strong. */
    var padRumble: Int
        get() = prefs.getInt("padRumble", 1).coerceIn(0, 2)
        set(value) {
            prefs.edit().putInt("padRumble", value.coerceIn(0, 2)).apply()
        }

    /** Mirrors the extras into the file the emulator reads at startup. */
    fun writeExtras(filesDir: File) = writeExtrasFile(
        filesDir,
        ExtraSettings(
            quickSlot, ffwdSpeed, ffwdMute, autoHideSeconds, smoothFilter, screenEffect, safeArea,
            showStats, batteryMode, padRumble,
        ),
    )
}
