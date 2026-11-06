package com.ahjin.pr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val APK_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/** Chooses an APK for this device. An exact ABI match beats a universal/legacy asset; an asset
 * explicitly made for an unsupported ABI is not offered. The list order is the device's ABI order. */
internal fun chooseApkName(names: List<String>, supportedAbis: List<String>): String? {
    fun rank(name: String): Int {
        val abi = APK_ABIS.firstOrNull { name.contains(it, ignoreCase = true) } ?: return 0
        val index = supportedAbis.indexOfFirst { it.equals(abi, ignoreCase = true) }
        return if (index >= 0) 1000 - index else -1
    }
    return names.filter { it.endsWith(".apk", ignoreCase = true) }
        .mapNotNull { name -> rank(name).takeIf { it >= 0 }?.let { name to it } }
        .maxByOrNull { it.second }?.first
}

/**
 * Looks for a newer build on GitHub. The repository's releases are the only place a version is
 * published, so there is no server to keep running and nothing to keep in step.
 */
object Update {
    private const val REPO = "waldnerverges27-collab/pokemon-reloaded-android-mejorado"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    data class Release(
        val version: String,
        val apkUrl: String?,
        val pageUrl: String,
        /** Size the release says the APK has, to catch a download that stopped halfway. 0 if unknown. */
        val apkSize: Long = 0,
        /** GitHub's asset digest, when present. Empty keeps compatibility with older releases. */
        val apkSha256: String = "",
        /** What changed, as written in the release. */
        val notes: String = "",
    )

    /** The newer release, or `null` when the installed build is already the latest one. */
    suspend fun check(installed: String): Result<Release?> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(get(LATEST))
            val tag = json.optString("tag_name").trim().trimStart('v', 'V')
            if (tag.isEmpty() || !isNewer(tag, installed)) {
                return@runCatching null
            }
            val apks = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            }.orEmpty()
            val chosen = chooseApkName(apks.map { it.optString("name") }, android.os.Build.SUPPORTED_ABIS.toList())
            val asset = apks.firstOrNull { it.optString("name") == chosen }
            Release(
                version = tag,
                apkUrl = asset?.optString("browser_download_url")?.takeIf { it.isNotEmpty() },
                pageUrl = json.optString("html_url").ifEmpty { RELEASES_PAGE },
                apkSize = asset?.optLong("size", 0L) ?: 0L,
                apkSha256 = sha256FromDigest(asset?.optString("digest")),
                notes = json.optString("body").trim(),
            )
        }
    }

    /**
     * Downloads the release's APK so the system installer can be handed a local file.
     *
     * Reports the bytes as they arrive, because sixty megabytes with no sign of life cannot be told
     * apart from a download that has died. Everything that happens is also written to the log, since
     * the failure used to be swallowed and the app simply opened the browser as if nothing was wrong.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
        expectedSize: Long = 0,
        cancelled: () -> Boolean = { false },
    ): Result<File> = withContext(Dispatchers.IO) {
        trace("update: descarga desde $url; parcial=${target.length()} bytes")
        runCatching { downloadFile(url, target, expectedSize, cancelled, onProgress) }
            .onSuccess { trace("update: descargados ${it.length()} bytes") }
            .onFailure { trace("update: FALLO la descarga: ${it::class.java.simpleName}: ${it.message}") }
    }

    /**
     * Checks the downloaded file before the installer sees it: it must be complete, be this same
     * app, be newer, and be signed with the same key. Android would refuse a differently signed
     * update anyway, but only after the user has gone through the installer, with a message that
     * does not say why. Returns the reason in words, or `null` when the file is fine.
     */
    fun verify(context: android.content.Context, file: File, release: Release): String? {
        if (release.apkSize > 0 && file.length() != release.apkSize) {
            return "La descarga quedo incompleta (${file.length()} de ${release.apkSize} bytes). Vuelve a intentarlo."
        }
        if (release.apkSha256.isNotEmpty()) {
            val actual = runCatching { fileSha256(file) }.getOrElse {
                return "No pude calcular el SHA-256 del APK: ${it.message}"
            }
            if (actual != release.apkSha256) {
                return "El SHA-256 del APK no coincide con el publicado por GitHub. Se ha descartado la descarga."
            }
        }
        return runCatching {
            val pm = context.packageManager
            val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
                ?: return "El archivo descargado no es un APK valido."
            if (archive.packageName != context.packageName) {
                return "El archivo descargado es de otra aplicacion (${archive.packageName})."
            }
            val installed = pm.getPackageInfo(context.packageName, flags)
            if (archive.longVersionCode <= installed.longVersionCode) {
                return "La version descargada no es mas nueva que la instalada."
            }
            fun certs(info: android.content.pm.PackageInfo) =
                info.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet().orEmpty()
            val mine = certs(installed)
            val theirs = certs(archive)
            if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
                "La actualizacion esta firmada con otra clave y Android no la dejaria instalar encima. " +
                    "Desinstala la app (exporta antes tus partidas) o instala la version nueva a mano."
            } else {
                null
            }
        }.getOrElse { "No pude comprobar el archivo: ${it::class.java.simpleName}: ${it.message}" }
    }

    private fun trace(message: String) {
        android.util.Log.i("Reloaded", message)
    }

    /**
     * True when `candidate` is ahead of `installed`, comparing the numbers one by one so that 0.1.10
     * counts as newer than 0.1.9 (a plain string comparison would get that wrong).
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(installed)
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun numbers(value: String) = value.trim().split('.', '-', '+').mapNotNull { it.toIntOrNull() }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", "ReloadedLauncher/1.0")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun get(url: String): String {
        val connection = open(url)
        connection.connect()
        if (connection.responseCode !in 200..299) error("GitHub respondio ${connection.responseCode}.")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

/** Returns a lowercase SHA-256 only when GitHub supplied a complete `sha256:` digest. */
internal fun sha256FromDigest(digest: String?): String {
    val value = digest.orEmpty().trim()
    return value.removePrefix("sha256:").takeIf {
        value.startsWith("sha256:") && it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
    }?.lowercase().orEmpty()
}

internal fun fileSha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Blocking downloader, separate from Android and coroutines so its range behavior can be tested on
 * the JVM. A `.part` file is deliberately kept after an error. A 206 response is accepted only for
 * the exact range requested; a server answering 200 starts from zero instead of duplicating bytes.
 */
internal fun downloadFile(
    url: String,
    target: File,
    expectedSize: Long,
    cancelled: () -> Boolean,
    onProgress: (Long, Long) -> Unit,
): File {
    target.parentFile?.mkdirs()
    var existing = if (target.isFile) target.length() else 0L
    if (expectedSize > 0 && existing > expectedSize) {
        target.delete()
        existing = 0
    }
    if (expectedSize > 0 && existing == expectedSize) {
        onProgress(existing, expectedSize)
        return target
    }
    val needed = if (expectedSize > existing) expectedSize - existing + 16 * 1024 * 1024 else 0L
    val usable = target.parentFile?.usableSpace ?: 0L
    if (needed > 0 && usable in 1 until needed) {
        error("No hay espacio suficiente para terminar la actualizacion. Libera espacio y vuelve a intentarlo.")
    }

    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ReloadedLauncher/1.0")
        setRequestProperty("Accept", "application/octet-stream")
        if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
    }
    try {
        connection.connect()
        val code = connection.responseCode
        if (code == 416 && existing > 0) {
            target.delete()
            error("El servidor ya no acepta el punto de reanudacion. Vuelve a intentarlo para empezar una descarga limpia.")
        }
        if (code !in 200..299) error("La descarga respondio $code.")

        val contentRange = connection.getHeaderField("Content-Range").orEmpty()
        val rangeStart = contentRange.substringAfter("bytes ", "").substringBefore('-').toLongOrNull()
        val append = code == HttpURLConnection.HTTP_PARTIAL && rangeStart == existing
        if (code == HttpURLConnection.HTTP_PARTIAL && !append) {
            target.delete()
            error("El servidor devolvio un rango distinto al solicitado. Vuelve a intentarlo.")
        }
        if (!append) existing = 0
        val rangeTotal = contentRange.substringAfterLast('/', "").toLongOrNull() ?: 0L
        val total = when {
            expectedSize > 0 -> expectedSize
            rangeTotal > 0 -> rangeTotal
            connection.contentLengthLong > 0 -> existing + connection.contentLengthLong
            else -> 0L
        }
        var written = existing
        onProgress(written, total)
        connection.inputStream.use { input ->
            java.io.FileOutputStream(target, append).buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) throw kotlinx.coroutines.CancellationException("Descarga cancelada.")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    onProgress(written, total)
                }
                output.flush()
            }
        }
        if (cancelled()) throw kotlinx.coroutines.CancellationException("Descarga cancelada.")
        if (expectedSize > 0 && written != expectedSize) {
            error("La conexion se corto: $written de $expectedSize bytes. Puedes continuar la descarga.")
        }
        if (total > 0 && written < total) {
            error("La conexion se corto antes de terminar. Puedes continuar la descarga.")
        }
        onProgress(written, total)
        return target
    } finally {
        connection.disconnect()
    }
}

/**
 * Hands a downloaded APK to the system installer, which is the only thing allowed to replace the
 * app. Android asks the user to allow installs from this app the first time, and if that has not
 * been granted there is nothing to open - so that case is reported instead of failing quietly.
 */
fun installUpdate(context: android.content.Context, apk: File): String? {
    // Without this Android will not open an installer for a file from this app, and it fails with
    // nothing to see. Sending the user straight to the switch is the only way to get anywhere.
    if (!context.packageManager.canRequestPackageInstalls()) {
        return runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Activa \"Permitir desde esta fuente\" y vuelve a darle a Instalar."
        }.getOrElse { "Android no deja instalar desde esta app: ${it.message}" }
    }
    return runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        null
    }.getOrElse { "No pude abrir el instalador: ${it::class.java.simpleName}: ${it.message}" }
}
