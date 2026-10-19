package com.klisee.reloaded.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klisee.reloaded.ControlSpec
import com.klisee.reloaded.JOYSTICK
import com.klisee.reloaded.Layout
import com.klisee.reloaded.Placement
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private class Screen(val title: String, val mask: Int)

private val screens = listOf(
    Screen("Mundo", Layout.WORLD),
    Screen("Combate", Layout.BATTLE),
    Screen("Safari", Layout.SAFARI),
    Screen("Elegir personaje", Layout.CHARACTER),
    Screen("Preguntas", Layout.CHOICE),
)

/**
 * Lets the player drag the on-screen buttons where their thumbs want them, resize them and hide the
 * ones they never use. The preview has the proportions of this phone's screen, since the engine
 * measures positions as fractions of it. Changes are saved as they are made, to `layout.conf`, which
 * the game reads when it starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsLayoutScreen(filesDir: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val defaults = remember { Layout.defaults(context) }
    val placements: SnapshotStateMap<String, Placement> = remember {
        mutableStateMapOf<String, Placement>().apply { putAll(Layout.read(filesDir)) }
    }
    var screen by remember { mutableStateOf(screens[0]) }
    var selected by remember { mutableStateOf<String?>(null) }

    fun save() = Layout.write(filesDir, placements.toMap())
    fun specOf(name: String): ControlSpec? =
        if (name == JOYSTICK) defaults.joystick else defaults.controls.firstOrNull { it.name == name }
    fun placementOf(spec: ControlSpec) = placements[spec.name] ?: Placement(spec.cx, spec.cy)

    val shown = defaults.controls.filter { it.contexts and screen.mask != 0 } +
        (if (screen.mask and (Layout.WORLD or Layout.BATTLE or Layout.CHOICE) != 0) listOf(defaults.joystick) else emptyList())

    val config = LocalConfiguration.current
    val ratio = max(config.screenWidthDp, config.screenHeightDp).toFloat() / min(config.screenWidthDp, config.screenHeightDp)
    val measurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Posicion de los botones") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    TextButton(onClick = {
                        placements.clear()
                        selected = null
                        save()
                    }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null)
                        Text("  Restaurar todo")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Two rows of chips would be tidier on a narrow phone, but five short labels fit one.
                screens.forEach { s ->
                    FilterChip(
                        selected = s === screen,
                        onClick = {
                            screen = s
                            selected = null
                        },
                        label = { Text(s.title, maxLines = 1) },
                    )
                }
            }

            Text(
                "Arrastra un boton para moverlo; tocalo para cambiar su tamano. La vista es la de tu pantalla.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1B2B1F)),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(screen) {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            // Pixel radius of a control, and where the finger is relative to it.
                            fun radiusPx(spec: ControlSpec, p: Placement) =
                                if (spec.name == JOYSTICK) spec.r * h * p.scale else spec.r * w * p.scale
                            fun pick(at: Offset): String? =
                                shown.map { spec ->
                                    val p = placementOf(spec)
                                    spec to hypot(at.x - p.cx * w, at.y - p.cy * h) - radiusPx(spec, p)
                                }.filter { it.second <= 12f }.minByOrNull { it.second }?.first?.name
                            detectTapGestures(onTap = { selected = pick(it) })
                        }
                        .pointerInput(screen) {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            var dragging: String? = null
                            detectDragGestures(
                                onDragStart = { at ->
                                    dragging = shown.map { spec ->
                                        val p = placements[spec.name] ?: Placement(spec.cx, spec.cy)
                                        val radius = if (spec.name == JOYSTICK) spec.r * h * p.scale else spec.r * w * p.scale
                                        spec to hypot(at.x - p.cx * w, at.y - p.cy * h) - radius
                                    }.filter { it.second <= 12f }.minByOrNull { it.second }?.first?.name
                                    selected = dragging
                                },
                                onDragEnd = {
                                    dragging = null
                                    save()
                                },
                                onDragCancel = { dragging = null },
                            ) { change, drag ->
                                val name = dragging ?: return@detectDragGestures
                                val spec = specOf(name) ?: return@detectDragGestures
                                val current = placements[name] ?: Placement(spec.cx, spec.cy)
                                change.consume()
                                placements[name] = current.copy(
                                    cx = (current.cx + drag.x / w).coerceIn(0.02f, 0.98f),
                                    cy = (current.cy + drag.y / h).coerceIn(0.02f, 0.98f),
                                )
                            }
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    // Thirds, to line things up by eye.
                    for (i in 1..2) {
                        drawLine(Color.White.copy(alpha = 0.06f), Offset(w * i / 3, 0f), Offset(w * i / 3, h))
                        drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, h * i / 3), Offset(w, h * i / 3))
                    }
                    for (spec in shown) {
                        val p = placements[spec.name] ?: Placement(spec.cx, spec.cy)
                        val isStick = spec.name == JOYSTICK
                        val r = if (isStick) spec.r * h * p.scale else spec.r * w * p.scale
                        val center = Offset(p.cx * w, p.cy * h)
                        val alpha = if (p.visible) 1f else 0.25f
                        val isSelected = selected == spec.name
                        drawCircle(Color(0xFF14181F).copy(alpha = 0.75f * alpha), r, center)
                        drawCircle(
                            (if (isSelected) accent else Color.White).copy(alpha = (if (isSelected) 1f else 0.5f) * alpha),
                            r, center, style = Stroke(width = if (isSelected) 5f else 2.5f),
                        )
                        val label = if (isStick) "" else spec.label
                        if (label.isNotEmpty()) {
                            val layout = measurer.measure(
                                label,
                                TextStyle(color = Color.White.copy(alpha = alpha), fontSize = (r * 0.5f / density).sp, fontWeight = FontWeight.Bold),
                            )
                            drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
                        } else {
                            drawCircle(Color.White.copy(alpha = 0.4f * alpha), r * 0.4f, center)
                        }
                    }
                }
            }

            val name = selected
            val spec = name?.let { specOf(it) }
            if (spec == null) {
                Text("Toca un boton para ajustarlo.", style = MaterialTheme.typography.bodyMedium, color = onSurface.copy(alpha = 0.7f))
            } else {
                val p = placementOf(spec)
                SettingsGroup(if (spec.name == JOYSTICK) "Joystick" else "Boton ${spec.label}") {
                    SliderRow(
                        icon = Icons.Filled.ZoomOutMap,
                        title = "Tamano",
                        subtitle = "${"%.0f".format(p.scale * 100)}%",
                        value = p.scale,
                        valueRange = 0.5f..2f,
                        items = if (spec.name == JOYSTICK) 2 else 3,
                        index = 0,
                        onValueChange = {
                            placements[spec.name] = p.copy(scale = it)
                            save()
                        },
                    )
                    if (spec.name != JOYSTICK) {
                        SwitchRow(
                            icon = Icons.Filled.Visibility,
                            title = "Mostrar",
                            subtitle = "Un boton oculto no se dibuja ni se puede tocar",
                            checked = p.visible,
                            items = 3,
                            index = 1,
                            onCheckedChange = {
                                placements[spec.name] = p.copy(visible = it)
                                save()
                            },
                        )
                    }
                    NavRow(
                        icon = Icons.Filled.RestartAlt,
                        title = "Restaurar este boton",
                        subtitle = "Vuelve a su sitio y tamano de origen",
                        items = if (spec.name == JOYSTICK) 2 else 3,
                        index = if (spec.name == JOYSTICK) 1 else 2,
                        onClick = {
                            placements.remove(spec.name)
                            save()
                        },
                    )
                }
            }
        }
    }
}
