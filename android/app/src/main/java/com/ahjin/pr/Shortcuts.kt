package com.ahjin.pr

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The "Continuar" shortcut that appears when the app's icon is held (`res/xml/shortcuts.xml`). */
object Shortcuts {
    const val ACTION_PLAY = "com.ahjin.pr.action.PLAY"

    /**
     * Acts on the shortcut that opened [activity], if one did. Returns whether the game is being started
     * (or brought back), in which case the launcher has nothing to show. When the game is not installed or
     * the app was never set up, the shortcut just opens the launcher, where that gets done. An obsolete
     * quick-load intent is deliberately ignored and also opens the launcher.
     */
    fun handle(activity: Activity, repo: GameRepository, prefs: Preferences): Boolean {
        if (activity.intent?.action != ACTION_PLAY) return false
        // Spent: turning the screen or coming back to the launcher later must not start the game again.
        activity.intent.action = null

        if (GameActivity.isRunning) {
            // One game at a time: the one that is open is brought back instead of starting a second.
            activity.startActivity(
                Intent().setClassName(activity.packageName, "com.ahjin.pr.GameActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            activity.finish()
            return true
        }
        if (!prefs.onboardingDone || repo.status() !is GameRepository.Status.Ready) return false
        val owner = activity as? androidx.activity.ComponentActivity ?: return false
        owner.lifecycleScope.launch {
            // The same copy of the saves the Play button makes, and the game starts whatever happens to it.
            if (prefs.autoBackup) {
                runCatching { withTimeoutOrNull(4_000) { repo.backup(SaveBackups.Reason.PLAY) } }
            }
            // Started from the activity itself, as the Play button does. If it still fails, the launcher
            // stays open and says so, instead of the app closing.
            runCatching { Emulator.start(activity) }.onFailure {
                Toast.makeText(activity, "No se pudo abrir el juego: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        return true
    }
}
