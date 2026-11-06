package com.ahjin.pr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

/** Process-owned cry-pack work: rotating the launcher does not cancel a 205 MiB download. */
object CryPacks {
    sealed interface Work {
        data object Idle : Work
        data class Running(
            val stage: String,
            val percent: Int,
            val written: Long = 0L,
            val total: Long = 0L,
        ) : Work
        data class Finished(val ok: Boolean, val message: String) : Work
    }

    enum class Action { INSTALL, APPLY, RESTORE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cancelRequested = false

    val work = MutableStateFlow<Work>(Work.Idle)
    val changes = MutableStateFlow(0)
    val isBusy: Boolean get() = work.value is Work.Running

    fun status(context: Context): CryPackStatus = store(context).status()

    @Synchronized
    fun start(context: Context, action: Action) {
        if (isBusy) return
        if (Emulator.isRunning) {
            work.value = Work.Finished(false, "Cierra el juego antes de cambiar los gritos. Despues vuelve a Ajustes.")
            return
        }
        cancelRequested = false
        work.value = Work.Running("Preparando los gritos", 0)
        val app = context.applicationContext
        scope.launch {
            val store = store(app)
            val result = try {
                val message = when (action) {
                    Action.INSTALL -> store.install(::isCancellationRequested, ::progress)
                    Action.APPLY -> store.applyDownloaded(::isCancellationRequested, ::progress)
                    Action.RESTORE -> store.restoreOrDisable(::isCancellationRequested, ::progress)
                }
                Result.success(message)
            } catch (e: CancellationException) {
                Result.failure(e)
            } catch (t: Throwable) {
                Result.failure(t)
            }
            if (result.isFailure) runCatching { store.recoverInterrupted() }
            changes.update { it + 1 }
            work.value = result.fold(
                { Work.Finished(true, it) },
                { Work.Finished(false, it.message ?: "No pude cambiar los gritos.") },
            )
        }
    }

    /** Cooperative: network reads and file copies stop at the next block, without corrupting a swap. */
    fun cancel() {
        cancelRequested = true
    }

    fun dismiss() {
        work.update { if (it is Work.Running) it else Work.Idle }
    }

    private fun isCancellationRequested(): Boolean = cancelRequested

    private fun progress(progress: CryPackProgress) {
        work.update {
            if (it is Work.Running) {
                Work.Running(progress.stage, progress.percent, progress.written, progress.total)
            } else {
                it
            }
        }
    }

    private fun store(context: Context) = CryPackStore(context.filesDir, context.cacheDir)
}
