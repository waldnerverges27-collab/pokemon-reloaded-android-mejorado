package com.klisee.reloaded

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
            val asset = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            }
            Release(
                version = tag,
                apkUrl = asset?.optString("browser_download_url")?.takeIf { it.isNotEmpty() },
                pageUrl = json.optString("html_url").ifEmpty { RELEASES_PAGE },
                apkSize = asset?.optLong("size", 0L) ?: 0L,
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
    suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(url)
            connection.connect()
            val code = connection.responseCode
            val total = connection.contentLengthLong
            trace("update: descarga $code, $total bytes desde $url")
            if (code !in 200..299) error("La descarga respondio $code.")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    trace("update: descargados $written bytes")
                }
            }
            target
        }.onFailure { trace("update: FALLO la descarga: ${it::class.java.simpleName}: ${it.message}") }
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
            "Activa \"Permitir desde esta fuente\" y vuelve a darle a Descargar."
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
