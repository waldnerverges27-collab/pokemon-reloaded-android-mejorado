package com.ahjin.pr

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** The exact, unmodified archive published as optional content. */
data class CryPackDefinition(
    val id: String,
    val title: String,
    val url: String,
    val pageUrl: String,
    val archiveBytes: Long,
    val uncompressedBytes: Long,
    val wavCount: Int,
    val sha256: String,
    val extraFiles: Set<String> = setOf("Historial de Cambio.txt"),
)

val ANIME_CRY_PACK = CryPackDefinition(
    id = "gritos-anime-20261029",
    title = "Gritos del anime",
    url = "https://github.com/waldnerverges27-collab/pokemon-reloaded-android-mejorado/releases/download/gritos-anime-20261029/Anime_Gritos.zip",
    pageUrl = "https://github.com/waldnerverges27-collab/pokemon-reloaded-android-mejorado/releases/tag/gritos-anime-20261029",
    archiveBytes = 215_099_791L,
    uncompressedBytes = 239_659_290L,
    wavCount = 1_284,
    sha256 = "bbb6cdcd3ab8e14435c04e528db0c60453edd108a527c0acbc69eee4286fb372",
)

data class CryPackStatus(
    val gameInstalled: Boolean,
    val downloaded: Boolean,
    val enabled: Boolean,
    val active: Boolean,
    val needsReapply: Boolean,
    val backupAvailable: Boolean,
    val packBytes: Long,
)

data class CryPackProgress(
    val stage: String,
    val percent: Int,
    val written: Long = 0L,
    val total: Long = 0L,
)

private data class CryState(
    val enabled: Boolean = false,
    val phase: String = "idle",
    val gameKey: String = "",
    val appliedAt: Long = 0L,
)

/**
 * Installs optional cries without mixing their storage with the game's own files.
 *
 * `pack/` is the verified overlay, kept so a new game install can be overlaid without downloading
 * 205 MiB again. `originals/grito/` is the last unmodified cry folder and is what Restore puts back.
 * Every game swap uses a sibling `.previous` directory, and the small state file makes an interrupted
 * swap recoverable the next time the screen is opened.
 */
class CryPackStore(
    private val filesDir: File,
    private val cacheDir: File,
    private val definition: CryPackDefinition = ANIME_CRY_PACK,
) {
    private val root get() = File(filesDir, "anime-cries/${definition.id}")
    private val packDir get() = File(root, "pack")
    private val packCries get() = File(packDir, "grito")
    private val packMeta get() = File(packDir, "pack.json")
    private val backupCries get() = File(root, "originals/grito")
    private val backupMeta get() = File(root, "originals/backup.json")
    private val stateFile get() = File(root, "state.json")
    private val workDir get() = File(root, "work")
    private val gameDir get() = File(filesDir, "game")
    private val gameMeta get() = File(filesDir, "game.json")
    private val targetCries get() = File(gameDir, "data/audio/grito")
    private val partialArchive get() = File(cacheDir, "${definition.id}.zip.part")

    fun status(): CryPackStatus {
        val state = readState()
        val key = gameKey()
        val game = targetCries.isDirectory
        val active = game && state.enabled && state.phase == "active" && state.gameKey == key
        return CryPackStatus(
            gameInstalled = game,
            downloaded = isDownloaded(),
            enabled = state.enabled,
            active = active,
            needsReapply = game && state.enabled && !active,
            backupAvailable = backupCries.isDirectory,
            packBytes = if (packDir.isDirectory) directoryBytes(packDir) else 0L,
        )
    }

    /** Recovers the two renames if the process died in the middle of an earlier apply/restore. */
    fun recoverInterrupted() {
        val state = readState()
        val target = targetCries
        val aside = sibling(target, "previous")
        when (state.phase) {
            "applying" -> when {
                aside.isDirectory && target.isDirectory -> {
                    aside.deleteRecursively()
                    writeState(state.copy(phase = "active"))
                }
                aside.isDirectory && !target.exists() -> {
                    if (!aside.renameTo(target)) error("No pude recuperar los gritos anteriores.")
                    writeState(state.copy(phase = "pending"))
                }
                else -> writeState(state.copy(phase = "pending"))
            }
            "restoring" -> when {
                aside.isDirectory && target.isDirectory -> {
                    aside.deleteRecursively()
                    writeState(CryState())
                }
                aside.isDirectory && !target.exists() -> {
                    if (!aside.renameTo(target)) error("No pude recuperar los gritos del anime.")
                    writeState(state.copy(phase = "active"))
                }
                else -> writeState(state.copy(phase = "active"))
            }
            else -> {
                // A generic safety net for a swap interrupted before the journal was written.
                if (!target.exists() && aside.isDirectory) aside.renameTo(target)
                else if (target.isDirectory && aside.isDirectory) aside.deleteRecursively()
            }
        }
        recoverBackupSwap()
    }

    /** Downloads if necessary, verifies, unpacks and applies. The partial download can be resumed. */
    fun install(
        cancelled: () -> Boolean = { false },
        onProgress: (CryPackProgress) -> Unit = {},
    ): String = synchronized(OPERATION_LOCK) {
        recoverInterrupted()
        if (status().active) return@synchronized "Los gritos del anime ya estaban aplicados."
        if (!isDownloaded()) {
            download(cancelled, onProgress)
            try {
                verifyArchive(partialArchive)
                unpackVerifiedArchive(partialArchive, cancelled, onProgress)
            } catch (t: Throwable) {
                // A cancelled extraction can reuse the complete, already verified archive. A bad
                // archive, on the other hand, must not be retried forever.
                if (t !is CancellationException) partialArchive.delete()
                throw t
            }
            // The verified overlay is what reapplications use; the 215 MB archive is not kept.
            partialArchive.delete()
        }
        applyDownloaded(cancelled, onProgress)
    }

    /** Applies the already unpacked overlay, without any network access. */
    fun applyDownloaded(
        cancelled: () -> Boolean = { false },
        onProgress: (CryPackProgress) -> Unit = {},
    ): String = synchronized(OPERATION_LOCK) {
        recoverInterrupted()
        if (status().active) return@synchronized "Los gritos del anime ya estaban aplicados."
        check(cancelled)
        require(isDownloaded()) { "El paquete todavia no esta descargado." }
        require(targetCries.isDirectory) { "Instala el juego antes de cambiar los gritos." }
        workDir.deleteRecursively()
        check(workDir.mkdirs() || workDir.isDirectory) { "No pude preparar la carpeta temporal." }

        val currentBytes = directoryBytes(targetCries)
        val packBytes = directoryBytes(packCries)
        ensureFreeSpace(filesDir, currentBytes * 2 + packBytes)
        val key = gameKey()
        val combined = File(workDir, "combined")
        val newBackup = File(workDir, "backup")

        onProgress(CryPackProgress("Preparando copia de seguridad", 88))
        copyDirectory(targetCries, newBackup, cancelled)
        copyDirectory(targetCries, combined, cancelled)
        onProgress(CryPackProgress("Fusionando los gritos", 92))
        copyDirectory(packCries, combined, cancelled)

        // The backup carries its own installation key. Even if the small state file is lost, an
        // existing baseline for this same game is safer than replacing it with a modified folder.
        if (!backupCries.isDirectory || backupGameKey() != key) {
            replaceBackup(newBackup, key)
        } else {
            newBackup.deleteRecursively()
        }

        onProgress(CryPackProgress("Aplicando los gritos", 96))
        check(cancelled)
        writeState(CryState(enabled = true, phase = "applying", gameKey = key))
        swapIntoPlace(combined, targetCries)
        writeState(CryState(enabled = true, phase = "active", gameKey = key, appliedAt = System.currentTimeMillis()))
        finishSwap(targetCries)
        workDir.deleteRecursively()
        onProgress(CryPackProgress("Terminado", 100))
        "Gritos del anime aplicados. Los originales tienen copia. Reinicia el juego para escucharlos."
    }

    /** Puts back the complete original folder; the downloaded pack remains available. */
    fun restore(
        cancelled: () -> Boolean = { false },
        onProgress: (CryPackProgress) -> Unit = {},
    ): String = synchronized(OPERATION_LOCK) {
        recoverInterrupted()
        require(status().active) { "Los gritos del anime no estan aplicados a esta instalacion." }
        require(targetCries.isDirectory) { "No hay un juego instalado." }
        require(backupCries.isDirectory) { "No encuentro la copia de los gritos originales." }
        workDir.deleteRecursively()
        check(workDir.mkdirs() || workDir.isDirectory) { "No pude preparar la carpeta temporal." }
        ensureFreeSpace(filesDir, directoryBytes(backupCries))
        val restored = File(workDir, "restored")
        onProgress(CryPackProgress("Preparando los originales", 40))
        copyDirectory(backupCries, restored, cancelled)
        onProgress(CryPackProgress("Restaurando los originales", 80))
        check(cancelled)
        writeState(readState().copy(phase = "restoring"))
        swapIntoPlace(restored, targetCries)
        writeState(CryState())
        finishSwap(targetCries)
        workDir.deleteRecursively()
        onProgress(CryPackProgress("Terminado", 100))
        "Gritos originales restaurados. Reinicia el juego para escucharlos."
    }

    /**
     * After a game reinstall the current folder is already original. In that case this only turns
     * off reapplication; copying the previous game's backup over it would be the wrong restore.
     */
    fun restoreOrDisable(
        cancelled: () -> Boolean = { false },
        onProgress: (CryPackProgress) -> Unit = {},
    ): String = synchronized(OPERATION_LOCK) {
        recoverInterrupted()
        if (status().active) return@synchronized restore(cancelled, onProgress)
        writeState(CryState())
        onProgress(CryPackProgress("Terminado", 100))
        "Se conservan los gritos originales de esta instalacion."
    }

    /** Called after a game zip has replaced `files/game`; returns true if an overlay was reapplied. */
    fun reapplyAfterGameInstall(): Boolean = synchronized(OPERATION_LOCK) {
        recoverInterrupted()
        val state = readState()
        if (!state.enabled || !isDownloaded() || !targetCries.isDirectory) return@synchronized false
        if (status().active) return@synchronized false
        applyDownloaded()
        true
    }

    /** Verifies the pinned archive before a single game file is touched. */
    internal fun installVerifiedArchiveForTest(
        archive: File,
        onProgress: (CryPackProgress) -> Unit = {},
    ): String = synchronized(OPERATION_LOCK) {
        verifyArchive(archive)
        unpackVerifiedArchive(archive, onProgress = onProgress)
        applyDownloaded(onProgress = onProgress)
    }

    private fun download(cancelled: () -> Boolean, onProgress: (CryPackProgress) -> Unit) {
        cacheDir.mkdirs()
        var existing = partialArchive.length().coerceAtLeast(0L)
        if (existing > definition.archiveBytes) {
            partialArchive.delete()
            existing = 0L
        }
        ensureFreeSpace(cacheDir, definition.archiveBytes - existing)
        if (existing == definition.archiveBytes) return
        val connection = (URL(definition.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ReloadedLauncher/1.0")
            setRequestProperty("Accept", "application/octet-stream")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code == 416 && existing == definition.archiveBytes) {
                return
            }
            if (code !in 200..299) error("La descarga respondio $code.")
            if (connection.url.protocol != "https") error("La descarga no uso una conexion segura.")
            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0 &&
                connection.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true
            if (!append) existing = 0L
            var written = existing
            connection.inputStream.use { input ->
                FileOutputStream(partialArchive, append).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(cancelled)
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written > definition.archiveBytes) error("La descarga es mas grande de lo esperado.")
                        onProgress(
                            CryPackProgress(
                                "Descargando ${definition.title}",
                                ((written * 70) / definition.archiveBytes).toInt().coerceIn(0, 70),
                                written,
                                definition.archiveBytes,
                            ),
                        )
                    }
                }
            }
            if (written != definition.archiveBytes) {
                error("Descarga incompleta: $written de ${definition.archiveBytes} bytes. Puedes reintentar y continuara.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyArchive(archive: File) {
        require(archive.isFile) { "No encuentro el ZIP descargado." }
        require(archive.length() == definition.archiveBytes) {
            "El ZIP tiene ${archive.length()} bytes; se esperaban ${definition.archiveBytes}."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(definition.sha256, ignoreCase = true)) {
            "El SHA-256 no coincide. No he cambiado ningun grito."
        }
    }

    private fun unpackVerifiedArchive(
        archive: File,
        cancelled: () -> Boolean = { false },
        onProgress: (CryPackProgress) -> Unit = {},
    ) {
        ensureFreeSpace(filesDir, definition.uncompressedBytes)
        val staging = File(root, "pack.staging")
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory) { "No pude preparar la extraccion." }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                val names = mutableSetOf<String>()
                var wavs = 0
                var total = 0L
                for (entry in entries) {
                    val name = entry.name.replace('\\', '/')
                    require(isSafeEntry(name)) { "El ZIP tiene una ruta no valida: ${entry.name}" }
                    if (entry.isDirectory) {
                        require(name == "grito/") { "Carpeta inesperada en el ZIP: ${entry.name}" }
                        continue
                    }
                    val isWav = name.startsWith("grito/") && name.endsWith(".wav", ignoreCase = true)
                    require(isWav || name in definition.extraFiles) { "Archivo inesperado en el ZIP: ${entry.name}" }
                    if (isWav) {
                        require(name.substringAfter("grito/").isNotEmpty() && !name.substringAfter("grito/").contains('/')) {
                            "Ruta de grito no valida: ${entry.name}"
                        }
                        wavs++
                    }
                    require(names.add(name.lowercase(Locale.ROOT))) { "Nombres duplicados en el ZIP: ${entry.name}" }
                    require(entry.size >= 0) { "No se pudo conocer el tamano de ${entry.name}." }
                    total += entry.size
                    require(total <= definition.uncompressedBytes) { "El ZIP descomprimido supera el tamano esperado." }
                }
                require(wavs == definition.wavCount) { "El ZIP trae $wavs WAV; se esperaban ${definition.wavCount}." }
                require(total == definition.uncompressedBytes) {
                    "El contenido descomprimido no tiene el tamano esperado."
                }

                var done = 0
                for (entry in entries) {
                    check(cancelled)
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/')
                    val target = File(staging, name)
                    val rootPath = staging.canonicalPath + File.separator
                    require(target.canonicalPath.startsWith(rootPath)) { "Ruta no valida: ${entry.name}" }
                    target.parentFile?.mkdirs()
                    val crc = CRC32()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                check(cancelled)
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                crc.update(buffer, 0, read)
                                written += read
                                if (written > entry.size) error("Entrada mas grande de lo declarado: ${entry.name}")
                            }
                            require(written == entry.size) { "Entrada incompleta: ${entry.name}" }
                        }
                    }
                    require(crc.value == entry.crc) { "CRC incorrecto en ${entry.name}." }
                    if (name.endsWith(".wav", ignoreCase = true)) validateWav(target)
                    done++
                    onProgress(CryPackProgress("Validando y extrayendo los WAV", 70 + (done * 15 / wavs).coerceAtMost(15)))
                }
            }
            val meta = JSONObject()
                .put("id", definition.id)
                .put("sha256", definition.sha256)
                .put("wavCount", definition.wavCount)
                .put("uncompressedBytes", definition.uncompressedBytes)
            File(staging, "pack.json").writeText(meta.toString())
            replacePack(staging)
            onProgress(CryPackProgress("Paquete verificado", 85))
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun isSafeEntry(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || '\u0000' in name) return false
        val parts = name.split('/')
        return parts.none { it == ".." || it == "." } && !Regex("^[A-Za-z]:").containsMatchIn(name)
    }

    private fun replacePack(staging: File) {
        root.mkdirs()
        val old = File(root, "pack.old")
        old.deleteRecursively()
        if (packDir.exists() && !packDir.renameTo(old)) error("No pude apartar el paquete anterior.")
        if (!staging.renameTo(packDir)) {
            old.renameTo(packDir)
            error("No pude guardar el paquete verificado.")
        }
        old.deleteRecursively()
    }

    private fun isDownloaded(): Boolean {
        if (!packCries.isDirectory || !packMeta.isFile) return false
        val meta = runCatching { JSONObject(packMeta.readText()) }.getOrNull() ?: return false
        return meta.optString("id") == definition.id &&
            meta.optString("sha256").equals(definition.sha256, ignoreCase = true) &&
            meta.optInt("wavCount", -1) == definition.wavCount &&
            packCries.listFiles()?.count { it.isFile && it.extension.equals("wav", ignoreCase = true) } == definition.wavCount
    }

    private fun replaceBackup(newBackup: File, key: String) {
        backupCries.parentFile?.mkdirs()
        swapIntoPlace(newBackup, backupCries)
        finishSwap(backupCries)
        backupMeta.writeText(JSONObject().put("gameKey", key).toString())
    }

    private fun backupGameKey(): String {
        if (!backupMeta.isFile) return ""
        return runCatching { JSONObject(backupMeta.readText()).optString("gameKey", "") }.getOrDefault("")
    }

    private fun swapIntoPlace(source: File, target: File) {
        target.parentFile?.mkdirs()
        val aside = sibling(target, "previous")
        aside.deleteRecursively()
        if (target.exists() && !target.renameTo(aside)) error("No pude apartar ${target.name}.")
        if (!source.renameTo(target)) {
            aside.renameTo(target)
            error("No pude mover ${target.name} a su carpeta definitiva.")
        }
        // Keep the old folder until the journal has recorded the successful swap. The caller deletes
        // it through recovery/next operation; deleting here is safe only after that state write, so
        // the actual deletion is deliberately left to `finishSwap` below.
    }

    /** Removes the old folder after the state journal has been written. */
    private fun finishSwap(target: File) {
        sibling(target, "previous").deleteRecursively()
    }

    private fun recoverBackupSwap() {
        val aside = sibling(backupCries, "previous")
        if (!backupCries.exists() && aside.isDirectory) aside.renameTo(backupCries)
        else if (backupCries.isDirectory && aside.isDirectory) aside.deleteRecursively()
    }

    private fun sibling(file: File, suffix: String) = File(file.parentFile, "${file.name}.$suffix")

    private fun copyDirectory(from: File, to: File, cancelled: () -> Boolean = { false }) {
        require(from.isDirectory) { "No encuentro ${from.name}." }
        val rootPath = from.canonicalPath
        for (source in from.walkTopDown()) {
            check(cancelled)
            val relative = source.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar)
            val target = if (relative.isEmpty()) to else File(to, relative)
            if (source.isDirectory) {
                check(target.mkdirs() || target.isDirectory) { "No pude crear ${target.path}." }
            } else {
                target.parentFile?.mkdirs()
                source.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(cancelled)
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                check(source.length() == target.length()) { "No se copio completo ${source.name}." }
            }
        }
    }

    private fun directoryBytes(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun ensureFreeSpace(directory: File, additional: Long) {
        directory.mkdirs()
        val margin = 32L * 1024 * 1024
        val available = directory.usableSpace
        require(available >= additional.coerceAtLeast(0L) + margin) {
            "No hay espacio suficiente: hacen falta ${(additional + margin) / (1024 * 1024)} MiB libres."
        }
    }

    private fun gameKey(): String {
        if (!gameMeta.isFile) return ""
        return runCatching { JSONObject(gameMeta.readText()).optLong("installedAt", 0L).toString() }.getOrDefault("")
    }

    private fun readState(): CryState {
        if (!stateFile.isFile) return CryState()
        val json = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: return CryState()
        return CryState(
            enabled = json.optBoolean("enabled", false),
            phase = json.optString("phase", "idle"),
            gameKey = json.optString("gameKey", ""),
            appliedAt = json.optLong("appliedAt", 0L),
        )
    }

    private fun writeState(state: CryState) {
        root.mkdirs()
        val json = JSONObject()
            .put("enabled", state.enabled)
            .put("phase", state.phase)
            .put("gameKey", state.gameKey)
            .put("appliedAt", state.appliedAt)
        stateFile.writeText(json.toString())
    }

    private fun check(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("Operacion cancelada.")
    }

    private companion object {
        val OPERATION_LOCK = Any()
    }
}

/** Structural RIFF/WAVE validation; accepts the PCM mono/stereo and 8/16-bit forms in the pack. */
internal fun validateWav(file: File) {
    RandomAccessFile(file, "r").use { wav ->
        require(wav.length() >= 44) { "WAV demasiado corto: ${file.name}" }
        val header = ByteArray(12)
        wav.readFully(header)
        require(header.copyOfRange(0, 4).contentEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())) &&
            header.copyOfRange(8, 12).contentEquals(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))) {
            "No es un WAV RIFF valido: ${file.name}"
        }
        var format = -1
        var channels = 0
        var sampleRate = 0L
        var bits = 0
        var data = false
        while (wav.filePointer + 8 <= wav.length()) {
            val id = ByteArray(4)
            wav.readFully(id)
            val size = readLe32(wav)
            val start = wav.filePointer
            require(size >= 0 && start + size <= wav.length()) { "Chunk WAV truncado: ${file.name}" }
            when {
                id.contentEquals(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte())) -> {
                    require(size >= 16) { "Formato WAV incompleto: ${file.name}" }
                    format = readLe16(wav)
                    channels = readLe16(wav)
                    sampleRate = readLe32(wav)
                    wav.seek(start + 14)
                    bits = readLe16(wav)
                }
                id.contentEquals(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte())) -> {
                    data = size > 0
                }
            }
            wav.seek(start + size + (size and 1L))
        }
        require(format == 1 && channels in 1..8 && sampleRate in 8_000L..192_000L && bits in setOf(8, 16, 24, 32) && data) {
            "Formato WAV no compatible: ${file.name}"
        }
    }
}

private fun readLe16(file: RandomAccessFile): Int {
    val a = file.read()
    val b = file.read()
    require(a >= 0 && b >= 0) { "Dato WAV incompleto." }
    return a or (b shl 8)
}

private fun readLe32(file: RandomAccessFile): Long {
    val a = readLe16(file).toLong()
    val b = readLe16(file).toLong()
    return a or (b shl 16)
}
