package com.ahjin.pr

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import com.ahjin.pr.ui.App
import com.ahjin.pr.ui.ReloadedTheme
import com.ahjin.pr.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val repo = GameRepository(applicationContext)
        val prefs = Preferences(applicationContext)
        setContent {
            val mode = prefs.themeMode
            val dark = isDarkTheme(mode)
            // The bars sit over the page, so their icons have to follow the page's brightness, not the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ReloadedTheme(mode, prefs.accent, prefs.amoled) { App(repo, prefs) }
        }
    }
}
