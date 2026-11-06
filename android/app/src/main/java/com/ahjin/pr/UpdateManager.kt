package com.ahjin.pr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Owns update work independently of a Compose activity. The small journal lives in internal files;
 * the large APK lives in cache. If the process is reopened, the actual file length is authoritative.
 */
object UpdateManager {
    data class Work(
        val release: Update.Release? = null,
        val running: Boolean = false,
        val verifying: Boolean = false,
        val written: Long = 0,
        val total: Long = 0,
        val resumable: Boolean = false,
        val readyToInstall: Boolean = false,
        val problem: String? = null,
    )

    private val mutableWork = MutableStateFlow(Work())
    val work: StateFlow<Work> = mutableWork.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    @Volatile private var cancelRequested = false

    /** Returns true only for the process's first automatic release check. */
    @Synchronized
    fun initialize(context: Context): Boolean {
        if (initialized) return false
        initialized = true
        val restored = readWork(context)
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        mutableWork.value = if (
            restored.release != null && installed.isNotEmpty() && !Update.isNewer(restored.release.version, installed)
        ) {
            journal(context).delete()
            partial(context).delete()
            verified(context).delete()
            Work()
        } else {
            restored
        }
        return true
    }

    /** A manual or automatic check can replace a waiting offer, but never interrupts a download. */
    @Synchronized
    fun offer(context: Context, release: Update.Release) {
        if (mutableWork.value.running) return
        val saved = readWork(context)
        mutableWork.value = if (sameRelease(saved.release, release)) {
            saved.copy(problem = null)
        } else {
            Work(release = release, total = release.apkSize)
        }
    }

    @Synchronized
    fun start(context: Context, repo: GameRepository) {
        val current = mutableWork.value
        val release = current.release ?: return
        if (current.running || release.apkUrl == null) return
        cancelRequested = false

        if (!current.readyToInstall) {
            val saved = readWork(context)
            if (!sameRelease(saved.release, release)) {
                partial(context).delete()
                verified(context).delete()
                journal(context).delete()
            }
            persist(context, release, current.written, current.total, ready = false)
        }
        mutableWork.value = current.copy(running = true, verifying = current.readyToInstall, problem = null)
        scope.launch {
            if (current.readyToInstall) installVerified(context, repo, release)
            else downloadThenInstall(context, repo, release)
        }
    }

    @Synchronized
    fun cancel() {
        if (mutableWork.value.running && !mutableWork.value.verifying) cancelRequested = true
    }

    /** Dismisses the offer, not the saved bytes. A new process can offer the resumable work again. */
    @Synchronized
    fun dismiss() {
        if (!mutableWork.value.running) mutableWork.value = Work()
    }

    private suspend fun downloadThenInstall(context: Context, repo: GameRepository, release: Update.Release) {
        val part = partial(context)
        verified(context).delete()
        var lastSaved = -1L
        val result = Update.download(
            url = release.apkUrl!!,
            target = part,
            expectedSize = release.apkSize,
            cancelled = { cancelRequested },
            onProgress = { done, size ->
                mutableWork.value = Work(release, running = true, written = done, total = size)
                if (lastSaved < 0 || done - lastSaved >= 1024 * 1024 || done == size) {
                    persist(context, release, done, size, ready = false)
                    lastSaved = done
                }
            },
        )
        if (result.isFailure) {
            pauseDownload(context, release, result.exceptionOrNull()!!)
            return
        }
        if (cancelRequested) {
            pauseDownload(context, release, kotlinx.coroutines.CancellationException("Descarga cancelada."))
            return
        }

        val complete = verified(context)
        complete.delete()
        if (!part.renameTo(complete)) {
            runCatching { part.copyTo(complete, overwrite = true) }
                .getOrElse {
                    mutableWork.value = Work(
                        release = release,
                        written = part.length(),
                        total = release.apkSize,
                        resumable = part.isFile && part.length() > 0,
                        problem = "No pude preparar el APK descargado: ${it.message}",
                    )
                    return
                }
            part.delete()
        }
        persist(context, release, complete.length(), release.apkSize, ready = true)
        installVerified(context, repo, release)
    }

    private fun pauseDownload(context: Context, release: Update.Release, failure: Throwable) {
        val part = partial(context)
        val done = if (part.isFile) part.length() else 0L
        persist(context, release, done, release.apkSize, ready = false)
        mutableWork.value = Work(
            release = release,
            written = done,
            total = release.apkSize,
            resumable = done > 0,
            problem = friendlyError(failure),
        )
    }

    /** Revalidates even a previously verified cache file immediately before handing it to Android. */
    private suspend fun installVerified(context: Context, repo: GameRepository, release: Update.Release) {
        val apk = verified(context)
        mutableWork.value = Work(
            release = release,
            running = true,
            verifying = true,
            written = apk.length(),
            total = release.apkSize,
            readyToInstall = true,
        )
        val problem = Update.verify(context, apk, release)
        if (problem != null) {
            apk.delete()
            journal(context).delete()
            mutableWork.value = Work(release = release, total = release.apkSize, problem = problem)
            return
        }

        runCatching { repo.backup(SaveBackups.Reason.BEFORE_UPDATE) }
        val installProblem = installUpdate(context, apk)
        if (installProblem == null) {
            // Keep the APK: the system installer may still be reading its granted content URI.
            journal(context).delete()
            mutableWork.value = Work()
        } else {
            persist(context, release, apk.length(), release.apkSize, ready = true)
            mutableWork.value = Work(
                release = release,
                written = apk.length(),
                total = release.apkSize,
                readyToInstall = true,
                problem = installProblem,
            )
        }
    }

    private fun readWork(context: Context): Work {
        val file = journal(context)
        if (!file.isFile) return Work()
        return runCatching {
            val json = JSONObject(file.readText())
            val release = Update.Release(
                version = json.getString("version"),
                apkUrl = json.optString("apkUrl").ifEmpty { null },
                pageUrl = json.optString("pageUrl", Update.RELEASES_PAGE),
                apkSize = json.optLong("apkSize"),
                apkSha256 = json.optString("apkSha256"),
                notes = json.optString("notes"),
            )
            val ready = json.optBoolean("ready")
            val apk = if (ready) verified(context) else partial(context)
            if (!apk.isFile) {
                file.delete()
                return Work()
            }
            val done = apk.length()
            if (ready && release.apkSize > 0 && done != release.apkSize) {
                apk.delete()
                file.delete()
                return Work()
            }
            Work(
                release = release,
                written = done,
                total = release.apkSize.takeIf { it > 0 } ?: json.optLong("total"),
                resumable = !ready && done > 0,
                readyToInstall = ready,
            )
        }.getOrElse {
            file.delete()
            Work()
        }
    }

    private fun persist(
        context: Context,
        release: Update.Release,
        written: Long,
        total: Long,
        ready: Boolean,
    ) {
        val json = JSONObject()
            .put("version", release.version)
            .put("apkUrl", release.apkUrl.orEmpty())
            .put("pageUrl", release.pageUrl)
            .put("apkSize", release.apkSize)
            .put("apkSha256", release.apkSha256)
            .put("notes", release.notes)
            .put("written", written)
            .put("total", total)
            .put("ready", ready)
        val target = journal(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            temporary.writeText(json.toString())
            if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun sameRelease(saved: Update.Release?, offered: Update.Release) =
        saved?.version == offered.version && saved.apkUrl == offered.apkUrl

    private fun friendlyError(error: Throwable): String = when (error) {
        is kotlinx.coroutines.CancellationException ->
            "Descarga cancelada. Lo ya descargado se conserva para continuar despues."
        is UnknownHostException ->
            "No hay conexion con GitHub. Revisa Internet; podras continuar sin repetir lo ya descargado."
        is SocketTimeoutException ->
            "GitHub tardo demasiado en responder. Revisa la conexion y pulsa Continuar."
        is SSLException ->
            "No se pudo verificar la conexion segura con GitHub. Revisa la fecha del telefono y la red."
        is SecurityException ->
            "Android no concedio acceso a Internet. Activa el permiso y vuelve a intentarlo."
        is IOException -> {
            if (error.message?.contains("space", ignoreCase = true) == true) {
                "No hay espacio suficiente. Libera espacio y continua la descarga."
            } else {
                "Se interrumpio la conexion. Lo ya descargado se conserva; pulsa Continuar."
            }
        }
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "Error de descarga (${error::class.java.simpleName})."
    }

    private fun journal(context: Context) = File(context.filesDir, "update-download.json")
    private fun partial(context: Context) = File(context.cacheDir, "update.apk.part")
    private fun verified(context: Context) = File(context.cacheDir, "update.apk")
}
