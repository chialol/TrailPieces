package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DragEngineTest {

    private val manifest = PuzzleFixtures.miniManifest()
    private val cell = 100f

    private fun engine(board: PuzzleBoard = PuzzleFixtures.lockedLeftColumnBoard(manifest)) =
        DragEngine(manifest, board)

    @Test
    fun startDragFailsWhenSolved() {
        val e = engine(PuzzleFixtures.solvedBoard(manifest))
        assertTrue(e.isSolved)
        assertFalse(e.startDrag(GridPos(0, 0)))
        assertNull(e.drag)
    }

    @Test
    fun startDragFailsWhenAlreadyDragging() {
        val e = engine()
        assertTrue(e.startDrag(GridPos(0, 1)))
        assertFalse(e.startDrag(GridPos(1, 1)))
    }

    @Test
    fun startDragFailsOnEmptyOrOob() {
        val e = engine(
            PuzzleFixtures.boardWithPlacements(manifest, mapOf(GridPos(0, 0) to 0)),
        )
        assertFalse(e.startDrag(GridPos(1, 1)))
        assertFalse(e.startDrag(GridPos(-1, 0)))
    }

    @Test
    fun belowThresholdDoesNotPush() {
        val e = engine(
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        e.moveFinger(Vec2(0f, 50f), cell, cell) // exactly 0.5 → need signed > 0.5
        assertEquals(GridPos(0, 0), e.drag!!.committedAnchor)
    }

    @Test
    fun pastThresholdPushesOnce() {
        val e = engine(
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        e.moveFinger(Vec2(0f, 51f), cell, cell)
        assertEquals(GridPos(1, 0), e.drag!!.committedAnchor)
        BoardAssert.assertTileAt(e.drag!!.grid, GridPos(0, 0), 0)
    }

    @Test
    fun largeFingerMovePushesMultipleCells() {
        val e = engine(
            // 5 above unlocked 3 above unlocked 4 — two Down pushes
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(5, 1, 3, 2, 4, 0)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        e.moveFinger(Vec2(0f, 160f), cell, cell)
        assertEquals(GridPos(2, 0), e.drag!!.committedAnchor)
    }

    @Test
    fun directionLockPreventsAxisFlipWithinOneMoveFinger() {
        val e = engine(
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        // Dominant is Down (larger |y|); large +x should not produce a Right push this call
        e.moveFinger(Vec2(80f, 100f), cell, cell)
        assertEquals(GridPos(1, 0), e.drag!!.committedAnchor)
        assertEquals(0, e.drag!!.committedAnchor.col)
    }

    @Test
    fun endDragSettlesOntoCommittedSlots() {
        val e = engine(
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        e.moveFinger(Vec2(0f, 60f), cell, cell)
        val board = e.endDrag()
        assertNull(e.drag)
        BoardAssert.assertTileAt(board.grid, GridPos(1, 0), 2)
        BoardAssert.assertTileAt(board.grid, GridPos(0, 0), 0)
    }

    @Test
    fun cancelDragRestoresPreDragBoard() {
        val initial = PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5))
        val e = engine(initial)
        assertTrue(e.startDrag(GridPos(0, 0)))
        e.moveFinger(Vec2(0f, 60f), cell, cell)
        e.cancelDragSafely()
        assertNull(e.drag)
        BoardAssert.assertOccupancy(e.board.grid, BoardAssert.occupancy(initial.grid))
    }

    @Test
    fun invalidCellSizeOrNanDeltaIsIgnored() {
        val e = engine(
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        e.moveFinger(Vec2(0f, 60f), 0f, cell)
        assertEquals(GridPos(0, 0), e.drag!!.committedAnchor)
        e.moveFinger(Vec2(Float.NaN, 60f), cell, cell)
        assertEquals(GridPos(0, 0), e.drag!!.committedAnchor)
    }

    @Test
    fun reshuffleProducesUnsolvedBoard() {
        val e = engine(PuzzleFixtures.solvedBoard(manifest))
        assertTrue(e.isSolved)
        e.reshuffle()
        assertFalse(e.isSolved)
        assertNull(e.drag)
    }

    @Test
    fun lockedGroupSizeReflectsComponent() {
        val e = engine(PuzzleFixtures.lockedLeftColumnBoard(manifest))
        assertEquals(3, e.lockedGroupSize(0))
        assertEquals(1, e.lockedGroupSize(5))
    }

    @Test
    fun clearFingerDeltaDoesNotUndoCommittedPushes() {
        val e = engine(
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        e.moveFinger(Vec2(0f, 60f), cell, cell)
        assertNotNull(e.drag)
        val anchor = e.drag!!.committedAnchor
        e.clearFingerDelta()
        assertEquals(Vec2.Zero, e.fingerDeltaPx)
        assertEquals(anchor, e.drag!!.committedAnchor)
    }

    @Test
    fun tunnelJumpDoesNotReverseOnSameFingerTravel() {
        // E below locked (A,C); small Up travel tunnels past the pair (2 cells).
        // Finger residual must not flip to Down and undo the tunnel.
        val board = LetterSlots.board("052134")
        val e = engine(board)
        assertTrue(e.startDrag(LetterSlots.E))
        e.moveFinger(Vec2(0f, -60f), cell, cell) // just past one-cell threshold

        val drag = e.drag
        assertNotNull(drag)
        assertEquals(
            LetterSlots.A,
            drag.committedAnchor,
            "Tunnel should land E at A in one push",
        )
        // Same frame's residual was clamped; another tiny Up shouldn't go Down
        e.moveFinger(Vec2(0f, -5f), cell, cell)
        assertEquals(LetterSlots.A, e.drag!!.committedAnchor)
        // Finger should not be behind committed (no negative Up residual)
        assertTrue(
            e.fingerDeltaPx.y <= -2f * cell + 0.1f,
            "fingerDelta should be clamped to ~2 cells up after tunnel, was ${e.fingerDeltaPx}",
        )
    }
}
