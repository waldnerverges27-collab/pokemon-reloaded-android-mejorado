package com.ahjin.pr

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The on-screen buttons the layout editor can move. The built-in positions come from
 * `assets/controls-layout.json`, which is generated from the engine's own table of controls
 * (`tools/generate-layout.py`), so the editor and the game cannot disagree about where things are.
 */
data class ControlSpec(
    val name: String,
    val label: String,
    val cx: Float,
    val cy: Float,
    /** Radius as a fraction of the screen *width*, the way the engine measures it. */
    val r: Float,
    /** Bit mask of the game states the button belongs to (same values as the engine's). */
    val contexts: Int,
)

/** Where the player put one button. [scale] multiplies its built-in size. */
data class Placement(val cx: Float, val cy: Float, val scale: Float = 1f, val visible: Boolean = true)

/**
 * A button the player made. It lives in one of eight slots (`custom1` to `custom8`) and presses
 * [key] in the game; [contexts] are the screens it shows on, [label] (up to three characters) is
 * what it says. Where it sits and how big it is goes in the slot's [Placement], like any button.
 */
data class CustomButton(val slot: Int, val key: String, val label: String, val contexts: Int) {
    val name: String get() = "custom$slot"

    /** The picture the button wears instead of text (its label is then written `~name`), or null. */
    val icon: String? get() = Layout.iconOf(label)

    /** The key without how it acts (`Z` for `Z:turbo`), or the action itself (`@speed`). */
    val baseKey: String get() = Layout.baseKey(key)

    /** How it acts: [Layout.MODE_PRESS], [Layout.MODE_TURBO], [Layout.MODE_LOCK] or [Layout.MODE_ACTION]. */
    val mode: String get() = Layout.modeOf(key)
}

/** What `layout.conf` holds: where each button is, and the buttons the player made. */
data class LayoutFile(val placements: Map<String, Placement>, val customs: Map<Int, CustomButton>)

/** The movement stick: radius is a fraction of the screen *height*. */
const val JOYSTICK = "joystick"

object Layout {
    const val WORLD = 2
    const val BATTLE = 4
    const val DIALOG = 8
    const val CHARACTER = 16
    const val CHOICE = 32
    const val SAFARI = 64

    class Defaults(val controls: List<ControlSpec>, val joystick: ControlSpec)

    fun defaults(context: Context): Defaults {
        val text = context.assets.open("controls-layout.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val list = root.getJSONArray("controls")
        val controls = (0 until list.length()).map {
            val o = list.getJSONObject(it)
            ControlSpec(
                o.getString("name"), o.getString("label"),
                o.getDouble("cx").toFloat(), o.getDouble("cy").toFloat(), o.getDouble("r").toFloat(),
                o.getInt("contexts"),
            )
        }
        val j = root.getJSONObject("joystick")
        val stick = ControlSpec(
            JOYSTICK, "", j.getDouble("cx").toFloat(), j.getDouble("cy").toFloat(), j.getDouble("r").toFloat(),
            WORLD or BATTLE or CHOICE,
        )
        return Defaults(controls, stick)
    }

    const val MENU = 1
    const val MENU_NAV = 128
    const val CUSTOM_SLOTS = 8

    /** Radius of a custom button, as the engine's table has it (fraction of the screen width). */
    const val CUSTOM_RADIUS = 0.026f

    /** The keys a custom button may press, by the names the engine understands. */
    val CUSTOM_KEYS: List<String> =
        ('A'..'Z').map { it.toString() } + (0..9).map { it.toString() } +
            (1..12).map { "F$it" } +
            listOf("RETURN", "SPACE", "ESCAPE", "TAB", "BACKSPACE", "UP", "DOWN", "LEFT", "RIGHT", "SHIFT", "CONTROL")

    /** Actions of the app's own a custom button can do instead of pressing a key. */
    const val ACTION_SPEED = "@speed"
    const val ACTION_TYPES = "@types"
    val ACTIONS: List<String> = listOf(ACTION_SPEED, ACTION_TYPES)

    const val MODE_PRESS = "press"
    const val MODE_TURBO = "turbo"
    const val MODE_LOCK = "lock"
    const val MODE_ACTION = "action"

    /** The key part of what a custom button's key field says: `Z:turbo` is `Z`; an action stays as it is. */
    fun baseKey(token: String): String = if (token.startsWith("@")) token else token.substringBefore(':')

    /** How a key field acts: a plain key is pressed, `:turbo` taps it over and over, `:lock` keeps it down. */
    fun modeOf(token: String): String = when {
        token.startsWith("@") -> MODE_ACTION
        token.endsWith(":turbo") -> MODE_TURBO
        token.endsWith(":lock") -> MODE_LOCK
        else -> MODE_PRESS
    }

    /** Puts a key and a way of acting together into the text the engine reads. */
    fun keyToken(key: String, mode: String): String = when {
        key.startsWith("@") -> key
        mode == MODE_TURBO -> "$key:turbo"
        mode == MODE_LOCK -> "$key:lock"
        else -> key
    }

    /**
     * What `layout.conf` may hold in a custom button's key field, cleaned up: the key in capitals and
     * the way of acting in lower case. Null when it is none of the things the engine understands.
     */
    fun normalizeKey(raw: String): String? {
        val text = raw.trim()
        if (text.startsWith("@")) return text.lowercase().takeIf { it in ACTIONS }
        val base = text.substringBefore(':').uppercase()
        if (base !in CUSTOM_KEYS) return null
        if (':' !in text) return base
        return when (text.substringAfter(':').lowercase()) {
            "turbo" -> "$base:turbo"
            "lock" -> "$base:lock"
            else -> null
        }
    }

    /** The pictures a custom button can wear. The engine draws them (`Icon` in `touch_controls.rs`). */
    val ICONS: List<String> = listOf(
        "act", "run", "hook", "bike", "ball", "team", "bag", "map", "star", "exit", "swap", "bolt", "box",
        "person", "magnifier", "chat", "smile", "clock", "heart", "check", "plus", "menu", "pause", "back",
        "save", "load", "forward", "chart", "up", "down", "left", "right",
    )

    /** What each picture is called in the editor. */
    val ICON_TITLES: Map<String, String> = mapOf(
        "act" to "Accion", "run" to "Correr", "hook" to "Cana", "bike" to "Bici", "ball" to "Bola", "team" to "Equipo",
        "bag" to "Mochila", "map" to "Mapa", "star" to "Estrella", "exit" to "Huir", "swap" to "Cambiar", "bolt" to "Rayo",
        "box" to "Objeto", "person" to "Entrenador", "magnifier" to "Buscar", "chat" to "Hablar", "smile" to "Emoji",
        "clock" to "Reloj", "heart" to "Corazon", "check" to "Ok", "plus" to "Mas", "menu" to "Menu", "pause" to "Pausa",
        "back" to "Atras", "save" to "Guardar", "load" to "Cargar", "forward" to "Rapido", "chart" to "Tabla", "up" to "Arriba",
        "down" to "Abajo", "left" to "Izquierda", "right" to "Derecha",
    )

    /** A picture's name in the label field: `~star`. */
    fun iconLabel(icon: String): String = "~$icon"

    /** The icon a label asks for, or null when it is plain text (or names an icon that does not exist). */
    fun iconOf(label: String): String? = label.takeIf { it.startsWith("~") }?.drop(1)?.lowercase()?.takeIf { it in ICONS }

    /** What the button's label is once it has been cleaned: an icon keeps its name, text is at most three capitals. */
    fun cleanLabel(raw: String): String {
        iconOf(raw)?.let { return iconLabel(it) }
        return raw.filter { it != ',' && it != '=' && it != '~' && !it.isWhitespace() }.take(3).uppercase()
    }

    /**
     * The picture a button of the game wears in a given screen, mirroring `icon_for` in
     * `touch_controls.rs` so the editor shows what the game will. Null means the button shows its letter.
     */
    fun builtinIcon(name: String, context: Int, functionIcons: Boolean): String? {
        when (name) {
            "return" -> return "menu"
            "escape" -> return "back"
            "b" -> return "ball"
            "sys_qsave" -> return "save"
            "sys_qload" -> return "load"
            "sys_ffwd" -> return "forward"
        }
        if (!functionIcons) return null
        val battle = context and BATTLE != 0
        val fight = context and (BATTLE or SAFARI) != 0
        val world = context and WORLD != 0
        return when (name) {
            "c" -> "hook".takeIf { world }
            "v" -> "bike".takeIf { world }
            "space" -> "team"
            "o" -> "bag"
            "m" -> if (battle) "star" else "map"
            "e" -> if (fight) "exit" else "smile"
            "t" -> if (battle) "clock" else "chat"
            "l" -> "magnifier"
            "p" -> "person"
            "n" -> "bolt"
            "a" -> "box"
            "s" -> "swap"
            else -> null
        }
    }

    /** A short name for the key, which is also what a new button says on its face. */
    fun defaultLabel(token: String): String = when (val key = baseKey(token)) {
        ACTION_SPEED -> "VEL"
        ACTION_TYPES -> "TIP"
        "RETURN" -> "ENT"
        "SPACE" -> "SPC"
        "ESCAPE" -> "ESC"
        "BACKSPACE" -> "BCK"
        "CONTROL" -> "CTL"
        "SHIFT" -> "SHF"
        "UP" -> "ARR"
        "DOWN" -> "ABA"
        "LEFT" -> "IZQ"
        "RIGHT" -> "DER"
        else -> key.take(3)
    }

    /** What `layout.conf` says. A missing or damaged file is the same as no changes. */
    fun read(filesDir: File): LayoutFile = parse(runCatching { File(filesDir, "layout.conf").readText() }.getOrNull().orEmpty())

    fun parse(text: String): LayoutFile {
        val placements = mutableMapOf<String, Placement>()
        val customs = mutableMapOf<Int, CustomButton>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val name = trimmed.substringBefore('=', "").trim()
            val parts = trimmed.substringAfter('=', "").split(',').map { it.trim() }
            if (name.isEmpty() || parts.size < 3) continue
            val cx = parts[0].toFloatOrNull()?.takeIf { it in 0f..1f } ?: continue
            val cy = parts[1].toFloatOrNull()?.takeIf { it in 0f..1f } ?: continue
            val scale = parts[2].toFloatOrNull() ?: continue
            val visible = parts.getOrNull(3) !in listOf("0", "false", "no")
            val slot = name.removePrefix("custom").toIntOrNull()?.takeIf { name.startsWith("custom") && it in 1..CUSTOM_SLOTS }
            if (slot != null) {
                // A custom button without its key, label and screens is no button at all.
                val key = parts.getOrNull(4)?.let { normalizeKey(it) } ?: continue
                val label = parts.getOrNull(5) ?: continue
                val contexts = parts.getOrNull(6)?.toIntOrNull()?.takeIf { it in 1..255 } ?: continue
                customs[slot] = CustomButton(slot, key, cleanLabel(label).ifEmpty { defaultLabel(key) }, contexts)
            }
            placements[name] = Placement(cx, cy, scale, visible)
        }
        return LayoutFile(placements, customs)
    }

    /** Writes only what differs from the built-in layout, in the engine's format. */
    fun write(filesDir: File, layout: LayoutFile) {
        val text = format(layout)
        // Beside the real file and renamed over it, so an interrupted write cannot leave half a layout.
        runCatching {
            val target = File(filesDir, "layout.conf")
            val tmp = File(filesDir, "layout.conf.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }
    }

    fun format(layout: LayoutFile): String = buildString {
        appendLine("# Escrito por el editor de posiciones. <nombre>=<x>,<y>,<tamano>,<visible>[,<tecla>,<texto>,<pantallas>]")
        for ((name, p) in layout.placements) {
            val slot = name.removePrefix("custom").toIntOrNull()?.takeIf { name.startsWith("custom") }
            if (slot != null) {
                // A custom button is only written together with its definition; a placement on its
                // own would be ignored by the engine anyway.
                val custom = layout.customs[slot] ?: continue
                appendLine("$name=${p.cx},${p.cy},${p.scale},${if (p.visible) 1 else 0},${custom.key},${cleanLabel(custom.label).ifEmpty { "?" }},${custom.contexts}")
            } else {
                appendLine("$name=${p.cx},${p.cy},${p.scale},${if (p.visible) 1 else 0}")
            }
        }
    }
}

/** Named copies of the whole layout (positions and custom buttons), so a setup can be swapped for another. */
object LayoutProfiles {
    private fun dir(filesDir: File) = File(filesDir, "layouts")

    /** Letters, digits, spaces, hyphens and underscores, so the name is safe as a file name. */
    fun clean(name: String): String = name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().take(24)

    fun list(filesDir: File): List<String> =
        dir(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".conf") }.orEmpty().map { it.name.removeSuffix(".conf") }.sorted()

    fun save(filesDir: File, name: String, layout: LayoutFile): Boolean {
        val safe = clean(name)
        if (safe.isEmpty()) return false
        return runCatching {
            dir(filesDir).mkdirs()
            File(dir(filesDir), "$safe.conf").writeText(Layout.format(layout))
        }.isSuccess
    }

    fun load(filesDir: File, name: String): LayoutFile? {
        val file = File(dir(filesDir), "${clean(name)}.conf")
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return Layout.parse(text)
    }

    fun delete(filesDir: File, name: String) {
        File(dir(filesDir), "${clean(name)}.conf").delete()
    }
}

/**
 * Ready-made arrangements of the game's own buttons. They only move and resize those buttons: the
 * buttons the player made are left where they are, and so is which of the game's buttons are hidden.
 * A preset is worked out from the built-in layout, never from the current one, so applying it twice
 * gives the same result instead of mirroring back.
 */
object LayoutPresets {
    class Preset(val title: String, val description: String, val mirror: Boolean, val scale: Float)

    val ALL: List<Preset> = listOf(
        Preset("Estandar", "La disposicion original", mirror = false, scale = 1f),
        Preset("Zurdo", "Joystick a la derecha y botones a la izquierda", mirror = true, scale = 1f),
        Preset("Compacto", "Todos los botones un 15% mas pequenos", mirror = false, scale = 0.85f),
        Preset("Grande", "Todos los botones un 15% mas grandes", mirror = false, scale = 1.15f),
        Preset("Zurdo compacto", "Zurdo con los botones un 15% mas pequenos", mirror = true, scale = 0.85f),
    )

    /**
     * The placements for [preset]. [current] only supplies which buttons are hidden. The plain
     * original layout is an empty map, which is what the engine reads as "no changes".
     */
    fun placements(defaults: Layout.Defaults, preset: Preset, current: Map<String, Placement>): Map<String, Placement> {
        if (!preset.mirror && preset.scale == 1f) {
            // Back to the original positions, but a button the player hid stays hidden.
            return current.filter { (name, p) -> !p.visible && !name.startsWith("custom") }
                .mapValues { (name, p) ->
                    val spec = (defaults.controls + defaults.joystick).firstOrNull { it.name == name } ?: return@mapValues p
                    Placement(spec.cx, spec.cy, 1f, false)
                }
        }
        return (defaults.controls + defaults.joystick).associate { spec ->
            spec.name to Placement(
                cx = if (preset.mirror) 1f - spec.cx else spec.cx,
                cy = spec.cy,
                scale = preset.scale,
                visible = current[spec.name]?.visible ?: true,
            )
        }
    }
}
