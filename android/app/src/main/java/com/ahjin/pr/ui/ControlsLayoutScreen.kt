package com.ahjin.pr.ui

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Tune
import com.ahjin.pr.LayoutPresets
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.ahjin.pr.ControlSpec
import com.ahjin.pr.CustomButton
import com.ahjin.pr.LayoutFile
import com.ahjin.pr.LayoutProfiles
import com.ahjin.pr.JOYSTICK
import com.ahjin.pr.Layout
import com.ahjin.pr.Placement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.ahjin.pr.Preferences
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private class Screen(val title: String, val mask: Int)

private val screens = listOf(
    Screen("Mundo", Layout.WORLD),
    Screen("Dialogos", Layout.DIALOG),
    Screen("Menus", Layout.MENU or Layout.MENU_NAV),
    Screen("Pantallas", Layout.SCREEN or Layout.SCREEN_C),
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
    val initial = remember { Layout.read(filesDir) }
    val placements: SnapshotStateMap<String, Placement> = remember {
        mutableStateMapOf<String, Placement>().apply { putAll(initial.placements) }
    }
    val customs: SnapshotStateMap<Int, CustomButton> = remember {
        mutableStateMapOf<Int, CustomButton>().apply { putAll(initial.customs) }
    }
    var screen by remember { mutableStateOf(screens[0]) }
    var selected by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf(LayoutProfiles.list(filesDir)) }
    var profileName by remember { mutableStateOf("") }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var pendingPreset by remember { mutableStateOf<LayoutPresets.Preset?>(null) }

    fun save() = Layout.write(filesDir, LayoutFile(placements.toMap(), customs.toMap()))
    // The buttons the player made start in the middle of the screen, with the size the engine uses.
    fun customSpec(c: CustomButton) = ControlSpec(c.name, c.label, 0.5f, 0.5f, Layout.CUSTOM_RADIUS, c.contexts)
    val allControls = defaults.controls + customs.values.sortedBy { it.slot }.map(::customSpec)
    fun specOf(name: String): ControlSpec? =
        if (name == JOYSTICK) defaults.joystick else allControls.firstOrNull { it.name == name }
    fun placementOf(spec: ControlSpec) = placements[spec.name] ?: Placement(spec.cx, spec.cy)

    val shown = allControls.filter { it.contexts and screen.mask != 0 } +
        (if (screen.mask and (Layout.WORLD or Layout.BATTLE or Layout.CHOICE) != 0) listOf(defaults.joystick) else emptyList())
    // The touch handlers outlive a single composition; they read the current list through this.
    val shownNow by rememberUpdatedState(shown)

    val config = LocalConfiguration.current
    val ratio = max(config.screenWidthDp, config.screenHeightDp).toFloat() / min(config.screenWidthDp, config.screenHeightDp)
    val measurer = rememberTextMeasurer()
    val iconsOn = remember { Preferences(context.applicationContext).functionIcons }
    val iconPainters = Layout.ICONS.associateWith { rememberVectorPainter(iconVector(it)) }
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    pendingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingPreset = null },
            title = { Text("Aplicar \"${preset.title}\"?") },
            text = {
                Text(
                    "Cambia la posicion y el tamano de los botones del juego. Tus botones propios no se tocan. " +
                        "Si quieres poder volver a tu disposicion actual, guardala antes en Disenos guardados.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = LayoutPresets.placements(defaults, preset, placements.toMap())
                    // Only the buttons of the game are replaced; the custom ones keep their own place.
                    val keep = placements.filterKeys { it.startsWith("custom") }
                    placements.clear()
                    placements.putAll(next)
                    placements.putAll(keep)
                    selected = null
                    save()
                    profileMessage = "Aplicado \"${preset.title}\""
                    pendingPreset = null
                }) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { pendingPreset = null }) { Text("Cancelar") } },
        )
    }

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
                        customs.clear()
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
                                shownNow.map { spec ->
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
                                    dragging = shownNow.map { spec ->
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
                        val icon = if (isStick) null else Layout.iconOf(spec.label)
                            ?: if (spec.name.startsWith("custom")) null else Layout.builtinIcon(spec.name, screen.mask, iconsOn)
                        val painter = icon?.let { iconPainters[it] }
                        val label = if (isStick || spec.label.startsWith("~")) "" else spec.label
                        if (painter != null) {
                            val side = r * 1.15f
                            translate(center.x - side / 2f, center.y - side / 2f) {
                                with(painter) { draw(Size(side, side), colorFilter = ColorFilter.tint(Color.White.copy(alpha = alpha))) }
                            }
                        } else if (label.isNotEmpty()) {
                            val layout = measurer.measure(
                                label,
                                TextStyle(color = Color.White.copy(alpha = alpha), fontSize = (min(r * 0.5f, r * 1.5f / label.length.coerceAtLeast(1)) / density).sp, fontWeight = FontWeight.Bold),
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
            val custom = spec?.let { sp -> customs.values.firstOrNull { it.name == sp.name } }
            if (spec == null) {
                Text("Toca un boton para ajustarlo.", style = MaterialTheme.typography.bodyMedium, color = onSurface.copy(alpha = 0.7f))
            } else {
                val p = placementOf(spec)
                val rows = if (spec.name == JOYSTICK) 2 else 3
                SettingsGroup(if (spec.name == JOYSTICK) "Joystick" else "Boton ${Layout.iconOf(spec.label)?.let { Layout.ICON_TITLES[it] } ?: spec.label}") {
                    SliderRow(
                        icon = Icons.Filled.ZoomOutMap,
                        title = "Tamano",
                        subtitle = "${"%.0f".format(p.scale * 100)}%",
                        value = p.scale,
                        valueRange = 0.5f..2f,
                        items = rows,
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
                            items = rows,
                            index = 1,
                            onCheckedChange = {
                                placements[spec.name] = p.copy(visible = it)
                                save()
                            },
                        )
                    }
                    if (custom == null) {
                        NavRow(
                            icon = Icons.Filled.RestartAlt,
                            title = "Restaurar este boton",
                            subtitle = "Vuelve a su sitio y tamano de origen",
                            items = rows,
                            index = rows - 1,
                            onClick = {
                                placements.remove(spec.name)
                                save()
                            },
                        )
                    } else {
                        NavRow(
                            icon = Icons.Filled.Delete,
                            title = "Eliminar este boton",
                            subtitle = "Lo quita del todo; puedes crear otro cuando quieras",
                            items = rows,
                            index = rows - 1,
                            onClick = {
                                placements.remove(spec.name)
                                customs.remove(custom.slot)
                                selected = null
                                save()
                            },
                        )
                    }
                }
                if (custom != null) {
                    CustomButtonEditor(
                        custom = custom,
                        onChange = {
                            customs[it.slot] = it
                            save()
                        },
                    )
                }
            }

            SettingsGroup("Botones propios") {
                val free = (1..Layout.CUSTOM_SLOTS).firstOrNull { it !in customs }
                NavRow(
                    icon = Icons.Filled.Add,
                    title = "Anadir un boton",
                    subtitle = if (free == null) "Ya hay ${Layout.CUSTOM_SLOTS}, el maximo" else
                        "Elige que tecla pulsa, que dice y en que pantallas sale (${customs.size} de ${Layout.CUSTOM_SLOTS})",
                    items = 1,
                    index = 0,
                    enabled = free != null,
                    onClick = {
                        val slot = free ?: return@NavRow
                        // Starts out pressing Z (the confirm key), on the screen being looked at.
                        val button = CustomButton(slot, "Z", Layout.defaultLabel("Z"), screen.mask)
                        customs[slot] = button
                        placements[button.name] = Placement(0.5f, 0.5f)
                        selected = button.name
                        save()
                    },
                )
            }

            // Buttons that do more than hold a key. None is there until it is added, and each one is
            // a button like the rest: it can be moved, resized, hidden and deleted.
            SettingsGroup("Botones especiales") {
                val free = (1..Layout.CUSTOM_SLOTS).firstOrNull { it !in customs }
                SpecialButtons.ALL.forEachIndexed { position, special ->
                    NavRow(
                        icon = Icons.Filled.Add,
                        title = special.title,
                        subtitle = if (free == null) "Ya hay ${Layout.CUSTOM_SLOTS} botones propios, el maximo" else special.description,
                        items = SpecialButtons.ALL.size,
                        index = position,
                        enabled = free != null,
                        onClick = {
                            val slot = free ?: return@NavRow
                            val button = CustomButton(slot, special.key, special.label, special.contexts)
                            customs[slot] = button
                            placements[button.name] = Placement(0.5f, 0.5f)
                            selected = button.name
                            save()
                        },
                    )
                }
            }

            SettingsGroup("Preajustes") {
                LayoutPresets.ALL.forEachIndexed { index, preset ->
                    NavRow(
                        icon = Icons.Filled.Tune,
                        title = preset.title,
                        subtitle = preset.description,
                        items = LayoutPresets.ALL.size,
                        index = index,
                        onClick = { pendingPreset = preset },
                    )
                }
            }

            SettingsGroup("Disenos guardados") {
                val saved = profiles
                if (saved.isEmpty()) {
                    InfoRow(
                        icon = Icons.Filled.Folder,
                        title = "Ninguno todavia",
                        value = "Guarda la disposicion actual con un nombre para volver a ella cuando quieras",
                        items = 1,
                        index = 0,
                    )
                }
                saved.forEachIndexed { index, name ->
                    GroupListItem(
                        headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium) },
                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = {
                                    val loaded = LayoutProfiles.load(filesDir, name)
                                    if (loaded == null) {
                                        profileMessage = "No pude leer \"$name\""
                                    } else {
                                        placements.clear()
                                        placements.putAll(loaded.placements)
                                        customs.clear()
                                        customs.putAll(loaded.customs)
                                        selected = null
                                        save()
                                        profileMessage = "Cargado \"$name\""
                                    }
                                }) { Text("Usar") }
                                IconButton(onClick = {
                                    LayoutProfiles.delete(filesDir, name)
                                    profiles = LayoutProfiles.list(filesDir)
                                }) { Icon(Icons.Filled.Delete, contentDescription = "Borrar $name") }
                            }
                        },
                        items = saved.size,
                        index = index,
                    )
                }
            }
            OutlinedTextField(
                value = profileName,
                onValueChange = { text -> profileName = text.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.take(24) },
                label = { Text("Nombre del diseno") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                enabled = LayoutProfiles.clean(profileName).isNotEmpty(),
                onClick = {
                    val ok = LayoutProfiles.save(filesDir, profileName, LayoutFile(placements.toMap(), customs.toMap()))
                    profiles = LayoutProfiles.list(filesDir)
                    profileMessage = if (ok) "Guardado \"${LayoutProfiles.clean(profileName)}\"" else "No se pudo guardar"
                    if (ok) profileName = ""
                },
            ) { Text("Guardar la disposicion actual") }
            profileMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A ready-made button with a way of acting the plain "Anadir un boton" does not offer. */
private class Special(
    val title: String,
    val description: String,
    val key: String,
    val label: String,
    val contexts: Int,
)

private object SpecialButtons {
    val ALL = listOf(
        Special(
            "Correr fijo",
            "Un toque deja X pulsada y otro la suelta: se corre sin mantener el dedo",
            "X:lock", Layout.iconLabel("run"), Layout.WORLD,
        ),
        Special(
            "Boton turbo",
            "Mantenerlo pulsa Z una y otra vez: pasa los textos y los menus a toda prisa",
            "Z:turbo", "TUR", Layout.WORLD or Layout.DIALOG,
        ),
        Special(
            "Velocidad del juego",
            "Cada toque pasa a la siguiente velocidad: normal, camara lenta, rapido y vuelta a normal",
            Layout.ACTION_SPEED, Layout.iconLabel("forward"), Layout.WORLD or Layout.BATTLE or Layout.SAFARI or Layout.DIALOG,
        ),
        Special(
            "Tabla de tipos",
            "Muestra la tabla de ventajas y debilidades del juego; un toque en la pantalla la cierra",
            Layout.ACTION_TYPES, Layout.iconLabel("chart"), Layout.BATTLE,
        ),
    )
}

/** The button with another key (or another thing to do), its label following when it still said the old name. */
private fun CustomButton.withKey(token: String): CustomButton {
    val follows = icon == null && label == Layout.defaultLabel(key)
    return copy(key = token, label = if (follows) Layout.defaultLabel(token) else label)
}

/** Key, way of acting, label and screens of a button the player made. */
@Composable
private fun CustomButtonEditor(custom: CustomButton, onChange: (CustomButton) -> Unit) {
    val isAction = custom.mode == Layout.MODE_ACTION
    SettingsGroup("Que hace este boton") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Funcion", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = !isAction,
                    onClick = { if (isAction) onChange(custom.withKey("Z")) },
                    label = { Text("Tecla del juego", maxLines = 1) },
                )
                FilterChip(
                    selected = custom.key == Layout.ACTION_SPEED,
                    onClick = { onChange(custom.withKey(Layout.ACTION_SPEED)) },
                    label = { Text("Velocidad", maxLines = 1) },
                )
                FilterChip(
                    selected = custom.key == Layout.ACTION_TYPES,
                    onClick = { onChange(custom.withKey(Layout.ACTION_TYPES)) },
                    label = { Text("Tabla de tipos", maxLines = 1) },
                )
            }
            if (isAction) {
                Text(
                    if (custom.key == Layout.ACTION_SPEED) "Cada toque pasa a la siguiente velocidad (la lista se elige en Extras)."
                    else "Abre la tabla de tipos; un toque en la pantalla la cierra.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Tecla que pulsa", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Layout.CUSTOM_KEYS.forEach { key ->
                        FilterChip(
                            selected = custom.baseKey == key,
                            // The way of acting stays the way it was; the label follows the key while it
                            // still says what the old key did.
                            onClick = { onChange(custom.withKey(Layout.keyToken(key, custom.mode))) },
                            label = { Text(key, maxLines = 1) },
                        )
                    }
                }
                Text("Como actua", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        Layout.MODE_PRESS to "Normal",
                        Layout.MODE_TURBO to "Turbo",
                        Layout.MODE_LOCK to "Fijo",
                    ).forEach { (mode, title) ->
                        FilterChip(
                            selected = custom.mode == mode,
                            onClick = { onChange(custom.copy(key = Layout.keyToken(custom.baseKey, mode))) },
                            label = { Text(title, maxLines = 1) },
                        )
                    }
                }
                Text(
                    when (custom.mode) {
                        Layout.MODE_TURBO -> "Turbo: mientras lo mantienes, la tecla se pulsa unas 7 veces por segundo."
                        Layout.MODE_LOCK -> "Fijo: un toque deja la tecla pulsada y otro la suelta. Se suelta sola al salir de la pantalla del boton."
                        else -> "Normal: la tecla esta pulsada mientras mantienes el dedo."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Que muestra", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = custom.icon == null,
                    onClick = { if (custom.icon != null) onChange(custom.copy(label = Layout.defaultLabel(custom.key))) },
                    label = { Text("Texto", maxLines = 1) },
                )
                Layout.ICONS.forEach { icon ->
                    FilterChip(
                        selected = custom.icon == icon,
                        onClick = { onChange(custom.copy(label = Layout.iconLabel(icon))) },
                        label = { Text(Layout.ICON_TITLES[icon] ?: icon, maxLines = 1) },
                        leadingIcon = { Icon(iconVector(icon), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (custom.icon == null) {
                OutlinedTextField(
                    value = custom.label,
                    onValueChange = { text -> onChange(custom.copy(label = Layout.cleanLabel(text))) },
                    label = { Text("Texto del boton (hasta 3 letras)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Pantallas donde aparece", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                screens.forEach { s ->
                    val on = custom.contexts and s.mask != 0
                    FilterChip(
                        selected = on,
                        onClick = {
                            val next = if (on) custom.contexts and s.mask.inv() else custom.contexts or s.mask
                            // A button that shows nowhere could not be found again, so one stays on.
                            if (next != 0) onChange(custom.copy(contexts = next))
                        },
                        label = { Text(s.title, maxLines = 1) },
                    )
                }
            }
        }
    }
}
