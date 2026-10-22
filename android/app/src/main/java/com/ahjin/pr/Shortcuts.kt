package com.ahjin.pr

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The shortcuts that appear when the app's icon is held (`res/xml/shortcuts.xml`): "Continuar" starts
 * the game straight away and "Cargar guardado rapido" starts it and loads the quick-save slot.
 */
object Shortcuts {
    const val ACTION_PLAY = "com.ahjin.pr.action.PLAY"
    const val ACTION_QUICK_LOAD = "com.ahjin.pr.action.QUICKLOAD"

    /**
     * Acts on the shortcut that opened [activity], if one did. Returns whether the game is being started
     * (or brought back), in which case the launcher has nothing to show. When the game is not installed or
     * the app was never set up, the shortcut just opens the launcher, where that gets done.
     */
    fun handle(activity: Activity, repo: GameRepository, prefs: Preferences): Boolean {
        val action = activity.intent?.action
        if (action != ACTION_PLAY && action != ACTION_QUICK_LOAD) return false
        // Spent: turning the screen or coming back to the launcher later must not start the game again.
        activity.intent.action = null
        val quickLoad = action == ACTION_QUICK_LOAD

        if (GameActivity.isRunning) {
            // One game at a time: the one that is open is brought back instead of starting a second.
            activity.startActivity(
                Intent().setClassName(activity.packageName, "com.ahjin.pr.GameActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            if (quickLoad) Toast.makeText(activity, "El juego ya esta abierto: usa su boton de cargar", Toast.LENGTH_LONG).show()
            activity.finish()
            return true
        }
        if (!prefs.onboardingDone || repo.status() !is GameRepository.Status.Ready) return false
        if (quickLoad && QuickSaves.savedAt(activity.filesDir, prefs.quickSlot) == null) {
            Toast.makeText(activity, "La ranura de guardado rapido ${prefs.quickSlot} esta vacia", Toast.LENGTH_LONG).show()
            return false
        }
        val context = activity.applicationContext
        (activity as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch {
            // The same copy of the saves the Play button makes, and the game starts whatever happens to it.
            if (prefs.autoBackup) {
                runCatching { withTimeoutOrNull(4_000) { repo.backup(SaveBackups.Reason.PLAY) } }
            }
            Emulator.start(context, quickLoad)
        }
        return true
    }
}
