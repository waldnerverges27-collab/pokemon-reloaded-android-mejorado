package com.ahjin.pr

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import com.ahjin.pr.ui.App
import com.ahjin.pr.ui.ReloadedTheme
import com.ahjin.pr.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    /** The zip that "Abrir con" or "Compartir" handed over, until its dialog is closed. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            ZipIntake.clearFinished()
            incoming = ZipIntake.uriFrom(intent)
        } else {
            // Turning the phone recreates the screen; the zip that was being asked about comes back with it.
            incoming = BundleCompat.getParcelable(savedInstanceState, STATE_INCOMING, Uri::class.java)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val repo = GameRepository(applicationContext)
        val prefs = Preferences(applicationContext)
        // Only on a fresh start: coming back after a turn of the screen must not run the shortcut again.
        if (savedInstanceState == null) Shortcuts.handle(this, repo, prefs)
        setContent {
            val mode = prefs.themeMode
            val dark = isDarkTheme(mode)
            // The bars sit over the page, so their icons have to follow the page's brightness, not the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ReloadedTheme(mode, prefs.accent, prefs.amoled) {
                App(repo, prefs, incoming = incoming, onIncomingClosed = { incoming = null })
            }
        }
    }

    // Some file managers start the app with the zip while it is already open on top.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ZipIntake.uriFrom(intent)?.let { incoming = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        incoming?.let { outState.putParcelable(STATE_INCOMING, it) }
    }

    private companion object {
        const val STATE_INCOMING = "incoming_zip"
    }
}
