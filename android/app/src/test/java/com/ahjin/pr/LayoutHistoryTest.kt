package com.ahjin.pr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutHistoryTest {
    private fun layout(x: Float, key: String = "Z") = LayoutFile(
        placements = mapOf(
            "z" to Placement(x, 0.8f, 1f, true),
            "custom1" to Placement(0.5f, 0.5f, 1f, true),
        ),
        customs = mapOf(1 to CustomButton(1, key, key, Layout.WORLD)),
    )

    @Test
    fun `undo and redo restore complete snapshots`() {
        val first = layout(0.8f)
        val second = layout(0.7f, "X")
        val third = layout(0.6f, "Q")
        val history = LayoutHistory(first)
        history.change(second)
        history.change(third)

        assertEquals(second, history.undo())
        assertEquals(first, history.undo())
        assertNull(history.undo())
        assertEquals(second, history.redo())
        assertEquals(third, history.redo())
        assertNull(history.redo())
    }

    @Test
    fun `a new change invalidates redo`() {
        val first = layout(0.8f)
        val history = LayoutHistory(first)
        history.change(layout(0.7f))
        history.undo()
        assertTrue(history.canRedo)

        history.change(layout(0.6f, "Q"))
        assertFalse(history.canRedo)
        assertNull(history.redo())
        assertEquals("Q", history.current.customs.getValue(1).key)
    }

    @Test
    fun `a no-op does not create an undo step`() {
        val first = layout(0.8f)
        val history = LayoutHistory(first)
        history.change(first.copy())
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `custom button definitions and visibility are restored together`() {
        val first = layout(0.8f)
        val changed = LayoutFile(
            placements = mapOf("z" to Placement(0.2f, 0.3f, 1.4f, false)),
            customs = mapOf(2 to CustomButton(2, "X:lock", "~run", Layout.WORLD)),
        )
        val history = LayoutHistory(first)
        history.change(changed)
        assertEquals(first, history.undo())
        assertEquals("Z", history.current.customs.getValue(1).key)
        assertEquals(changed, history.redo())
        assertFalse(history.current.placements.getValue("z").visible)
        assertEquals("X:lock", history.current.customs.getValue(2).key)
    }

    @Test
    fun `the history is bounded and drops the oldest snapshot`() {
        val first = layout(0.8f)
        val history = LayoutHistory(first, limit = 2)
        history.change(layout(0.7f))
        history.change(layout(0.6f))
        history.change(layout(0.5f))

        assertEquals(0.6f, history.undo()?.placements?.getValue("z")?.cx)
        assertEquals(0.7f, history.undo()?.placements?.getValue("z")?.cx)
        assertNull(history.undo())
    }
}
