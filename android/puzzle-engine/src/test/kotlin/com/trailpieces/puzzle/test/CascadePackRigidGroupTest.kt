package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.service.DragSession
import com.trailpieces.puzzle.service.FrozenLockGraph
import com.trailpieces.puzzle.service.LockGroupService
import com.trailpieces.puzzle.service.PuzzleBoard
import com.trailpieces.puzzle.service.PushService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Drag {A,B,D} down on 7-row playfield while {F,H,J} stay rigid.
 * Loose tiles cascade; {F,H,J} packs into col1 when a pocket clears.
 */
class CascadePackRigidGroupTest {

    private val abD = setOf(0, 1, 3)

    private fun pushDown(session: DragSession): DragSession {
        val next = session.tryPush(AxisDirection.Down)
        assertNotNull(next, "Down push should succeed")
        return next
    }

    private fun beginAbDDrag(board: PuzzleBoard = TwelveLetterGrid.cascadePackBoard7()): DragSession {
        val session = board.beginDrag(TwelveLetterGrid.A, liftOverride = abD)!!
        assertEquals(abD, session.liftedTileIds)
        return session
    }

    @Test
    fun push1_FHJShiftsDown_notUpIntoHoles() {
        var session = beginAbDDrag()
        session = pushDown(session)

        BoardAssert.assertTileAt(session.grid, GridPos(3, 1), 5)
        BoardAssert.assertTileAt(session.grid, GridPos(4, 1), 7)
        BoardAssert.assertTileAt(session.grid, GridPos(5, 1), 9)
        assertEquals(GridPos(1, 0), session.committedAnchor)
    }

    @Test
    fun packFHJIntoCol1Top_whenThreeRowsClear() {
        val manifest = TwelveLetterGrid.manifest
        val grid = com.trailpieces.puzzle.model.SlotGrid.empty(manifest.cols, 7).withCells { cells ->
            val place = { pos: GridPos, id: Int -> cells[pos.index(manifest.cols)] = id }
            place(GridPos(0, 0), 10) // K
            place(GridPos(1, 0), 4)  // E
            place(GridPos(3, 0), 2)  // C
            place(GridPos(4, 0), 6)  // G
            place(GridPos(5, 0), 8)  // I — not adjacent to J
            place(GridPos(6, 0), 11) // L
            place(GridPos(4, 1), 5)  // F
            place(GridPos(5, 1), 7)  // H
            place(GridPos(6, 1), 9)  // J
            // (2,0), (2,1), (3,1) empty — lifted {A,B,D} footprint; col1 rows 0–2 pack pocket
        }
        val locks = LockGroupService.compute(grid, manifest)
        val holes = setOf(GridPos(2, 0), GridPos(2, 1), GridPos(3, 1))
        val lifted = abD

        val pushed = PushService.tryPush(
            grid = grid,
            holes = holes,
            liftedTileIds = lifted,
            direction = AxisDirection.Down,
            locks = FrozenLockGraph.freeze(grid, manifest),
        )
        assertNotNull(pushed, "Pack push should succeed")

        BoardAssert.assertTileAt(pushed.grid, GridPos(0, 1), 5)
        BoardAssert.assertTileAt(pushed.grid, GridPos(1, 1), 7)
        BoardAssert.assertTileAt(pushed.grid, GridPos(2, 1), 9)
    }
}
