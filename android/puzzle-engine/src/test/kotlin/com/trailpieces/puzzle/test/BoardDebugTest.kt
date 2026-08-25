package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.service.BoardDebug
import kotlin.test.Test
import kotlin.test.assertTrue

class BoardDebugTest {

    @Test
    fun dumpIncludesGridAndLockGroups() {
        val board = LetterSlots.board("052134")
        val text = BoardDebug.dump(board)
        assertTrue(text.contains("grid"), text)
        assertTrue(text.contains("lock groups"), text)
        assertTrue(text.contains("{AC}") || text.contains("ids=[0, 2]"), text)
        assertTrue(BoardDebug.slotLabel(GridPos(0, 0), 2) == "A")
        assertTrue(BoardDebug.slotLabel(LetterSlots.E, 2) == "E")
    }

    @Test
    fun dumpIncludesDragTraceWhenPresent() {
        val board = LetterSlots.board("052134")
        val engine = com.trailpieces.puzzle.service.DragEngine(board.manifest, board)
        engine.startDrag(GridPos(0, 0))
        engine.moveFinger(com.trailpieces.puzzle.model.Vec2(0f, 60f), 100f, 100f)
        engine.endDrag()
        val text = BoardDebug.dump(engine.board, trace = engine.dragTrace)
        assertTrue(text.contains("drag trace"), text)
        assertTrue(text.contains("start"), text)
        assertTrue(text.contains("release-after-settle"), text)
    }

    @Test
    fun dragTraceKeepsStartAfterManyCommits() {
        val board = LetterSlots.board("052134")
        val engine = com.trailpieces.puzzle.service.DragEngine(board.manifest, board)
        assertTrue(engine.startDrag(GridPos(0, 0)))
        // Flood the ring with distinct commits so the start step is evicted.
        repeat(80) { i ->
            engine.dragTrace.record(
                kind = "commit:aim-empty",
                note = "GridPos(row=0, col=0) → GridPos(row=0, col=0) #$i",
                gridBrief = "  _ B\n  C D\n  E F",
            )
        }
        val text = engine.dragTrace.format()
        assertTrue(text.contains("start"), "start must survive ring eviction:\n$text")
        assertTrue(text.contains("drag start") || text.contains("lift"), text)
    }
}
