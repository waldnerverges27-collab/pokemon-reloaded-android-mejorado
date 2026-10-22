package com.ahjin.pr

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.StatFs
import androidx.core.content.FileProvider
import com.ahjin.pr.ui.formatBytes
import com.ahjin.pr.ui.readLog
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * One report with everything needed to understand a problem without asking the player questions:
 * the app and phone, the state of the game, the settings that change behaviour and the end of the log.
 *
 * It never includes the saves, and nothing in it identifies the player. It is built only when asked
 * for and leaves the phone only if the player shares it.
 */
object Diagnostics {

    fun report(context: Context, repo: GameRepository, prefs: Preferences): String = buildString {
        fun section(title: String) {
            appendLine()
            appendLine("== $title ==")
        }
        // A broken line must not take the whole report down with it.
        fun line(label: String, value: () -> Any?) {
            val text = runCatching { value()?.toString() }.getOrElse { "no disponible (${it.javaClass.simpleName})" }
            appendLine("$label: ${text ?: "-"}")
        }

        appendLine("Informe de diagnostico - Pokemon Reloaded Mejorado")
        appendLine("Generado: ${DateFormat.getDateTimeInstance().format(Date())}")
        appendLine("No incluye tus partidas ni datos personales.")

        section("Aplicacion")
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        line("Version") { "${info?.versionName} (${info?.longVersionCode})" }
        line("Paquete") { context.packageName }
        line("Instalada") { info?.firstInstallTime?.let { DateFormat.getDateTimeInstance().format(Date(it)) } }

        section("Dispositivo")
        line("Modelo") { "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})" }
        line("Android") { "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }
        line("Procesador") { Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= 31 && it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE }
        line("Arquitecturas") { Build.SUPPORTED_ABIS.joinToString() }
        line("Pantalla") {
            val metrics = context.resources.displayMetrics
            val hz = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0)?.refreshRate
            "${metrics.widthPixels}x${metrics.heightPixels} px, ${metrics.densityDpi} dpi" + (hz?.let { ", %.0f Hz".format(it) } ?: "")
        }
        line("Memoria") {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            "${formatBytes(memory.availMem)} libres de ${formatBytes(memory.totalMem)}" + if (memory.lowMemory) " (poca memoria)" else ""
        }
        line("Espacio libre") { StatFs(context.filesDir.path).availableBytes.let(::formatBytes) }
        line("Mandos") { connectedPads(context).ifEmpty { listOf("ninguno") }.joinToString() }

        section("Juego")
        val status = repo.status()
        if (status is GameRepository.Status.Ready) {
            line("Instalado") { "si" }
            line("Ejecutable") { status.exe }
            line("Version del juego") { status.version }
            line("Origen") { status.source }
            line("Tamano") { formatBytes(status.sizeBytes) }
        } else {
            line("Instalado") { "no" }
        }
        val partidas = File(context.filesDir, "game/data/partidas")
        line("Partidas") {
            partidas.listFiles { f -> SaveBackups.isSlotFile("partidas/" + f.name) }?.size ?: 0
        }
        line("Guardados rapidos") { (1..QUICK_SLOTS).count { QuickSaves.savedAt(context.filesDir, it) != null } }
        runCatching { repo.backups.list() }.onSuccess { copies ->
            line("Copias de seguridad") { "${copies.size} (${formatBytes(copies.sumOf { it.sizeBytes })}), ultima: ${copies.firstOrNull()?.let { DateFormat.getDateTimeInstance().format(Date(it.createdAt)) } ?: "ninguna"}" }
        }

        section("Ajustes")
        line("Aspecto") { listOf("sistema", "oscuro", "claro").getOrElse(prefs.themeMode) { prefs.themeMode.toString() } }
        line("Imagen") { prefs.videoMode }
        line("Filtro suave") { prefs.smoothFilter }
        line("Efecto de pantalla") { prefs.screenEffect }
        line("Zona segura") { prefs.safeArea }
        line("Ahorro de bateria") { prefs.batteryMode }
        line("Indicador de FPS") { prefs.showStats }
        line("Controles tactiles") { "estilo nativo=${prefs.nativeStyle}, iconos=${prefs.functionIcons}, escala=${prefs.touchScale}, opacidad=${prefs.touchOpacity}, joystick=${prefs.stickEnabled}, flotante=${prefs.floatingStick}, zona muerta=${prefs.stickDeadzone}" }
        line("Vibracion") { "tactil=${prefs.hapticLevel}, mando=${prefs.padRumble}" }
        line("Ocultar controles") { "${prefs.autoHideSeconds} s" }
        line("Guardado rapido") { "ranura=${prefs.quickSlot}" }
        line("Avance rapido") { "${speedLabel(prefs.speedPercent)}, silenciar=${prefs.ffwdMute}" }
        line("Copia automatica") { prefs.autoBackup }
        line("Botones propios") {
            File(context.filesDir, "layout.conf").takeIf { it.isFile }?.readLines()?.count { it.startsWith("custom") && it.split(',').getOrNull(3) == "1" } ?: 0
        }

        section("Registro (final)")
        appendLine(runCatching { readLog(repo).lines().takeLast(220).joinToString("\n").takeLast(40_000) }.getOrElse { "No pude leer el registro." })
    }

    /** Opens the share sheet with the report as a text file. */
    fun share(context: Context, text: String) {
        val file = File(File(context.cacheDir, "logs").apply { mkdirs() }, "diagnostico-reloaded.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Diagnostico de Pokemon Reloaded Mejorado")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Compartir informe").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
