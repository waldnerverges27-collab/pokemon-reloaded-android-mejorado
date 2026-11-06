package com.ahjin.pr

/**
 * Snapshot history for the layout draft. A drag or slider movement is committed only when it ends,
 * so one gesture is one undo step. Snapshots include custom button definitions, not just positions.
 */
internal class LayoutHistory(initial: LayoutFile, private val limit: Int = 50) {
    private val undo = ArrayDeque<LayoutFile>()
    private val redo = ArrayDeque<LayoutFile>()

    var current: LayoutFile = initial
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun change(next: LayoutFile) {
        if (next == current) return
        undo.addLast(current)
        while (undo.size > limit) undo.removeFirst()
        current = next
        redo.clear()
    }

    fun undo(): LayoutFile? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        current = previous
        return previous
    }

    fun redo(): LayoutFile? {
        val following = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        current = following
        return following
    }
}
