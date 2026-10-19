package com.ahjin.pr.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahjin.pr.GameRepository
import com.ahjin.pr.Diagnostics
import com.ahjin.pr.Preferences
import com.ahjin.pr.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: GameRepository,
    prefs: Preferences,
    revision: Int = 0,
    onLog: () -> Unit,
    onAbout: () -> Unit,
    onControls: () -> Unit,
    onLayout: () -> Unit,
    onExtras: () -> Unit,
    onMySaves: () -> Unit,
    onBackups: () -> Unit,
    onHelp: () -> Unit,
    onUpdateFound: (Update.Release) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var status by remember(revision) { mutableStateOf(repo.status()) }
    var keepScreenOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var videoMode by remember { mutableStateOf(prefs.videoMode) }
    var touchOpacity by remember { mutableStateOf(prefs.touchOpacity) }
    var nativeStyle by remember { mutableStateOf(prefs.nativeStyle) }
    var functionIcons by remember { mutableStateOf(prefs.functionIcons) }
    var touchScale by remember { mutableStateOf(prefs.touchScale) }
    var hapticLevel by remember { mutableStateOf(prefs.hapticLevel) }
    var floatingStick by remember { mutableStateOf(prefs.floatingStick) }
    var stickDeadzone by remember { mutableStateOf(prefs.stickDeadzone) }
    var checking by remember { mutableStateOf(false) }
    var preparingReport by remember { mutableStateOf(false) }
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    var confirmingDelete by remember { mutableStateOf(false) }
    val installed = status as? GameRepository.Status.Ready

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Borrar el juego?") },
            text = { Text("Se borra la copia extraida (${formatBytes(installed?.sizeBytes ?: 0)}). Podras volver a descargarlo o importarlo. Antes de borrar se guarda una copia de tus partidas en Copias de seguridad, para poder recuperarlas.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        repo.deleteGame()
                        status = repo.status()
                    }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ajustes") }) },
        bottomBar = bottomBar,
        // Lifted clear of the floating bar, which otherwise sits on top of the message.
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = BottomNavSpace)) },
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup("Juego") {
                if (installed == null) {
                    InfoRow(
                        icon = Icons.Filled.Info,
                        title = "Sin juego instalado",
                        value = "Descargalo o importalo desde la pantalla principal",
                        items = 1,
                        index = 0,
                    )
                } else {
                    InfoRow(Icons.Filled.VideogameAsset, "Version", installed.version ?: "sin datos", items = 4, index = 0)
                    InfoRow(Icons.Filled.Storage, "Tamano en disco", formatBytes(installed.sizeBytes), items = 4, index = 1)
                    InfoRow(Icons.Filled.CalendarMonth, "Instalado el", formatDate(installed.installedAt), items = 4, index = 2)
                    NavRow(
                        icon = Icons.Filled.Delete,
                        title = "Borrar el juego",
                        subtitle = "Libera ${formatBytes(installed.sizeBytes)} del almacenamiento",
                        items = 4,
                        index = 3,
                        onClick = { confirmingDelete = true },
                    )
                }
            }

            SettingsGroup("Partidas") {
                NavRow(
                    icon = Icons.Filled.Save,
                    title = "Mis partidas",
                    subtitle = if (installed == null) {
                        "El juego no esta descargado"
                    } else {
                        "Ve cuales partidas tienes guardadas en el juego"
                    },
                    items = 2,
                    index = 0,
                    enabled = installed != null,
                    onClick = onMySaves,
                )
                NavRow(
                    icon = Icons.Filled.Backup,
                    title = "Copias de seguridad",
                    subtitle = "Copias automaticas de tus partidas: hacer, restaurar o compartir",
                    items = 2,
                    index = 1,
                    onClick = onBackups,
                )
            }

            SettingsGroup("Mando") {
                NavRow(
                    icon = Icons.Filled.SportsEsports,
                    title = "Controles",
                    subtitle = "Elige que boton del mando hace cada tecla, y pruebalo",
                    items = 1,
                    index = 0,
                    onClick = onControls,
                )
            }

            SettingsGroup("Aspecto de los controles") {
                ChoiceRow(
                    icon = Icons.Filled.TouchApp,
                    title = "Nativo",
                    subtitle = "Botones translucidos y suaves, como un juego de Android",
                    selected = nativeStyle,
                    items = 4,
                    index = 0,
                    onSelect = {
                        nativeStyle = true
                        prefs.nativeStyle = true
                    },
                )
                ChoiceRow(
                    icon = Icons.Filled.VideogameAsset,
                    title = "Pixel art clasico",
                    subtitle = "Los botones dibujados a mano que trae el juego",
                    selected = !nativeStyle,
                    items = 4,
                    index = 1,
                    onSelect = {
                        nativeStyle = false
                        prefs.nativeStyle = false
                    },
                )
                SwitchRow(
                    icon = Icons.Filled.TouchApp,
                    title = "Iconos en los botones",
                    subtitle = "Cada boton muestra lo que hace (mochila, mapa, bici...) en vez de su letra. En combate, Z X C V siguen con letra",
                    checked = functionIcons,
                    items = 4,
                    index = 2,
                    onCheckedChange = {
                        functionIcons = it
                        prefs.functionIcons = it
                    },
                )
                NavRow(
                    icon = Icons.Filled.ZoomOutMap,
                    title = "Posicion y tamano de los botones",
                    subtitle = "Arrastra cada boton donde te venga mejor, o escondelo",
                    items = 4,
                    index = 3,
                    onClick = onLayout,
                )
            }

            SettingsGroup("Controles tactiles") {
                SliderRow(
                    icon = Icons.Filled.Opacity,
                    title = "Opacidad de los botones",
                    subtitle = "Bajala para que los botones con sprite tapen menos el juego",
                    value = touchOpacity,
                    items = 4,
                    index = 0,
                    onValueChange = {
                        touchOpacity = it
                        prefs.touchOpacity = it
                    },
                )
                SliderRow(
                    icon = Icons.Filled.ZoomOutMap,
                    title = "Tamano de los botones",
                    subtitle = "De ${"%.0f".format(touchScale * 100)}%: ajustalo a tus dedos",
                    value = touchScale,
                    valueRange = 0.7f..1.4f,
                    items = 4,
                    index = 1,
                    onValueChange = {
                        touchScale = it
                        prefs.touchScale = it
                    },
                )
                SwitchRow(
                    icon = Icons.Filled.TouchApp,
                    title = "Joystick flotante",
                    subtitle = "El joystick aparece donde pongas el pulgar, en la parte izquierda de la pantalla",
                    checked = floatingStick,
                    items = 4,
                    index = 2,
                    onCheckedChange = {
                        floatingStick = it
                        prefs.floatingStick = it
                    },
                )
                SliderRow(
                    icon = Icons.Filled.Gamepad,
                    title = "Zona muerta del joystick",
                    subtitle = "Cuanto hay que mover el joystick para que el personaje camine (${"%.0f".format(stickDeadzone * 100)}%)",
                    value = stickDeadzone,
                    valueRange = 0.1f..0.6f,
                    items = 4,
                    index = 3,
                    onValueChange = {
                        stickDeadzone = it
                        prefs.stickDeadzone = it
                    },
                )
            }

            SettingsGroup("Vibracion al tocar") {
                listOf(
                    Triple(0, "Desactivada", "Sin vibracion"),
                    Triple(1, "Ligera", "Un toque suave en cada boton"),
                    Triple(2, "Fuerte", "Un clic marcado en cada boton"),
                ).forEachIndexed { position, (level, title, subtitle) ->
                    ChoiceRow(
                        icon = Icons.Filled.Vibration,
                        title = title,
                        subtitle = subtitle,
                        selected = hapticLevel == level,
                        items = 3,
                        index = position,
                        onSelect = {
                            hapticLevel = level
                            prefs.hapticLevel = level
                        },
                    )
                }
            }

            SettingsGroup("Resolucion") {
                listOf(
                    Triple("full", "Rellenar la pantalla", "La imagen ocupa todo el movil, aunque se deforme un poco") to Icons.Filled.Fullscreen,
                    Triple("aspect", "Mantener la proporcion", "El aspecto clasico del PC, con bandas oscuras a los lados") to Icons.Filled.AspectRatio,
                    Triple("integer", "Pixeles perfectos", "Solo multiplos enteros del tamano del juego: todos los pixeles iguales, con bandas") to Icons.Filled.ZoomOutMap,
                ).forEachIndexed { position, (option, icon) ->
                    val (mode, title, subtitle) = option
                    ChoiceRow(
                        icon = icon,
                        title = title,
                        subtitle = subtitle,
                        selected = videoMode == mode,
                        items = 3,
                        index = position,
                        onSelect = {
                            videoMode = mode
                            prefs.videoMode = mode
                            prefs.writeVideo(context.filesDir)
                        },
                    )
                }
            }

            SettingsGroup("Extras") {
                NavRow(
                    icon = Icons.Filled.Speed,
                    title = "Guardado rapido, avance rapido y mas",
                    subtitle = "Guardar y cargar al instante, acelerar el juego, filtros, bateria y mando",
                    items = 1,
                    index = 0,
                    onClick = onExtras,
                )
            }

            SettingsGroup("Apariencia") {
                ChoiceRow(
                    icon = Icons.Filled.DarkMode,
                    title = "Oscuro",
                    subtitle = "Negro profundo con acentos dorados (recomendado)",
                    selected = prefs.themeMode == ThemeMode.DARK,
                    items = 3,
                    index = 0,
                    onSelect = { prefs.themeMode = ThemeMode.DARK },
                )
                ChoiceRow(
                    icon = Icons.Filled.LightMode,
                    title = "Claro",
                    subtitle = "Marfil con acentos de bronce",
                    selected = prefs.themeMode == ThemeMode.LIGHT,
                    items = 3,
                    index = 1,
                    onSelect = { prefs.themeMode = ThemeMode.LIGHT },
                )
                ChoiceRow(
                    icon = Icons.Filled.BrightnessAuto,
                    title = "Segun el sistema",
                    subtitle = "Cambia solo con el modo del movil",
                    selected = prefs.themeMode == ThemeMode.SYSTEM,
                    items = 3,
                    index = 2,
                    onSelect = { prefs.themeMode = ThemeMode.SYSTEM },
                )
            }

            val darkNow = isDarkTheme(prefs.themeMode)
            SettingsGroup("Color y negro") {
                SwitchRow(
                    icon = Icons.Filled.DarkMode,
                    title = "Negro puro (AMOLED)",
                    subtitle = if (darkNow) "Fondo totalmente negro: ahorra bateria en pantallas OLED" else "Solo se nota en el modo oscuro",
                    checked = prefs.amoled,
                    items = Accent.ALL.size + 1,
                    index = 0,
                    onCheckedChange = { prefs.amoled = it },
                )
                Accent.ALL.forEachIndexed { i, accent ->
                    ChoiceRow(
                        icon = Icons.Filled.Circle,
                        iconTint = accentSwatch(accent, darkNow),
                        title = Accent.label(accent),
                        subtitle = if (accent == Accent.GOLD) "El color original de la app" else "Color de acento",
                        selected = prefs.accent == accent,
                        items = Accent.ALL.size + 1,
                        index = i + 1,
                        onSelect = { prefs.accent = accent },
                    )
                }
            }

            SettingsGroup("Pantalla") {
                SwitchRow(
                    icon = Icons.Filled.LightMode,
                    title = "Mantener la pantalla encendida",
                    subtitle = "Evita que el movil se bloquee mientras juegas",
                    checked = keepScreenOn,
                    items = 1,
                    index = 0,
                    onCheckedChange = {
                        keepScreenOn = it
                        prefs.keepScreenOn = it
                    },
                )
            }

            SettingsGroup("Actualizaciones") {
                NavRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "Version de la app: $appVersion",
                    subtitle = "Checa si hay alguna actualizacion disponible de la aplicacion",
                    items = 1,
                    index = 0,
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val found = Update.check(appVersion).getOrNull()
                            checking = false
                            if (found != null) {
                                onUpdateFound(found)
                            } else {
                                snackbar.showSnackbar("Ya tienes la ultima version ($appVersion).")
                            }
                        }
                    },
                )
            }

            SettingsGroup("Ayuda") {
                NavRow(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    title = "Guia de uso",
                    subtitle = "Como importar el juego, usar los controles, guardar y resolver problemas",
                    items = 4,
                    index = 0,
                    onClick = onHelp,
                )
                NavRow(
                    icon = Icons.Filled.BugReport,
                    title = if (preparingReport) "Preparando informe..." else "Informe de diagnostico",
                    subtitle = "Un toque: reune version, dispositivo, ajustes y logs para compartirlo",
                    items = 4,
                    index = 1,
                    enabled = !preparingReport,
                    onClick = {
                        preparingReport = true
                        scope.launch {
                            val text = runCatching { withContext(Dispatchers.IO) { Diagnostics.report(context, repo, prefs) } }
                            preparingReport = false
                            text.onSuccess { runCatching { Diagnostics.share(context, it) } }
                        }
                    },
                )
                NavRow(
                    icon = Icons.Filled.Article,
                    title = "Logs",
                    subtitle = "En caso de un error, comparte los logs, me ayudarán a saber qué falló",
                    items = 4,
                    index = 2,
                    onClick = onLog,
                )
                NavRow(
                    icon = Icons.Filled.Info,
                    title = "Acerca de",
                    subtitle = "Version, creditos y aviso",
                    items = 4,
                    index = 3,
                    onClick = onAbout,
                )
            }

            Spacer(Modifier.height(BottomNavSpace))
        }
    }
}
