package com.ahjin.pr

import java.io.File

/**
 * A small, deterministic installation check. The Android screen supplies device information; this
 * object only inspects files and turns the result into explanations and safe next steps.
 *
 * It never deletes or repairs the game by itself. The only write is a unique temporary probe used
 * to check that the app's private storage is writable, and that probe is removed immediately.
 */
object InstallationDoctor {
    enum class Level { OK, WARNING, ERROR }
    enum class CheckId { COMPATIBILITY, GAME, ESSENTIALS, AUDIO, SAVES, STORAGE, WRITE, LEFTOVERS }
    enum class Remedy { IMPORT, PREPARE }

    data class Installed(val exe: String, val version: String?)

    data class Check(
        val id: CheckId,
        val level: Level,
        val title: String,
        val detail: String,
        val remedy: Remedy? = null,
    )

    data class Report(val checks: List<Check>) {
        val errors: Int get() = checks.count { it.level == Level.ERROR }
        val warnings: Int get() = checks.count { it.level == Level.WARNING }
        val isClear: Boolean get() = errors == 0 && warnings == 0
        val needsImport: Boolean get() = checks.any { it.level == Level.ERROR && it.remedy == Remedy.IMPORT }
        val canRepair: Boolean get() = checks.any { it.level != Level.OK && it.remedy == Remedy.PREPARE }
    }

    const val LOW_STORAGE_BYTES: Long = 256L * 1024 * 1024
    private val SAVE = Regex("^reloaded[1-9][0-9]*\\.savc$", RegexOption.IGNORE_CASE)

    fun inspect(
        filesDir: File,
        installed: Installed?,
        supportedAbis: List<String>,
        availableBytes: Long,
    ): Report {
        val checks = mutableListOf<Check>()
        val compatible = supportedAbis.any { it.equals("arm64-v8a", true) || it.equals("armeabi-v7a", true) }
        checks += Check(
            CheckId.COMPATIBILITY,
            if (compatible) Level.OK else Level.ERROR,
            "Arquitectura del teléfono",
            if (compatible) {
                "Hay una variante nativa para: ${supportedAbis.joinToString()}."
            } else {
                "Esta app solo tiene variantes ARM64 y ARMv7; no hay una biblioteca para ${supportedAbis.joinToString().ifEmpty { "esta arquitectura" }}."
            },
        )

        val gameDir = File(filesDir, "game")
        val oldGame = File(filesDir, "game.old")
        if (installed == null) {
            val detail = when {
                !gameDir.exists() && oldGame.isDirectory ->
                    "La última instalación quedó interrumpida. Vuelve a importar el ZIP: el instalador intentará recuperar la copia anterior antes de continuar."
                gameDir.exists() ->
                    "Hay una carpeta de juego incompleta o ilegible. Reimporta el ZIP completo; no hace falta borrar tus partidas."
                else ->
                    "Descarga el ZIP oficial o elige Importar si ya lo tienes. La app no incluye los archivos del juego."
            }
            checks += Check(CheckId.GAME, Level.ERROR, "Juego no disponible", detail, Remedy.IMPORT)
        } else {
            checks += Check(
                CheckId.GAME,
                Level.OK,
                "Juego instalado",
                "${installed.exe}${installed.version?.let { " · versión $it" } ?: ""}",
            )

            val exe = File(gameDir, installed.exe)
            val languages = File(gameDir, "data/text/lan.txt")
            val missing = buildList {
                if (!exe.isFile || exe.length() == 0L || !exe.canRead()) add("el ejecutable")
                if (!languages.isFile || languages.length() == 0L || !languages.canRead()) add("data/text/lan.txt")
            }
            checks += if (missing.isEmpty()) {
                Check(CheckId.ESSENTIALS, Level.OK, "Archivos básicos", "El ejecutable y los archivos de idioma se pueden leer.")
            } else {
                Check(
                    CheckId.ESSENTIALS,
                    Level.ERROR,
                    "Faltan archivos del juego",
                    "No se puede leer ${missing.joinToString()}. Reimporta el ZIP completo para reconstruir la instalación y conservar tus partidas.",
                    Remedy.IMPORT,
                )
            }

            val soundfont = File(gameDir, "soundfont.sf2")
            val audioReady = soundfont.isFile && soundfont.length() > 0L && soundfont.canRead()
            checks += Check(
                CheckId.AUDIO,
                if (audioReady) Level.OK else Level.WARNING,
                "Audio MIDI",
                if (audioReady) {
                    "El banco de sonido incluido está preparado."
                } else {
                    "Falta soundfont.sf2 o está vacío. La reparación vuelve a copiarlo desde la app, sin tocar el juego ni las partidas."
                },
                if (audioReady) null else Remedy.PREPARE,
            )

            val saves = File(gameDir, "data/partidas").listFiles().orEmpty().count { it.isFile && SAVE.matches(it.name) }
            checks += Check(
                CheckId.SAVES,
                Level.OK,
                "Partidas originales",
                when (saves) {
                    0 -> "Todavía no hay ninguna .savc; puedes empezar una partida nueva."
                    1 -> "Se detectó 1 partida .savc. Esta comprobación no la abre ni la modifica."
                    else -> "Se detectaron $saves partidas .savc. Esta comprobación no las abre ni las modifica."
                },
            )
        }

        checks += Check(
            CheckId.STORAGE,
            if (availableBytes < LOW_STORAGE_BYTES) Level.WARNING else Level.OK,
            "Espacio disponible",
            if (availableBytes < LOW_STORAGE_BYTES) {
                "Quedan ${size(availableBytes)}. Libera espacio antes de importar, reinstalar o actualizar; esas operaciones necesitan archivos temporales."
            } else {
                "Quedan ${size(availableBytes)} en el almacenamiento de la app."
            },
        )

        checks += writeCheck(filesDir)

        val temporary = listOf("game.old", "game.staging", "import.zip", "download.zip").filter { File(filesDir, it).exists() }
        if (temporary.isNotEmpty()) {
            checks += Check(
                CheckId.LEFTOVERS,
                Level.WARNING,
                "Restos de una instalación anterior",
                "Se encontraron ${temporary.joinToString()}. No los borres a mano: vuelve a importar para que el instalador los gestione y pueda recuperar una instalación interrumpida.",
            )
        }
        return Report(checks)
    }

    private fun writeCheck(filesDir: File): Check {
        var probe: File? = null
        return try {
            if (!filesDir.isDirectory) error("La carpeta privada no existe.")
            val created = File.createTempFile(".doctor-", ".tmp", filesDir).apply { writeText("ok") }
            probe = created
            check(created.readText() == "ok") { "La lectura de prueba no coincide." }
            Check(CheckId.WRITE, Level.OK, "Escritura de la app", "La carpeta privada permite crear, leer y borrar archivos temporales.")
        } catch (error: Throwable) {
            Check(
                CheckId.WRITE,
                Level.ERROR,
                "La app no puede escribir",
                "Android no permitió completar una prueba temporal (${error.javaClass.simpleName}). Exporta tus copias antes de reinstalar o cambiar la app.",
            )
        } finally {
            probe?.delete()
        }
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    }
}
