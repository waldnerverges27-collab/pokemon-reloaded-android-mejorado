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

    /** What `layout.conf` says. A missing or damaged file is the same as no changes. */
    fun read(filesDir: File): Map<String, Placement> {
        val file = File(filesDir, "layout.conf")
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, Placement>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val name = trimmed.substringBefore('=', "").trim()
            val parts = trimmed.substringAfter('=', "").split(',').map { it.trim() }
            if (name.isEmpty() || parts.size < 3) continue
            val cx = parts[0].toFloatOrNull() ?: continue
            val cy = parts[1].toFloatOrNull() ?: continue
            val scale = parts[2].toFloatOrNull() ?: continue
            val visible = parts.getOrNull(3) !in listOf("0", "false", "no")
            result[name] = Placement(cx, cy, scale, visible)
        }
        return result
    }

    /** Writes only the buttons that differ from the built-in layout, in the engine's format. */
    fun write(filesDir: File, placements: Map<String, Placement>) {
        val text = buildString {
            appendLine("# Escrito por el editor de posiciones. <nombre>=<x>,<y>,<tamano>,<visible>")
            for ((name, p) in placements) {
                appendLine("$name=${p.cx},${p.cy},${p.scale},${if (p.visible) 1 else 0}")
            }
        }
        runCatching { File(filesDir, "layout.conf").writeText(text) }
    }
}
