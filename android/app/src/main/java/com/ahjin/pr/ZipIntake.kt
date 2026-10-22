package com.ahjin.pr

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * A zip handed to the app from outside: "Abrir con" in a file manager (a long press on the file), or
 * "Compartir". The launcher's own pickers keep working as they were; this is the other way in.
 *
 * Two things live here. [classify] looks inside the zip and says whether it is the game, a set of
 * saves or one single save, so the player is asked the right question. And [work] holds the import
 * itself in a scope of the process rather than of the screen: turning the phone while 200 MB are being
 * unpacked used to be able to cancel the screen that was waiting for it, and with it the progress.
 */
object ZipIntake {

    sealed interface Kind {
        /** The game itself: it has the executable and the `data` folder. */
        data object Game : Kind

        /** Saves, teams and characters (what "Exportar todo" and the safety copies make). */
        data object Saves : Kind

        /** One single slot, as "Exportar" in a slot makes it. [number] is the slot it came from. */
        data class Slot(val number: Int) : Kind

        data object Unknown : Kind
    }

    /** What the system says about the document: its name and size, when it says them. */
    data class Doc(val name: String?, val size: Long)

    sealed interface Work {
        data object Idle : Work
        data class Running(val title: String, val percent: Int) : Work
        data class Finished(val ok: Boolean, val message: String) : Work
    }

    private val SAVE_FOLDERS = listOf("partidas/", "equipos/", "char_custom/")
    private val SLOT_FILE = Regex("^partidas/reloaded(\\d+)[._]", RegexOption.IGNORE_CASE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val work = MutableStateFlow<Work>(Work.Idle)

    /** Counts the imports that ended, so the screens behind the dialog know to read the game again. */
    val changes = MutableStateFlow(0)

    /** The zip a VIEW or SEND intent carries, or null if the intent is anything else. */
    fun uriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }

    /** The result of an import nobody looked at must not greet the next zip that is opened. */
    fun clearFinished() {
        work.update { if (it is Work.Finished) Work.Idle else it }
    }

    fun describe(context: Context, uri: Uri): Doc {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return Doc(name ?: uri.lastPathSegment?.substringAfterLast('/'), size)
    }

    suspend fun classify(context: Context, uri: Uri): Result<Kind> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                context.contentResolver.openInputStream(uri)?.use { classify(it) } ?: error("No pude abrir el fichero.")
            } catch (e: SecurityException) {
                error("Android no me dejo leer ese archivo. Prueba con Importar desde la propia app.")
            }
        }
    }

    /**
     * Reads the entry names of a zip and decides what it holds, without unpacking anything.
     *
     * The names are read as ISO-8859-1 because the game's own zip has two names that are not valid
     * UTF-8, and the default reader gives up on the whole archive at the first one. It stops at the
     * first sign of the game (an executable, or a `data` folder), so the big zip is not read through;
     * a zip of saves is small and is read to the end to tell one slot from several.
     */
    fun classify(input: InputStream): Kind {
        var saves = false
        var other = false
        val slots = sortedSetOf<Int>()
        ZipInputStream(input.buffered(), StandardCharsets.ISO_8859_1).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                val lower = name.lowercase()
                if (!entry.isDirectory && lower.endsWith(".exe")) return Kind.Game
                if (lower.startsWith("data/") || lower.contains("/data/")) return Kind.Game
                if (SAVE_FOLDERS.any { name.startsWith(it) }) {
                    saves = true
                    SLOT_FILE.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { slots += it }
                    if (!name.startsWith("partidas/")) other = true
                } else if (name != "backup.info" && !entry.isDirectory) {
                    other = true
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!saves) return Kind.Unknown
        // One slot and nothing else is a save to be moved; anything more is a restore.
        if (!other && slots.size == 1) return Kind.Slot(slots.first())
        return Kind.Saves
    }

    /**
     * Runs an import in the process's own scope. One at a time: a second request while one is running
     * is ignored, which is what stops two copies fighting over the same temporary file.
     */
    fun start(title: String, block: suspend (onProgress: (Int) -> Unit) -> Result<String>) {
        if (work.value is Work.Running) return
        work.value = Work.Running(title, 0)
        scope.launch {
            val result = runCatching {
                block { percent -> work.update { if (it is Work.Running) Work.Running(title, percent) else it } }
            }.getOrElse { Result.failure(it) }
            changes.update { it + 1 }
            work.value = result.fold(
                { Work.Finished(true, it) },
                { Work.Finished(false, it.message ?: "No pude importarlo.") },
            )
        }
    }

    fun dismiss() {
        work.update { if (it is Work.Running) it else Work.Idle }
    }
}
