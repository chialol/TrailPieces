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
}
