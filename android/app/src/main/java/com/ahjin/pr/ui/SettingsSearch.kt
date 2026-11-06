package com.ahjin.pr.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class SettingAction {
    DELETE_GAME, CRIES, MY_SAVES, BACKUPS, CONTROLS, LAYOUT, EXTRAS,
    UPDATE, HELP, DOCTOR, REPORT, LOG, ABOUT,
}

/** One searchable row. A row either scrolls to a local group or invokes an existing action. */
internal data class SettingSearchEntry(
    val id: String,
    val section: String,
    val title: String,
    val subtitle: String,
    val keywords: String,
    val icon: ImageVector,
    val scrollTo: String? = null,
    val action: SettingAction? = null,
    val requiresGame: Boolean = false,
) {
    init {
        require(scrollTo == null || action == null) { "Un resultado no puede tener dos destinos" }
        require(scrollTo != null || action != null) { "Resultado sin destino: $id" }
    }
}

internal fun settingSearchEntries(appVersion: String): List<SettingSearchEntry> = listOf(
    SettingSearchEntry(
        "game-install", "Juego", "Instalar o reimportar el juego", "Descarga o importa el ZIP desde Inicio",
        "descargar importar zip instalacion reinstalar ejecutable datos", Icons.Filled.VideogameAsset, scrollTo = "Juego",
    ),
    SettingSearchEntry(
        "game-version", "Juego", "Versión y fecha del juego", "Consulta la versión instalada",
        "version instalado fecha ejecutable", Icons.Filled.Info, scrollTo = "Juego",
    ),
    SettingSearchEntry(
        "game-size", "Juego", "Tamaño en disco", "Espacio que ocupa la instalación",
        "tamano almacenamiento espacio archivos", Icons.Filled.Storage, scrollTo = "Juego",
    ),
    SettingSearchEntry(
        "game-delete", "Juego", "Borrar el juego", "Elimina la copia extraída, con copia previa de las partidas",
        "eliminar desinstalar borrar libera espacio", Icons.Filled.Delete, action = SettingAction.DELETE_GAME, requiresGame = true,
    ),
    SettingSearchEntry(
        "audio-cries", "Audio", "Gritos del anime", "Descarga, aplica o restaura los gritos",
        "sonido audio wav voces pokemon", Icons.Filled.RecordVoiceOver, action = SettingAction.CRIES, requiresGame = true,
    ),
    SettingSearchEntry(
        "saves-mine", "Partidas", "Mis partidas", "Ranuras, nombre, lugar y fecha de las partidas originales",
        "guardar cargar f5 f6 savc slots ranuras continuar", Icons.Filled.Save, action = SettingAction.MY_SAVES, requiresGame = true,
    ),
    SettingSearchEntry(
        "saves-backups", "Partidas", "Copias de seguridad", "Haz, restaura, exporta o comparte copias",
        "backup restaurar exportar partidas equipos personajes", Icons.Filled.Backup, action = SettingAction.BACKUPS,
    ),
    SettingSearchEntry(
        "pad-controls", "Mando", "Controles del mando", "Asigna y prueba los botones de un mando físico",
        "gamepad bluetooth cable botones teclas comandos", Icons.Filled.SportsEsports, action = SettingAction.CONTROLS,
    ),
    SettingSearchEntry(
        "buttons-icons", "Botones", "Iconos en los botones", "Muestra dibujos o letras en los controles",
        "imagenes dibujos letras z x mochila mapa", Icons.Filled.TouchApp, scrollTo = "Botones",
    ),
    SettingSearchEntry(
        "buttons-layout", "Botones", "Posición y tamaño de los botones", "Editor de diseño, preajustes y botones propios",
        "mover redimensionar editor disenos borrador deshacer rehacer", Icons.Filled.ZoomOutMap, action = SettingAction.LAYOUT,
    ),
    SettingSearchEntry(
        "movement-stick", "Control de movimiento", "Joystick", "Control redondo para el mundo y los combates",
        "movimiento joystick palanca direccion", Icons.Filled.Gamepad, scrollTo = "Control de movimiento",
    ),
    SettingSearchEntry(
        "movement-cross", "Control de movimiento", "Cruceta de 8 direcciones", "Diagonales en el mundo y cruceta de 4 en los menús",
        "dpad direcciones diagonales movimiento menus pokedex", Icons.Filled.OpenWith, scrollTo = "Control de movimiento",
    ),
    SettingSearchEntry(
        "touch-opacity", "Controles táctiles", "Opacidad de los botones", "Ajusta cuánto tapan la imagen",
        "transparencia translucidos controles", Icons.Filled.Opacity, scrollTo = "Controles tactiles",
    ),
    SettingSearchEntry(
        "touch-size", "Controles táctiles", "Tamaño de los botones táctiles", "Escala los controles para tus dedos",
        "escala tamano grandes pequenos", Icons.Filled.ZoomOutMap, scrollTo = "Controles tactiles",
    ),
    SettingSearchEntry(
        "touch-floating", "Controles táctiles", "Joystick flotante", "Coloca el control donde apoyas el pulgar",
        "flotante pulgar izquierda posicion", Icons.Filled.TouchApp, scrollTo = "Controles tactiles",
    ),
    SettingSearchEntry(
        "touch-deadzone", "Controles táctiles", "Zona muerta", "Sensibilidad del joystick y la cruceta",
        "sensibilidad deadzone movimiento", Icons.Filled.Gamepad, scrollTo = "Controles tactiles",
    ),
    SettingSearchEntry(
        "haptics", "Vibración al tocar", "Vibración de los botones", "Desactivada, ligera o fuerte",
        "haptica vibracion respuesta toque", Icons.Filled.Vibration, scrollTo = "Vibracion al tocar",
    ),
    SettingSearchEntry(
        "video-full", "Resolución", "Rellenar la pantalla", "La imagen ocupa toda la pantalla",
        "pantalla completa estirar full resolucion", Icons.Filled.Fullscreen, scrollTo = "Resolucion",
    ),
    SettingSearchEntry(
        "video-aspect", "Resolución", "Mantener la proporción", "Conserva el aspecto clásico con bandas",
        "aspect ratio bandas resolucion", Icons.Filled.AspectRatio, scrollTo = "Resolucion",
    ),
    SettingSearchEntry(
        "video-integer", "Resolución", "Píxeles perfectos", "Múltiplos enteros y píxeles del mismo tamaño",
        "integer nitidez escala entera pixeles", Icons.Filled.ZoomOutMap, scrollTo = "Resolucion",
    ),
    SettingSearchEntry(
        "extras", "Extras", "Velocidad, imagen y más", "Avance rápido, filtros, FPS, batería y mando",
        "avance rapido camara lenta velocidad suavizado crt lcd fps memoria bateria rendimiento zona segura ocultar controles rumble",
        Icons.Filled.Speed, action = SettingAction.EXTRAS,
    ),
    SettingSearchEntry(
        "theme-dark", "Apariencia", "Modo oscuro", "Fondo oscuro de la app",
        "tema negro apariencia", Icons.Filled.DarkMode, scrollTo = "Apariencia",
    ),
    SettingSearchEntry(
        "theme-light", "Apariencia", "Modo claro", "Fondo marfil de la app",
        "tema blanco apariencia", Icons.Filled.LightMode, scrollTo = "Apariencia",
    ),
    SettingSearchEntry(
        "theme-system", "Apariencia", "Tema según el sistema", "Sigue el modo del teléfono",
        "automatico sistema apariencia", Icons.Filled.BrightnessAuto, scrollTo = "Apariencia",
    ),
    SettingSearchEntry(
        "color-amoled", "Color y negro", "Negro puro (AMOLED)", "Fondo completamente negro en modo oscuro",
        "oled ahorro bateria pantalla", Icons.Filled.DarkMode, scrollTo = "Color y negro",
    ),
    SettingSearchEntry(
        "color-accent", "Color y negro", "Color de acento", "Oro, Zafiro, Esmeralda, Rubí o Amatista",
        "colores tema dorado azul verde rojo morado", Icons.Filled.Circle, scrollTo = "Color y negro",
    ),
    SettingSearchEntry(
        "screen-awake", "Pantalla", "Mantener la pantalla encendida", "Evita el bloqueo mientras juegas",
        "bloqueo suspender pantalla siempre encendida", Icons.Filled.LightMode, scrollTo = "Pantalla",
    ),
    SettingSearchEntry(
        "app-update", "Actualizaciones", "Versión de la app: $appVersion", "Busca una actualización de la aplicación",
        "actualizar actualizacion version apk github", Icons.Filled.SystemUpdate, action = SettingAction.UPDATE,
    ),
    SettingSearchEntry(
        "help-guide", "Ayuda", "Guía de uso", "Primeros pasos, controles, guardado y solución de problemas",
        "ayuda importar guardar controles buscar", Icons.AutoMirrored.Filled.HelpOutline, action = SettingAction.HELP,
    ),
    SettingSearchEntry(
        "help-doctor", "Ayuda", "Asistente de instalación", "Comprueba el juego, archivos, espacio y escritura",
        "diagnostico instalacion arquitectura sonido midi reparar", Icons.Filled.Build, action = SettingAction.DOCTOR,
    ),
    SettingSearchEntry(
        "help-report", "Ayuda", "Informe de diagnóstico", "Reúne y comparte información para reportar un fallo",
        "error problema logs compartir informe", Icons.Filled.BugReport, action = SettingAction.REPORT,
    ),
    SettingSearchEntry(
        "help-logs", "Ayuda", "Logs", "Abre el registro de la app y del motor",
        "registro errores fallos crash", Icons.Filled.Article, action = SettingAction.LOG,
    ),
    SettingSearchEntry(
        "help-about", "Ayuda", "Acerca de", "Versión, créditos, licencias y repositorio",
        "creditos licencia github codigo version", Icons.Filled.Info, action = SettingAction.ABOUT,
    ),
)
