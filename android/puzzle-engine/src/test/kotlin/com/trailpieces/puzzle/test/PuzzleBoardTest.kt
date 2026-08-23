package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PuzzleBoardTest {

    private val manifest = PuzzleFixtures.miniManifest()

    @Test
    fun solvedBoardReportsSolved() {
        assertTrue(PuzzleFixtures.solvedBoard(manifest).isSolved)
        assertFalse(PuzzleFixtures.lockedLeftColumnBoard(manifest).isSolved)
    }

    @Test
    fun beginDragGroupedLiftsEntireLockComponent() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(1, 0), grouped = true)
        assertNotNull(session)
        assertEquals(setOf(0, 2, 4), session.liftedTileIds)
        assertEquals(GridPos(0, 0), session.startAnchor)
        assertEquals(
            setOf(GridPos(0, 0), GridPos(1, 0), GridPos(2, 0)),
            session.grid.emptySlots(),
        )
        // Resting tiles untouched
        BoardAssert.assertTileAt(session.grid, GridPos(0, 1), 5)
        BoardAssert.assertTileAt(session.grid, GridPos(1, 1), 1)
        BoardAssert.assertTileAt(session.grid, GridPos(2, 1), 3)
    }

    @Test
    fun beginDragUngroupedPeelsSingleTileFromLock() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(1, 0), grouped = false)
        assertNotNull(session)
        assertEquals(setOf(2), session.liftedTileIds)
        assertEquals(setOf(GridPos(1, 0)), session.grid.emptySlots())
        BoardAssert.assertTileAt(session.grid, GridPos(0, 0), 0)
        BoardAssert.assertTileAt(session.grid, GridPos(2, 0), 4)
    }

    @Test
    fun beginDragOnEmptyOrOobReturnsNull() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val withHole = board.beginDrag(GridPos(0, 0), grouped = false)!!
        // After lift, that slot is empty on the session grid — but beginDrag is on board
        assertNull(board.beginDrag(GridPos(-1, 0)))
        // Empty slot on a board with incomplete placement
        val sparse = PuzzleFixtures.boardWithPlacements(
            manifest,
            mapOf(GridPos(0, 0) to 0),
        )
        assertNull(sparse.beginDrag(GridPos(1, 1)))
        assertNotNull(withHole)
    }

    @Test
    fun shapeOffsetsRelativeToAnchor() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        assertEquals(GridPos(0, 0), session.shapeOffsets[0])
        assertEquals(GridPos(1, 0), session.shapeOffsets[2])
        assertEquals(GridPos(2, 0), session.shapeOffsets[4])
        assertEquals(
            setOf(GridPos(0, 0), GridPos(1, 0), GridPos(2, 0)),
            session.targetSlots,
        )
    }
}

class DragSessionTest {

    private val manifest = PuzzleFixtures.miniManifest()

    @Test
    fun tryPushAdvancesAnchorAndMovesRestingTile() {
        val board = PuzzleFixtures.boardFromRowMajor(
            manifest,
            listOf(2, 1, 0, 3, 4, 5),
        )
        val session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        val pushed = session.tryPush(AxisDirection.Down)
        assertNotNull(pushed)
        assertEquals(GridPos(1, 0), pushed.committedAnchor)
        BoardAssert.assertTileAt(pushed.grid, GridPos(0, 0), 0)
        BoardAssert.assertEmpty(pushed.grid, GridPos(1, 0))
    }

    @Test
    fun tryPushBlockedLeavesSessionUnchangedViaNull() {
        val board = PuzzleFixtures.boardFromRowMajor(manifest, listOf(0, 1, 2, 3, 4, 5))
        val session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        assertNull(session.tryPush(AxisDirection.Up))
        assertEquals(session.startAnchor, session.committedAnchor)
    }

    @Test
    fun settlePlacesLiftedTilesAndRecomputesLocks() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        val pushed = session.tryPush(AxisDirection.Right)!!
        val settled = pushed.settle(manifest)
        BoardAssert.assertOccupancy(
            settled.grid,
            mapOf(
                GridPos(0, 0) to 5,
                GridPos(0, 1) to 0,
                GridPos(1, 0) to 1,
                GridPos(1, 1) to 2,
                GridPos(2, 0) to 3,
                GridPos(2, 1) to 4,
            ),
        )
        // Vertical strip moved to col 1 — still correct relative → still locked
        assertEquals(setOf(0, 2, 4), settled.componentContaining(0))
    }

    @Test
    fun settleOutOfBoundsThrows() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        // Force an illegal committed anchor off the right edge
        val illegal = session.copy(committedAnchor = GridPos(0, 2))
        assertFailsWith<IllegalArgumentException> { illegal.settle(manifest) }
    }
}
