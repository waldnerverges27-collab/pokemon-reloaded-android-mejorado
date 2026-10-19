package com.ahjin.pr

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Safety copies of the player's saves.
 *
 * What counts as a save is the same as everywhere else in the app: the folders `partidas`,
 * `equipos` and `char_custom` inside the game's `data` directory. A copy is a zip with exactly that
 * layout (plus one small `backup.info` entry), so it can also be imported by hand through
 * "Mis partidas" and opened on a PC.
 *
 * Nothing here depends on Android, so it is tested on the JVM. Every write goes to a temporary file
 * that is checked and then renamed, and a restore keeps the old folders aside until the new ones are
 * in place and puts them back if anything goes wrong: a failure must never leave the player with
 * fewer saves than before.
 */
class SaveBackups(
    private val dataDir: File,
    private val backupDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Why a copy was made. The name is what ends up in the file name and in `backup.info`. */
    object Reason {
        const val MANUAL = "manual"
        const val PLAY = "al-jugar"
        const val BEFORE_IMPORT = "antes-de-importar"
        const val BEFORE_DELETE = "antes-de-borrar"
        const val BEFORE_INSTALL = "antes-de-instalar"
        const val BEFORE_RESTORE = "antes-de-restaurar"
        const val BEFORE_UPDATE = "antes-de-actualizar"
    }

    data class Backup(
        val file: File,
        val createdAt: Long,
        val reason: String,
        /** Save slots in the copy (the `.savc` files, not counting the game's own `_bk` ones). */
        val saves: Int,
        val files: Int,
        val sizeBytes: Long,
        val fingerprint: String,
    ) {
        val manual: Boolean get() = reason == Reason.MANUAL
    }

    companion object {
        /** The folders that make a save, in the order they are written. */
        val FOLDERS = listOf("partidas", "equipos", "char_custom")

        private const val INFO = "backup.info"
        private const val KEEP_PLAY = 5
        private const val KEEP_SAFETY = 10
        private const val TMP = ".tmp"
        private const val STAGING = ".restore-staging"
        private const val ASIDE = ".restore-aside"

        fun isSlotFile(path: String): Boolean =
            path.startsWith("partidas/") && path.endsWith(".savc", ignoreCase = true) && !path.contains("_bk", ignoreCase = true)

        private fun slug(text: String): String =
            text.lowercase(Locale.ROOT).map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("").trim('-').ifEmpty { "copia" }
    }

    /** The copy being restored: pruning must not remove it halfway through. */
    private var inUse: File? = null

    /** Every file that makes up the saves right now, with its path inside a copy. */
    private fun sources(): List<Pair<String, File>> = buildList {
        for (folder in FOLDERS) {
            val root = File(dataDir, folder)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .forEach { add("$folder/" + it.relativeTo(root).invariantSeparatorsPath to it) }
        }
    }

    /** A hash of names and contents: two states with the same hash are the same saves. */
    private fun fingerprint(files: List<Pair<String, File>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        for ((path, file) in files) {
            digest.update(path.toByteArray())
            digest.update(0)
            digest.update(file.length().toString().toByteArray())
            digest.update(0)
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Whether there is anything to back up at all. */
    fun hasSaves(): Boolean = sources().isNotEmpty()

    /**
     * Makes a copy of the saves as they are now.
     *
     * Returns the copy, or `null` when there was nothing to copy or the saves are identical to the
     * latest copy (so playing and quitting without saving does not fill the list with duplicates).
     * [force] skips that second check, which a copy the player asked for does.
     */
    fun create(reason: String, force: Boolean = false): Result<Backup?> = runCatching {
        val files = sources()
        if (files.isEmpty()) return@runCatching null
        val print = fingerprint(files)
        if (!force && list().firstOrNull()?.fingerprint == print) return@runCatching null

        if (!backupDir.isDirectory && !backupDir.mkdirs()) throw IOException("No pude crear la carpeta de copias.")
        val created = clock()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(created))
        val base = "copia-$stamp-${slug(reason)}"
        var target = File(backupDir, "$base.zip")
        var n = 2
        while (target.exists()) target = File(backupDir, "$base-${n++}.zip")
        val tmp = File(backupDir, target.name + TMP)

        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                var slots = 0
                for ((path, file) in files) {
                    zip.putNextEntry(ZipEntry(path))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (isSlotFile(path)) slots++
                }
                zip.putNextEntry(ZipEntry(INFO))
                zip.write(
                    buildString {
                        appendLine("created=$created")
                        appendLine("reason=${reason.filter { it != '\n' && it != '\r' }}")
                        appendLine("files=${files.size}")
                        appendLine("saves=$slots")
                        appendLine("fingerprint=$print")
                    }.toByteArray(),
                )
                zip.closeEntry()
            }
            // A copy that cannot be read back is worse than none: it would be trusted later.
            ZipFile(tmp).use { check ->
                if (check.size() != files.size + 1) throw IOException("La copia quedo incompleta.")
            }
            if (!tmp.renameTo(target)) throw IOException("No pude guardar la copia.")
        } finally {
            tmp.delete()
        }
        prune()
        read(target) ?: throw IOException("No pude leer la copia que acabo de hacer.")
    }

    /** The copies, newest first. A file that is not a readable copy is left out, not an error. */
    fun list(): List<Backup> =
        backupDir.listFiles { f -> f.isFile && f.name.startsWith("copia-") && f.name.endsWith(".zip") }
            .orEmpty()
            .mapNotNull { read(it) }
            .sortedWith(compareByDescending<Backup> { it.createdAt }.thenByDescending { it.file.name })

    private fun read(file: File): Backup? = runCatching {
        ZipFile(file).use { zip ->
            val info = zip.getEntry(INFO) ?: return null
            val fields = zip.getInputStream(info).bufferedReader().readLines()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
            Backup(
                file = file,
                createdAt = fields["created"]?.toLongOrNull() ?: return null,
                reason = fields["reason"].orEmpty(),
                saves = fields["saves"]?.toIntOrNull() ?: 0,
                files = fields["files"]?.toIntOrNull() ?: 0,
                sizeBytes = file.length(),
                fingerprint = fields["fingerprint"].orEmpty(),
            )
        }
    }.getOrNull()

    fun totalSize(): Long = list().sumOf { it.sizeBytes }

    fun delete(backup: Backup): Boolean = backup.file.parentFile?.canonicalPath == backupDir.canonicalPath && backup.file.delete()

    /**
     * Keeps the folder from growing without limit. Copies made by playing are the most numerous and
     * the least valuable, so they are kept in a smaller number than the ones made before something
     * destructive. The ones the player made by hand are never removed on their own.
     */
    private fun prune() {
        val all = list().filter { !it.manual }
        val (play, safety) = all.partition { it.reason == Reason.PLAY }
        (play.drop(KEEP_PLAY) + safety.drop(KEEP_SAFETY)).filter { it.file != inUse }.forEach { it.file.delete() }
        // Leftovers of a write that was interrupted.
        backupDir.listFiles { f -> f.name.endsWith(TMP) }.orEmpty().forEach { it.delete() }
    }

    /**
     * Puts a copy back, replacing the saves that are there. The current saves are copied first (as
     * "antes-de-restaurar") so a restore can itself be undone.
     */
    fun restore(backup: Backup): Result<Int> = runCatching {
        if (!dataDir.isDirectory) throw IOException("Primero instala el juego.")
        inUse = backup.file
        try {
            doRestore(backup)
        } finally {
            inUse = null
        }
    }

    private fun doRestore(backup: Backup): Int {
        val staging = File(dataDir, STAGING)
        val aside = File(dataDir, ASIDE)
        staging.deleteRecursively()
        aside.deleteRecursively()

        // Check and unpack the copy first, so a damaged one is found before anything is touched.
        var restored = 0
        try {
            ZipFile(backup.file).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (entry.isDirectory || FOLDERS.none { name.startsWith("$it/") }) continue
                    val out = File(staging, name)
                    if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw IOException("La copia trae una ruta no valida: $name")
                    }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    if (isSlotFile(name)) restored++
                }
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw IOException("La copia esta danada: ${e.message}")
        }

        create(Reason.BEFORE_RESTORE).getOrElse {
            staging.deleteRecursively()
            throw IOException("No pude guardar tus partidas actuales antes de restaurar, asi que no he tocado nada.")
        }

        // Swap folder by folder, remembering what was moved so it can be moved back.
        val moved = mutableListOf<String>()
        val placed = mutableListOf<String>()
        try {
            aside.mkdirs()
            for (folder in FOLDERS) {
                val current = File(dataDir, folder)
                if (current.exists()) {
                    if (!current.renameTo(File(aside, folder))) throw IOException("No pude apartar la carpeta $folder.")
                    moved += folder
                }
                val fresh = File(staging, folder)
                if (fresh.isDirectory) {
                    if (!fresh.renameTo(current)) throw IOException("No pude colocar la carpeta $folder.")
                    placed += folder
                }
            }
        } catch (e: Exception) {
            for (folder in placed) File(dataDir, folder).deleteRecursively()
            for (folder in moved) File(aside, folder).renameTo(File(dataDir, folder))
            staging.deleteRecursively()
            aside.deleteRecursively()
            throw e
        }
        staging.deleteRecursively()
        aside.deleteRecursively()
        return restored
    }
}
