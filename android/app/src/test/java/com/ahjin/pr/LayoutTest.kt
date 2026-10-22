package com.ahjin.pr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun defaults() = Layout.Defaults(
        controls = listOf(
            ControlSpec("z", "Z", 0.865f, 0.851f, 0.033f, 2),
            ControlSpec("x", "X", 0.933f, 0.700f, 0.033f, 2),
        ),
        joystick = ControlSpec(JOYSTICK, "", 0.135f, 0.7f, 0.2f, 2),
    )

    @Test
    fun `icons are kept as they are written and text is cleaned`() {
        assertEquals("~star", Layout.cleanLabel("~star"))
        assertEquals("~star", Layout.cleanLabel("~STAR"))
        assertEquals("ABC", Layout.cleanLabel("abcdef"))
        assertEquals("AB", Layout.cleanLabel("a,b="))
        assertEquals("NOP", Layout.cleanLabel("~nope"))
        assertEquals("star", CustomButton(1, "Z", "~star", 2).icon)
        assertNull(CustomButton(1, "Z", "STA", 2).icon)
        assertNull(CustomButton(1, "Z", "~nope", 2).icon)
    }

    @Test
    fun `a custom button with an icon survives writing and reading`() {
        val layout = LayoutFile(
            placements = mapOf("custom1" to Placement(0.4f, 0.3f, 1.2f, true), "z" to Placement(0.5f, 0.5f)),
            customs = mapOf(1 to CustomButton(1, "Z", "~heart", 2)),
        )
        val text = Layout.format(layout)
        assertTrue(text, "custom1=0.4,0.3,1.2,1,Z,~heart,2" in text)
        val back = Layout.parse(text)
        assertEquals("~heart", back.customs[1]?.label)
        assertEquals("heart", back.customs[1]?.icon)
        assertEquals(Placement(0.4f, 0.3f, 1.2f, true), back.placements["custom1"])
    }

    @Test
    fun `every icon the editor offers has a name and none repeats`() {
        for (icon in Layout.ICONS) assertTrue(icon, Layout.ICON_TITLES[icon]?.isNotEmpty() == true)
        assertEquals(Layout.ICONS.toSet(), Layout.ICON_TITLES.keys)
        assertEquals(Layout.ICONS.size, Layout.ICONS.toSet().size)
    }

    @Test
    fun `a damaged layout line is skipped instead of breaking the rest`() {
        val parsed = Layout.parse("z=0.5,0.5,1,1\nx=oops\ncustom2=0.1,0.1,1,1,Z\ncustom3=0.1,0.1,1,1,Q,AB,2\n")
        assertEquals(setOf("z", "custom3"), parsed.placements.keys)
        assertEquals(setOf(3), parsed.customs.keys)
    }

    @Test
    fun `the left handed preset mirrors every button and the stick`() {
        val mirrored = LayoutPresets.placements(defaults(), LayoutPresets.ALL.first { it.title == "Zurdo" }, emptyMap())
        assertEquals(1f - 0.865f, mirrored.getValue("z").cx, 1e-6f)
        assertEquals(0.851f, mirrored.getValue("z").cy, 1e-6f)
        assertEquals(1f - 0.135f, mirrored.getValue(JOYSTICK).cx, 1e-6f)
    }

    @Test
    fun `a preset gives the same result when applied twice`() {
        for (preset in LayoutPresets.ALL) {
            val once = LayoutPresets.placements(defaults(), preset, emptyMap())
            val twice = LayoutPresets.placements(defaults(), preset, once)
            assertEquals(preset.title, once, twice)
        }
    }

    @Test
    fun `presets keep hidden buttons hidden and stay inside the screen and the engine's size limits`() {
        val hidden = mapOf("x" to Placement(0.933f, 0.7f, 1f, visible = false))
        for (preset in LayoutPresets.ALL) {
            val result = LayoutPresets.placements(defaults(), preset, hidden)
            assertFalse(preset.title, result["x"]?.visible ?: true)
            for ((_, p) in result) {
                assertTrue(p.cx in 0f..1f && p.cy in 0f..1f)
                assertTrue(p.scale in 0.4f..2.5f)
            }
        }
    }

    @Test
    fun `the standard preset is the engine's no changes`() {
        val standard = LayoutPresets.placements(defaults(), LayoutPresets.ALL.first { it.title == "Estandar" }, emptyMap())
        assertTrue(standard.isEmpty())
    }

    @Test
    fun `buttons of the game show the picture of what they do in each screen`() {
        // Out of battle the main four are the actions; in battle they are the four attacks, so letters.
        assertEquals("bike", Layout.builtinIcon("v", Layout.WORLD, true))
        assertNull(Layout.builtinIcon("v", Layout.BATTLE, true))
        assertEquals("map", Layout.builtinIcon("m", Layout.WORLD, true))
        assertEquals("star", Layout.builtinIcon("m", Layout.BATTLE, true))
        assertEquals("smile", Layout.builtinIcon("e", Layout.WORLD, true))
        assertEquals("exit", Layout.builtinIcon("e", Layout.SAFARI, true))
        assertEquals("chat", Layout.builtinIcon("t", Layout.WORLD, true))
        assertEquals("clock", Layout.builtinIcon("t", Layout.BATTLE, true))
        assertEquals("bag", Layout.builtinIcon("o", Layout.WORLD, true))
        // Turning the setting off leaves only the icons the game's buttons always had.
        assertNull(Layout.builtinIcon("o", Layout.WORLD, false))
        assertEquals("menu", Layout.builtinIcon("return", Layout.WORLD, false))
        assertEquals("ball", Layout.builtinIcon("b", Layout.BATTLE, false))
        // Every picture used exists in the list the editor and the engine share.
        for (name in listOf("z", "x", "c", "v", "space", "o", "m", "e", "t", "l", "p", "n", "a", "s", "return", "escape", "b", "sys_qsave", "sys_qload", "sys_ffwd")) {
            for (context in listOf(Layout.WORLD, Layout.BATTLE, Layout.SAFARI)) {
                Layout.builtinIcon(name, context, true)?.let { assertTrue("$name -> $it", it in Layout.ICONS) }
            }
        }
    }

    @Test
    fun `a key says how it acts and is cleaned the way the engine reads it`() {
        assertEquals("Z", Layout.normalizeKey("z"))
        assertEquals("Z:turbo", Layout.normalizeKey(" z:TURBO "))
        assertEquals("X:lock", Layout.normalizeKey("x:Lock"))
        assertEquals("F5:lock", Layout.normalizeKey("f5:lock"))
        assertEquals("@speed", Layout.normalizeKey("@SPEED"))
        assertEquals("@types", Layout.normalizeKey("@types"))
        assertNull(Layout.normalizeKey("Z:"))
        assertNull(Layout.normalizeKey("Z:hold"))
        assertEquals("Q:turbo", Layout.normalizeKey("q:turbo"))
        assertNull(Layout.normalizeKey("!:turbo"))
        assertNull(Layout.normalizeKey("@nothing"))
        assertNull(Layout.normalizeKey("@speed:lock"))
    }

    @Test
    fun `a button knows its key and its mode`() {
        val plain = CustomButton(1, "Z", "Z", 2)
        assertEquals("Z" to Layout.MODE_PRESS, plain.baseKey to plain.mode)
        val turbo = CustomButton(1, "Z:turbo", "TUR", 2)
        assertEquals("Z" to Layout.MODE_TURBO, turbo.baseKey to turbo.mode)
        val lock = CustomButton(1, "X:lock", "~run", 2)
        assertEquals("X" to Layout.MODE_LOCK, lock.baseKey to lock.mode)
        val action = CustomButton(1, "@speed", "~forward", 2)
        assertEquals("@speed" to Layout.MODE_ACTION, action.baseKey to action.mode)
        // Changing the key keeps how it acts, and an action has nothing to keep.
        assertEquals("Q:lock", Layout.keyToken("Q", Layout.MODE_LOCK))
        assertEquals("Q", Layout.keyToken("Q", Layout.MODE_PRESS))
        assertEquals("@types", Layout.keyToken("@types", Layout.MODE_LOCK))
        assertEquals("VEL", Layout.defaultLabel("@speed"))
        assertEquals("TIP", Layout.defaultLabel("@types"))
        assertEquals("ENT", Layout.defaultLabel("RETURN:turbo"))
    }

    @Test
    fun `turbo, lock and action buttons survive writing and reading, and a bad mode is dropped`() {
        val customs = mapOf(
            1 to CustomButton(1, "X:lock", "~run", Layout.WORLD),
            2 to CustomButton(2, "Z:turbo", "TUR", Layout.WORLD or Layout.DIALOG),
            3 to CustomButton(3, "@types", "~chart", Layout.BATTLE),
        )
        val layout = LayoutFile(customs.keys.associate { "custom$it" to Placement(0.5f, 0.5f) }, customs)
        val text = Layout.format(layout)
        assertTrue(text, "custom1=0.5,0.5,1.0,1,X:lock,~run,2" in text)
        assertTrue(text, "custom3=0.5,0.5,1.0,1,@types,~chart,4" in text)
        val back = Layout.parse(text)
        assertEquals(customs, back.customs)
        val damaged = Layout.parse("custom1=0.5,0.5,1,1,Z:fast,AB,2\ncustom2=0.5,0.5,1,1,z:lock,AB,2\n")
        assertEquals(setOf(2), damaged.customs.keys)
        assertEquals("Z:lock", damaged.customs[2]?.key)
    }
}
