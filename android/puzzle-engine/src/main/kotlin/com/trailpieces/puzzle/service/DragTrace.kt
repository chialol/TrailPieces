package com.trailpieces.puzzle.service

/**
 * One recorded step in a drag session for debug dumps / bug reproduction.
 * [gridBrief] is home-letter occupancy (`.` empty, `_` lift hole).
 */
data class DragTraceStep(
    val kind: String,
    val note: String,
    val gridBrief: String? = null,
)

/**
 * Ring buffer of recent drag steps (start / commits / release). Survives across
 * drags until [clear]. Included in [BoardDebug.dump] when non-empty.
 *
 * The latest [start] is always kept even when the ring evicts older commits, so
 * a dump after a long drag still shows what was lifted.
 */
class DragTrace(private val capacity: Int = 64) {
    private val steps = ArrayDeque<DragTraceStep>(capacity)
    /** Most recent pointer-down; pinned across ring eviction. */
    private var lastStart: DragTraceStep? = null

    fun clear() {
        steps.clear()
        lastStart = null
    }

    fun record(kind: String, note: String, gridBrief: String? = null) {
        val step = DragTraceStep(kind, note, gridBrief)
        if (kind == "start") {
            lastStart = step
        }
        // Skip no-op commits (same note + same grid) — aim-empty spam was
        // blowing away the start line in long drags.
        val prev = steps.lastOrNull()
        if (kind.startsWith("commit:") &&
            prev != null &&
            prev.kind == kind &&
            prev.note == note &&
            prev.gridBrief == gridBrief
        ) {
            return
        }
        if (steps.size >= capacity) {
            val removed = steps.removeFirst()
            // If we evicted the pinned start instance, keep lastStart as the pin.
            if (removed === lastStart) {
                // lastStart stays; it will be prepended in format() if missing.
            }
        }
        steps.addLast(step)
    }

    fun snapshot(): List<DragTraceStep> = steps.toList()

    fun isEmpty(): Boolean = steps.isEmpty() && lastStart == null

    fun format(): String = buildString {
        val list = steps.toList()
        val pinned = lastStart
        val showPinned = pinned != null && list.none { it === pinned }
        if (list.isEmpty() && pinned == null) {
            appendLine("(empty)")
            return@buildString
        }
        var index = 0
        if (showPinned && pinned != null) {
            appendStep(index++, pinned, pinnedMarker = true)
        }
        for (step in list) {
            appendStep(index++, step, pinnedMarker = false)
        }
    }

    private fun StringBuilder.appendStep(index: Int, step: DragTraceStep, pinnedMarker: Boolean) {
        val tag = if (pinnedMarker) " (drag start)" else ""
        appendLine("  [$index] ${step.kind}$tag: ${step.note}")
        step.gridBrief?.lineSequence()?.forEach { line ->
            appendLine("      $line")
        }
    }
}
