package com.ahjin.pr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ahjin.pr.Preferences
import com.ahjin.pr.QUICK_SLOTS
import com.ahjin.pr.QuickSaves
import com.ahjin.pr.SPEED_CHOICES
import com.ahjin.pr.speedLabel
import java.io.File

/**
 * What goes beyond the game itself: quick save, fast-forward, the picture, the battery and the pad.
 * Every change is written to `extras.conf` at once; the engine reads it when the game starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtrasScreen(prefs: Preferences, filesDir: File, onBack: () -> Unit) {
    var quickSlot by remember { mutableIntStateOf(prefs.quickSlot) }
    var speedPercent by remember { mutableIntStateOf(prefs.speedPercent) }
    var ffwdMute by remember { mutableStateOf(prefs.ffwdMute) }
    var autoHide by remember { mutableIntStateOf(prefs.autoHideSeconds) }
    var smooth by remember { mutableStateOf(prefs.smoothFilter) }
    var effect by remember { mutableIntStateOf(prefs.screenEffect) }
    var safeArea by remember { mutableStateOf(prefs.safeArea) }
    var stats by remember { mutableStateOf(prefs.showStats) }
    var battery by remember { mutableIntStateOf(prefs.batteryMode) }
    var rumble by remember { mutableIntStateOf(prefs.padRumble) }
    // Bumped after a delete so the slot descriptions are read again.
    var slotsRevision by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<Int?>(null) }

    fun persist() = prefs.writeExtras(filesDir)

    deleting?.let { slot ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Borrar la ranura $slot?") },
            text = { Text("Se pierde el guardado rapido de esa ranura. Las partidas del juego no se tocan.") },
            confirmButton = {
                TextButton(onClick = {
                    QuickSaves.delete(filesDir, slot)
                    slotsRevision++
                    deleting = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extras") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup("Guardado rapido (experimental)") {
                InfoRow(
                    icon = Icons.Filled.Warning,
                    title = "Como funciona",
                    value = "En el juego, el boton de guardar toma una foto de toda la partida y el de cargar la devuelve. " +
                        "Es aparte de guardar dentro del juego, que sigue igual. Cargar pide pulsar dos veces. " +
                        "La musica en bucle vuelve a empezar desde el principio.",
                    items = QUICK_SLOTS + 1,
                    index = 0,
                )
                for (slot in 1..QUICK_SLOTS) {
                    val description = remember(slotsRevision, slot) {
                        val size = QuickSaves.size(filesDir, slot)
                        val base = QuickSaves.describe(filesDir, slot)
                        if (size > 0) "$base (${formatBytes(size)})" else base
                    }
                    ChoiceRow(
                        icon = Icons.Filled.Save,
                        title = "Ranura $slot",
                        subtitle = description,
                        selected = quickSlot == slot,
                        items = QUICK_SLOTS + 1,
                        index = slot,
                        onSelect = {
                            quickSlot = slot
                            prefs.quickSlot = slot
                            persist()
                        },
                    )
                }
            }
            val hasSave = remember(slotsRevision, quickSlot) { QuickSaves.savedAt(filesDir, quickSlot) != null }
            SettingsGroup("Ranura $quickSlot") {
                NavRow(
                    icon = Icons.Filled.Delete,
                    title = "Borrar el guardado de la ranura $quickSlot",
                    subtitle = if (hasSave) "Libera el espacio que ocupa" else "Esta vacia",
                    items = 1,
                    index = 0,
                    enabled = hasSave,
                    onClick = { deleting = quickSlot },
                )
            }

            SettingsGroup("Velocidad del juego") {
                SPEED_CHOICES.forEachIndexed { position, speed ->
                    ChoiceRow(
                        icon = Icons.Filled.FastForward,
                        title = if (speed < 100) "Camara lenta ${speedLabel(speed)}" else "Avance rapido ${speedLabel(speed)}",
                        subtitle = when (speed) {
                            50 -> "La mitad: util para esquivar o leer con calma"
                            75 -> "Un poco mas despacio"
                            150 -> "Un poco mas rapido"
                            200 -> "El doble: buena para caminar y para los textos"
                            else -> "Si el movil no llega, va a la maxima que pueda"
                        },
                        selected = speedPercent == speed,
                        items = SPEED_CHOICES.size + 1,
                        index = position,
                        onSelect = {
                            speedPercent = speed
                            prefs.speedPercent = speed
                            persist()
                        },
                    )
                }
                SwitchRow(
                    icon = Icons.Filled.VolumeOff,
                    title = "Silenciar si no va a velocidad normal",
                    subtitle = "A otra velocidad el sonido se descompasa: asi no se oye ruido",
                    checked = ffwdMute,
                    items = SPEED_CHOICES.size + 1,
                    index = SPEED_CHOICES.size,
                    onCheckedChange = {
                        ffwdMute = it
                        prefs.ffwdMute = it
                        persist()
                    },
                )
            }

            SettingsGroup("Imagen") {
                SwitchRow(
                    icon = Icons.Filled.Wallpaper,
                    title = "Suavizar la imagen",
                    subtitle = "Desactivado: pixeles nitidos, como el original. Activado: bordes suaves al escalar",
                    checked = smooth,
                    items = 5,
                    index = 0,
                    onCheckedChange = {
                        smooth = it
                        prefs.smoothFilter = it
                        persist()
                    },
                )
                listOf(
                    Triple(0, "Sin efecto", "La imagen tal cual"),
                    Triple(1, "Rejilla de pantalla LCD", "Lineas finas entre los pixeles, como una portatil. Solo si cada pixel del juego ocupa 2 o mas de pantalla"),
                    Triple(2, "Lineas de monitor CRT", "Lineas horizontales oscuras. Mismas condiciones"),
                ).forEachIndexed { position, (value, title, subtitle) ->
                    ChoiceRow(
                        icon = Icons.Filled.GridOn,
                        title = title,
                        subtitle = subtitle,
                        selected = effect == value,
                        items = 5,
                        index = position + 1,
                        onSelect = {
                            effect = value
                            prefs.screenEffect = value
                            persist()
                        },
                    )
                }
                SwitchRow(
                    icon = Icons.Filled.Monitor,
                    title = "Respetar la camara",
                    subtitle = "La imagen no pasa por debajo del agujero o la muesca de la camara",
                    checked = safeArea,
                    items = 5,
                    index = 4,
                    onCheckedChange = {
                        safeArea = it
                        prefs.safeArea = it
                        persist()
                    },
                )
            }

            SettingsGroup("Botones tactiles") {
                SliderRow(
                    icon = Icons.Filled.VisibilityOff,
                    title = "Ocultar solos",
                    subtitle = if (autoHide == 0) "Nunca: los botones se quedan siempre" else "Se difuminan tras $autoHide s sin tocar la pantalla; siguen funcionando",
                    value = autoHide.toFloat(),
                    valueRange = 0f..30f,
                    items = 1,
                    index = 0,
                    onValueChange = {
                        autoHide = it.toInt()
                        prefs.autoHideSeconds = autoHide
                        persist()
                    },
                )
            }

            SettingsGroup("Mando") {
                listOf(
                    Triple(0, "Sin vibracion", "El mando no vibra"),
                    Triple(1, "Vibracion ligera", "Un toque breve al pulsar un boton"),
                    Triple(2, "Vibracion fuerte", "Un toque largo al pulsar un boton"),
                ).forEachIndexed { position, (value, title, subtitle) ->
                    ChoiceRow(
                        icon = Icons.Filled.Vibration,
                        title = title,
                        subtitle = subtitle,
                        selected = rumble == value,
                        items = 3,
                        index = position,
                        onSelect = {
                            rumble = value
                            prefs.padRumble = value
                            persist()
                        },
                    )
                }
            }

            SettingsGroup("Rendimiento y bateria") {
                SwitchRow(
                    icon = Icons.Filled.Speed,
                    title = "Mostrar FPS, tiempo y memoria",
                    subtitle = "Un pequeno indicador arriba, en el centro, mientras juegas",
                    checked = stats,
                    items = 4,
                    index = 0,
                    onCheckedChange = {
                        stats = it
                        prefs.showStats = it
                        persist()
                    },
                )
                listOf(
                    Triple(0, "Ahorro de bateria desactivado", "El juego pide al movil la CPU que haga falta para ir fluido"),
                    Triple(1, "Ahorro de bateria siempre", "Pide solo lo justo para cada fotograma: gasta menos y se calienta menos, con menos margen en escenas pesadas"),
                    Triple(2, "Ahorro automatico", "Se activa solo con el ahorro de bateria del sistema o si el movil esta caliente"),
                ).forEachIndexed { position, (value, title, subtitle) ->
                    ChoiceRow(
                        icon = Icons.Filled.BatteryChargingFull,
                        title = title,
                        subtitle = subtitle,
                        selected = battery == value,
                        items = 4,
                        index = position + 1,
                        onSelect = {
                            battery = value
                            prefs.batteryMode = value
                            persist()
                        },
                    )
                }
            }

            Text(
                "Los cambios se aplican la proxima vez que abras el juego.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
