package com.ahjin.pr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Tune
import com.ahjin.pr.LayoutPresets
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ahjin.pr.ControlSpec
import com.ahjin.pr.CustomButton
import com.ahjin.pr.LayoutFile
import com.ahjin.pr.LayoutHistory
import com.ahjin.pr.LayoutProfiles
import com.ahjin.pr.JOYSTICK
import com.ahjin.pr.Layout
import com.ahjin.pr.Placement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.translate
import com.ahjin.pr.Preferences
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class Screen(val title: String, val mask: Int)

private val screens = listOf(
    Screen("Mundo", Layout.WORLD),
    Screen("Dialogos", Layout.DIALOG),
    Screen("Menus", Layout.MENU or Layout.MENU_NAV),
    Screen("Pantallas", Layout.SCREEN or Layout.SCREEN_C),
    Screen("Mapa de vuelo", Layout.FLY_MAP),
    Screen("Pokedex: lista", Layout.DEX_LIST),
    Screen("Pokedex: ficha", Layout.DEX_DETAIL),
    Screen("Pokedex: filtro", Layout.DEX_FILTER or Layout.DEX_PLACES),
    Screen("Combate", Layout.BATTLE),
    Screen("Safari", Layout.SAFARI),
    Screen("Elegir personaje", Layout.CHARACTER),
    Screen("Preguntas", Layout.CHOICE),
)

/**
 * Lets the player drag the on-screen buttons where their thumbs want them, resize them and hide the
 * ones they never use. The preview has the proportions of this phone's screen, since the engine
 * measures positions as fractions of it. Edits form a draft with undo/redo; only the explicit Save
 * action writes `layout.conf`, which the game reads when it starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsLayoutScreen(filesDir: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val defaults = remember { Layout.defaults(context) }
    val initial = remember { Layout.read(filesDir) }
    // The draft survives rotation, but only saveLayout() writes the file read by the game.
    var draftText by rememberSaveable { mutableStateOf(Layout.format(initial)) }
    val restored = remember { Layout.parse(draftText) }
    val placements: SnapshotStateMap<String, Placement> = remember {
        mutableStateMapOf<String, Placement>().apply { putAll(restored.placements) }
    }
    val customs: SnapshotStateMap<Int, CustomButton> = remember {
        mutableStateMapOf<Int, CustomButton>().apply { putAll(restored.customs) }
    }
    var savedLayout by remember { mutableStateOf(initial) }
    val history = remember { LayoutHistory(restored) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf(screens[0]) }
    var selected by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf(LayoutProfiles.list(filesDir)) }
    var profileName by remember { mutableStateOf("") }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var saveProblem by remember { mutableStateOf<String?>(null) }
    var pendingPreset by remember { mutableStateOf<LayoutPresets.Preset?>(null) }
    var pendingRestoreAll by remember { mutableStateOf(false) }
    var pendingExit by remember { mutableStateOf(false) }
    var showTest by remember { mutableStateOf(false) }

    fun currentLayout() = LayoutFile(placements.toMap(), customs.toMap())
    fun changed() {
        val next = currentLayout()
        history.change(next)
        draftText = Layout.format(next)
        historyVersion++
    }
    fun restoreSnapshot(snapshot: LayoutFile) {
        placements.clear()
        placements.putAll(snapshot.placements)
        customs.clear()
        customs.putAll(snapshot.customs)
        draftText = Layout.format(snapshot)
        historyVersion++
    }
    fun saveLayout(): Boolean {
        val next = currentLayout()
        val ok = Layout.write(filesDir, next)
        if (ok) {
            savedLayout = next
            draftText = Layout.format(next)
            saveProblem = null
            profileMessage = "Diseno guardado; el juego lo usara al abrirse"
        } else {
            saveProblem = "No pude guardar el diseno. Comprueba el espacio disponible y vuelve a intentarlo."
            profileMessage = null
        }
        return ok
    }
    fun requestBack() {
        if (currentLayout() != savedLayout) pendingExit = true else onBack()
    }

    val dirty = currentLayout() != savedLayout
    val canUndo = historyVersion.let { history.canUndo }
    val canRedo = historyVersion.let { history.canRedo }
    BackHandler(enabled = dirty) { pendingExit = true }
    // The buttons the player made start in the middle of the screen, with the size the engine uses.
    fun customSpec(c: CustomButton) = ControlSpec(c.name, c.label, 0.5f, 0.5f, Layout.CUSTOM_RADIUS, c.contexts)
    val allControls = defaults.controls + customs.values.sortedBy { it.slot }.map(::customSpec)
    fun specOf(name: String): ControlSpec? =
        if (name == JOYSTICK) defaults.joystick else allControls.firstOrNull { it.name == name }
    fun placementOf(spec: ControlSpec) =
        placements[spec.name] ?: Placement(spec.cx, spec.cy, Layout.defaultScale(spec.name))

    val shown = allControls.filter { it.contexts and screen.mask != 0 } +
        (if (screen.mask and (Layout.WORLD or Layout.BATTLE or Layout.CHOICE or Layout.MENU_NAV or Layout.SCREEN or Layout.SCREEN_C or Layout.DEX or Layout.FLY_MAP) != 0) listOf(defaults.joystick) else emptyList())
    // The touch handlers outlive a single composition; they read the current list through this.
    val shownNow by rememberUpdatedState(shown)

    val config = LocalConfiguration.current
    val ratio = max(config.screenWidthDp, config.screenHeightDp).toFloat() / min(config.screenWidthDp, config.screenHeightDp)
    val measurer = rememberTextMeasurer()
    val iconsOn = remember { Preferences(context.applicationContext).functionIcons }
    val iconPainters = rememberButtonPictures()
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    pendingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingPreset = null },
            title = { Text("Aplicar \"${preset.title}\"?") },
            text = {
                Text(
                    "Cambia la posicion y el tamano de los botones del juego. Tus botones propios no se tocan. " +
                        "El cambio queda en el borrador y puedes deshacerlo antes de guardar.",
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
                    changed()
                    profileMessage = "Aplicado \"${preset.title}\" al borrador"
                    pendingPreset = null
                }) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { pendingPreset = null }) { Text("Cancelar") } },
        )
    }

    if (pendingRestoreAll) {
        AlertDialog(
            onDismissRequest = { pendingRestoreAll = false },
            title = { Text("Restaurar todo?") },
            text = { Text("Se restauraran las posiciones y se quitaran los botones propios. El cambio se puede deshacer y no se guardara hasta que pulses Guardar.") },
            confirmButton = {
                TextButton(onClick = {
                    placements.clear()
                    customs.clear()
                    selected = null
                    changed()
                    pendingRestoreAll = false
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { pendingRestoreAll = false }) { Text("Cancelar") } },
        )
    }

    if (pendingExit) {
        AlertDialog(
            onDismissRequest = { pendingExit = false },
            title = { Text("Cambios sin guardar") },
            text = {
                Column {
                    Text("El juego todavia usa el ultimo diseño guardado. Si sales sin guardar, perderas los cambios del borrador.")
                    saveProblem?.let {
                        Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        draftText = Layout.format(savedLayout)
                        pendingExit = false
                        onBack()
                    }) { Text("Salir sin guardar") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (saveLayout()) {
                        pendingExit = false
                        onBack()
                    }
                }) { Text("Guardar y salir") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExit = false }) { Text("Seguir editando") }
            },
        )
    }

    if (showTest) {
        InteractiveLayoutDialog(
            defaults = defaults,
            layout = currentLayout(),
            dirty = dirty,
            iconsOn = iconsOn,
            onDismiss = { showTest = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Posicion de los botones")
                        if (dirty) Text("Cambios sin guardar", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    IconButton(
                        onClick = { history.undo()?.let(::restoreSnapshot) },
                        enabled = canUndo,
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Deshacer") }
                    IconButton(
                        onClick = { history.redo()?.let(::restoreSnapshot) },
                        enabled = canRedo,
                    ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Rehacer") }
                    TextButton(onClick = { saveLayout() }, enabled = dirty) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Text(" Guardar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup("Diseño") {
                NavRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "Probar diseño",
                    subtitle = "Toca los botones y arrastra el movimiento antes de abrir el juego; no envia teclas ni cambia partidas",
                    items = 2,
                    index = 0,
                    onClick = { showTest = true },
                )
                NavRow(
                    icon = Icons.Filled.RestartAlt,
                    title = "Restaurar todo",
                    subtitle = "Vuelve al diseño original en el borrador; puedes deshacerlo",
                    items = 2,
                    index = 1,
                    onClick = { pendingRestoreAll = true },
                )
            }
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
                                        val p = placementOf(spec)
                                        val radius = if (spec.name == JOYSTICK) spec.r * h * p.scale else spec.r * w * p.scale
                                        spec to hypot(at.x - p.cx * w, at.y - p.cy * h) - radius
                                    }.filter { it.second <= 12f }.minByOrNull { it.second }?.first?.name
                                    selected = dragging
                                },
                                onDragEnd = {
                                    dragging = null
                                    changed()
                                },
                                onDragCancel = {
                                    dragging = null
                                    restoreSnapshot(history.current)
                                },
                            ) { change, drag ->
                                val name = dragging ?: return@detectDragGestures
                                // This handler survives recompositions. Read the current list: a custom
                                // button added after the gesture started did not exist in the old capture.
                                val spec = shownNow.firstOrNull { it.name == name } ?: return@detectDragGestures
                                val current = placementOf(spec)
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
                        val p = placementOf(spec)
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
                            // The 160 px files have a transparent margin around the original 128 px
                            // canvas. Compensate for it so the visible drawing matches the engine.
                            val side = r * 1.3f * (160f / 128f)
                            translate(center.x - side / 2f, center.y - side / 2f) {
                                with(painter) { draw(Size(side, side), alpha = alpha) }
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

            Text(
                if (dirty) "Borrador con cambios sin guardar; el juego todavia usa el diseño anterior."
                else "Diseño guardado; el juego lo leera al abrirse.",
                style = MaterialTheme.typography.bodySmall,
                color = if (dirty) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.7f),
            )
            saveProblem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            val name = selected
            val spec = name?.let { specOf(it) }
            val custom = spec?.let { sp -> customs.values.firstOrNull { it.name == sp.name } }
            if (spec == null) {
                Text("Toca un boton para ajustarlo.", style = MaterialTheme.typography.bodyMedium, color = onSurface.copy(alpha = 0.7f))
            } else {
                val p = placementOf(spec)
                val rows = when {
                    spec.name == JOYSTICK -> 2
                    custom == null -> 3
                    else -> 4
                }
                SettingsGroup(if (spec.name == JOYSTICK) "Joystick y cruceta" else "Boton ${Layout.iconOf(spec.label)?.let { Layout.ICON_TITLES[it] } ?: spec.label}") {
                    SliderRow(
                        icon = Icons.Filled.ZoomOutMap,
                        title = "Tamano",
                        subtitle = "${"%.0f".format(p.scale * 100)}%",
                        value = p.scale,
                        valueRange = 0.5f..2f,
                        items = rows,
                        index = 0,
                        onValueChange = {
                            val current = placementOf(spec)
                            placements[spec.name] = current.copy(scale = it)
                        },
                        onValueChangeFinished = { changed() },
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
                                changed()
                            },
                        )
                    }
                    if (custom == null) {
                        NavRow(
                            icon = Icons.Filled.RestartAlt,
                            title = "Restaurar este boton",
                            subtitle = "Vuelve a su sitio, tamaño y visibilidad de origen sin tocar los demas",
                            items = rows,
                            index = rows - 1,
                            onClick = {
                                val next = currentLayout().resetButton(spec.name)
                                placements.clear()
                                placements.putAll(next.placements)
                                changed()
                            },
                        )
                    } else {
                        NavRow(
                            icon = Icons.Filled.RestartAlt,
                            title = "Restaurar posicion",
                            subtitle = "Conserva la tecla, el icono, el modo y las pantallas de este boton propio",
                            items = rows,
                            index = rows - 2,
                            onClick = {
                                val next = currentLayout().resetButton(spec.name)
                                placements.clear()
                                placements.putAll(next.placements)
                                changed()
                            },
                        )
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
                                changed()
                            },
                        )
                    }
                }
                if (custom != null) {
                    CustomButtonEditor(
                        custom = custom,
                        onChange = {
                            customs[it.slot] = it
                            changed()
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
                        changed()
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
                            changed()
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
                                        changed()
                                        profileMessage = "Cargado \"$name\" en el borrador"
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
                    profileMessage = if (ok) "Copia \"${LayoutProfiles.clean(profileName)}\" guardada" else "No se pudo guardar la copia"
                    if (ok) profileName = ""
                },
            ) { Text("Guardar una copia con este nombre") }
            profileMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Full-screen, harmless rehearsal of the draft. It never sends input to the emulator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InteractiveLayoutDialog(
    defaults: Layout.Defaults,
    layout: LayoutFile,
    dirty: Boolean,
    iconsOn: Boolean,
    onDismiss: () -> Unit,
) {
    val config = LocalConfiguration.current
    val ratio = max(config.screenWidthDp, config.screenHeightDp).toFloat() / min(config.screenWidthDp, config.screenHeightDp)
    val pictures = rememberButtonPictures()
    var screen by remember { mutableStateOf(screens[0]) }
    var lastPressed by remember { mutableStateOf<String?>(null) }
    var lastAction by remember { mutableStateOf("Toca un boton o arrastra el movimiento.") }
    var stickDelta by remember { mutableStateOf(Offset.Zero) }

    val customSpecs = layout.customs.values.sortedBy { it.slot }.map {
        ControlSpec(it.name, it.label, 0.5f, 0.5f, Layout.CUSTOM_RADIUS, it.contexts)
    }
    val allControls = defaults.controls + customSpecs
    val shown = allControls.filter { it.contexts and screen.mask != 0 } +
        (if (screen.mask and (Layout.WORLD or Layout.BATTLE or Layout.CHOICE or Layout.MENU_NAV or Layout.SCREEN or Layout.SCREEN_C or Layout.DEX or Layout.FLY_MAP) != 0) listOf(defaults.joystick) else emptyList())

    fun announce(spec: ControlSpec) {
        lastPressed = spec.name
        val custom = layout.customs.values.firstOrNull { it.name == spec.name }
        lastAction = when {
            spec.name == JOYSTICK -> "Movimiento: arrastra en cualquier direccion."
            custom?.key == Layout.ACTION_SPEED -> "Velocidad del juego: vista previa, sin cambiar la velocidad real."
            custom?.key == Layout.ACTION_TYPES -> "Tabla de tipos: vista previa, sin abrir el juego."
            custom != null -> {
                val mode = when (custom.mode) {
                    Layout.MODE_TURBO -> "turbo"
                    Layout.MODE_LOCK -> "fijo"
                    else -> "pulsacion normal"
                }
                "${custom.baseKey}: $mode. La posicion y el tamaño responden correctamente."
            }
            else -> {
                val icon = Layout.iconOf(spec.label) ?: Layout.builtinIcon(spec.name, screen.mask, iconsOn)
                val title = icon?.let { Layout.ICON_TITLES[it] } ?: spec.label
                "Boton $title: posicion y tamaño correctos."
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Prueba del diseño")
                            Text(
                                if (dirty) "Borrador sin guardar" else "Diseño guardado",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Volver al editor") }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    screens.forEach { item ->
                        FilterChip(
                            selected = item === screen,
                            onClick = {
                                screen = item
                                lastPressed = null
                            },
                            label = { Text(item.title, maxLines = 1) },
                        )
                    }
                }
                Text(
                    "Solo comprueba la respuesta tactil: no envia teclas al juego ni toca las partidas.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101612)),
                ) {
                    val previewWidth: androidx.compose.ui.unit.Dp
                    val previewHeight: androidx.compose.ui.unit.Dp
                    if (maxWidth / maxHeight > ratio) {
                        previewHeight = maxHeight
                        previewWidth = maxHeight * ratio
                    } else {
                        previewWidth = maxWidth
                        previewHeight = maxWidth / ratio
                    }
                    Box(
                        modifier = Modifier.size(previewWidth, previewHeight).align(Alignment.Center).background(Color(0xFF1B2B1F)),
                    ) {
                        val width = previewWidth
                        val height = previewHeight
                        for (spec in shown) {
                            val placement = layout.placements[spec.name]
                                ?: Placement(spec.cx, spec.cy, Layout.defaultScale(spec.name))
                            if (!placement.visible) continue
                            val isStick = spec.name == JOYSTICK
                            val radius = if (isStick) height * spec.r * placement.scale else width * spec.r * placement.scale
                            val icon = if (isStick) null else Layout.iconOf(spec.label)
                                ?: if (spec.name.startsWith("custom")) null else Layout.builtinIcon(spec.name, screen.mask, iconsOn)
                            val painter = icon?.let { pictures[it] }
                            val label = if (isStick || spec.label.startsWith("~")) "" else spec.label
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (width.toPx() * placement.cx - radius.toPx()).roundToInt(),
                                            (height.toPx() * placement.cy - radius.toPx()).roundToInt(),
                                        )
                                    }
                                    .size(radius * 2)
                                    .clip(CircleShape)
                                    .background(Color(0xFF14181F).copy(alpha = 0.82f))
                                    .border(
                                        width = if (lastPressed == spec.name) 3.dp else 1.dp,
                                        color = if (lastPressed == spec.name) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f),
                                        shape = CircleShape,
                                    )
                                    .clickable { announce(spec) }
                                    .then(
                                        if (isStick) {
                                            Modifier.pointerInput(spec.name) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        stickDelta = Offset.Zero
                                                        lastPressed = JOYSTICK
                                                        lastAction = "Movimiento: centro"
                                                    },
                                                    onDragEnd = { stickDelta = Offset.Zero },
                                                    onDragCancel = { stickDelta = Offset.Zero },
                                                ) { change, drag ->
                                                    change.consume()
                                                    stickDelta += drag
                                                    lastPressed = JOYSTICK
                                                    lastAction = "Movimiento: ${movementDirection(stickDelta)}"
                                                }
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    painter != null -> Image(
                                        painter = painter,
                                        contentDescription = null,
                                        modifier = Modifier.size(radius * 1.3f * (160f / 128f)),
                                    )
                                    label.isNotEmpty() -> Text(
                                        label,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (radius.value * 0.5f).coerceIn(10f, 26f).sp,
                                        maxLines = 1,
                                    )
                                    else -> Box(
                                        Modifier.size(radius * 0.65f).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)),
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    lastAction,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun movementDirection(delta: Offset): String {
    if (delta.getDistance() < 14f) return "centro"
    val vertical = when {
        delta.y < -8f -> "arriba"
        delta.y > 8f -> "abajo"
        else -> null
    }
    val horizontal = when {
        delta.x < -8f -> "izquierda"
        delta.x > 8f -> "derecha"
        else -> null
    }
    return listOfNotNull(vertical, horizontal).joinToString(" + ")
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
    val pictures = rememberButtonPictures()
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
                        leadingIcon = if (pictures[icon] != null) {
                            { Image(pictures.getValue(icon), contentDescription = null, modifier = Modifier.size(20.dp)) }
                        } else {
                            null
                        },
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
