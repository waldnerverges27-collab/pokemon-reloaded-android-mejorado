package com.klisee.reloaded

import android.app.NativeActivity
import android.content.Context
import android.os.Build
import android.graphics.Rect
import android.os.Bundle
import android.os.PerformanceHintManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The emulator activity: a plain `NativeActivity` (that is what loads `libgm8emulator.so`),
 * subclassed to hide the system bars, to honour the launcher's screen setting, and to be the target
 * the system keyboard attaches to.
 *
 * Hiding has to be done here rather than from Rust: the Context that `android-activity` publishes
 * for JNI is the *Application*, which has no `getWindow()`, so the native attempt always failed
 * with a NoSuchMethodError and the navigation bar stayed on screen.
 */
private const val TAG = "ReloadedIme"

class GameActivity : NativeActivity() {

    /** The 1x1 view the system keyboard types into. What it receives goes straight to the engine. */
    private var imeAnchor: EditText? = null

    private var vibrator: Vibrator? = null
    private var hintSession: PerformanceHintManager.Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the notch/camera hole. Without this the system insets the window on the short
        // edge and the game is left with a black band beside it instead of filling the screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        super.onCreate(savedInstanceState)
        instance = this
        if (Preferences(applicationContext).keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        installImeAnchor()
        watchImeHeight()
        hideSystemBars()
        setUpVibrator()
        excludeSystemGestures()
        // Sustained-performance mode used to be requested here. It caps the clocks at a level the
        // phone can hold forever, which on gaming phones is well under what the game and its audio
        // thread need during a busy scene, so it is left off; the ADPF hint session does the job.
    }

    override fun onDestroy() {
        perfStop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // The bars come back on some system events, so re-hide them whenever we regain focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------------------------------
    // Gestures. The buttons sit at the left and right edges, which is exactly where gesture
    // navigation takes a swipe as "back", so a thumb that slid a little too far used to close the
    // game. Those strips are handed back to the game; the swipe from the bottom (home) and the one
    // from the top (notifications) keep working.

    private fun excludeSystemGestures() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val decor = window.decorView
        decor.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val strip = (resources.displayMetrics.density * 56f).toInt()
            v.systemGestureExclusionRects = listOf(
                Rect(0, 0, strip, height),
                Rect(width - strip, 0, width, height),
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // Haptics. The engine calls `haptic` over JNI when a button goes down or the stick engages a
    // direction. The system's predefined effects are used because the phone's own motor tunes them
    // (on a phone with a linear motor they are a crisp tick rather than a buzz).

    private fun setUpVibrator() {
        vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }.getOrNull()?.takeIf { it.hasVibrator() }
    }

    private fun playHaptic(level: Int, effect: Int) {
        val v = vibrator ?: return
        runCatching {
            val wanted = when {
                // Stick tick, light: the shortest pulse the motor can do.
                effect == 1 && level < 2 && v.hasAmplitudeControl() -> VibrationEffect.createOneShot(6, 60)
                effect == 1 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                level >= 2 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }
            v.vibrate(wanted)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Performance hint (ADPF). The engine opens a session for its game thread with the length of a
    // frame as the target and reports how long each frame's work took. Android uses that to raise
    // the CPU clock before a heavy frame needs it and to let it fall in light ones.

    private fun perfBegin(tid: Int, targetNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            hintSession?.close()
            val manager = getSystemService(PerformanceHintManager::class.java) ?: return
            hintSession = manager.createHintSession(intArrayOf(tid), targetNanos)
        }
    }

    private fun perfStop() {
        runCatching { hintSession?.close() }
        hintSession = null
    }

    // ------------------------------------------------------------------------------------------
    // System keyboard. The game only asks for text through its own modal and has no Android view of
    // its own, so this tiny transparent view exists only to be the target the keyboard attaches to.
    // Nothing typed is kept in it: a custom InputConnection forwards each character to the engine,
    // which is the one that decides when to raise the keyboard (it sees the game's dialog open).

    private fun installImeAnchor() {
        val anchor = object : EditText(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                // No fullscreen/extract editor and no suggestion strip: in landscape the system
                // would otherwise cover the game with its own text box.
                outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN
                return ForwardingInputConnection(this)
            }
        }
        anchor.isFocusable = true
        anchor.isFocusableInTouchMode = true
        anchor.alpha = 0f
        addContentView(anchor, ViewGroup.LayoutParams(1, 1))
        imeAnchor = anchor
    }

    /** Sends what is typed to the engine instead of leaving it in the view. */
    private inner class ForwardingInputConnection(target: View) : BaseInputConnection(target, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!text.isNullOrEmpty()) nativeCommitText(text.toString())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { nativeBackspace() }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    nativeBackspace()
                    return true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    nativeEnter()
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            nativeEnter()
            return true
        }
    }

    /**
     * Keeps the engine told of the system keyboard's height, so it can fit the game above it instead
     * of leaving what is being typed hidden under the keyboard.
     */
    private fun watchImeHeight() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            reportImeHeight(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
    }

    private fun currentImeHeight(): Int =
        ViewCompat.getRootWindowInsets(window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun reportImeHeight(bottom: Int) {
        Log.i(TAG, "altura del teclado = $bottom")
        nativeImeInset(bottom)
    }

    private fun applyImeVisible(visible: Boolean) {
        val anchor = imeAnchor ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        Log.i(TAG, "teclado visible = $visible")
        if (visible) {
            anchor.requestFocus()
            imm.showSoftInput(anchor, 0)
            // The keyboard animates in, so its height is not final on the first frame; ask again once
            // it has settled. If it is still not up by then, the system ignored the first request
            // (it happens when nothing was touched), so ask once more.
            anchor.postDelayed({
                if (currentImeHeight() == 0) imm.showSoftInput(anchor, 0)
                reportImeHeight(currentImeHeight())
            }, 400)
        } else {
            imm.hideSoftInputFromWindow(anchor.windowToken, 0)
            anchor.clearFocus()
        }
    }

    external fun nativeCommitText(text: String)
    external fun nativeBackspace()
    external fun nativeEnter()
    external fun nativeImeInset(height: Int)

    companion object {
        init {
            // These are declared as `native` methods on this class, so the JVM has to find their
            // `Java_com_klisee_reloaded_GameActivity_*` symbols in the library. The emulator loads
            // the same one through the manifest, and this only links it to the app's class loader.
            System.loadLibrary("gm8emulator")
        }

        private var instance: GameActivity? = null

        /** Called from the engine over JNI. `effect`: 0 = a button went down, 1 = stick tick. */
        @JvmStatic
        fun haptic(level: Int, effect: Int) {
            instance?.playHaptic(level, effect)
        }

        /** Opens the performance hint session for the engine's game thread. */
        @JvmStatic
        fun perfStart(tid: Int, targetNanos: Long) {
            instance?.perfBegin(tid, targetNanos)
        }

        /** Reports how long the last frame's work took. */
        @JvmStatic
        fun perfReport(nanos: Long) {
            val session = instance?.hintSession ?: return
            runCatching { session.reportActualWorkDuration(nanos) }
        }

        /**
         * Called from the engine over JNI (it only holds the Application context, so it goes through
         * this static). The keyboard has to be raised from the UI thread.
         */
        @JvmStatic
        fun setImeVisible(visible: Boolean) {
            val activity = instance ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) activity.applyImeVisible(visible)
            }
        }

        /**
         * Called from the engine to make it read the keyboard's height again. The height normally
         * reaches it on its own from the insets; this is there so a callback that never arrived
         * cannot leave the game pushed up with its controls hidden.
         */
        @JvmStatic
        fun refreshImeHeight() {
            val activity = instance ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.reportImeHeight(activity.currentImeHeight())
                }
            }
        }
    }
}
