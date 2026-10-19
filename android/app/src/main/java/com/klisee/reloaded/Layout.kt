package com.klisee.reloaded

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
}

/** What `layout.conf` holds: where each button is, and the buttons the player made. */
data class LayoutFile(val placements: Map<String, Placement>, val customs: Map<Int, CustomButton>)

/** The movement stick: radius is a fraction of the screen *height*. */
const val JOYSTICK = "joystick"

object Layout {
    const val WORLD = 2
    const val BATTLE = 4
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

    /** A short name for the key, which is also what a new button says on its face. */
    fun defaultLabel(key: String): String = when (key) {
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
                val key = parts.getOrNull(4)?.uppercase()?.takeIf { it in CUSTOM_KEYS } ?: continue
                val label = parts.getOrNull(5) ?: continue
                val contexts = parts.getOrNull(6)?.toIntOrNull()?.takeIf { it in 1..255 } ?: continue
                customs[slot] = CustomButton(slot, key, label.take(3).uppercase(), contexts)
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
                appendLine("$name=${p.cx},${p.cy},${p.scale},${if (p.visible) 1 else 0},${custom.key},${custom.label.filter { it != ',' && it != '=' }.ifEmpty { "?" }},${custom.contexts}")
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
