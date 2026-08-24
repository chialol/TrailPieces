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
        val move = e.moveFinger(Vec2(0f, 51f), cell, cell)
        assertEquals(GridPos(1, 0), e.drag!!.committedAnchor)
        BoardAssert.assertTileAt(e.drag!!.grid, GridPos(0, 0), 0)
        assertTrue(move.hadRestingImpact)
        assertEquals(1, move.restingImpacts)
    }

    @Test
    fun emptyHoleSlideDoesNotReportRestingImpact() {
        // A alone above a persistent empty (B not home-adjacent so A does not
        // lock into a larger group). Down slides the footprint with no resting bump.
        val board = PuzzleFixtures.boardWithPlacements(
            manifest,
            mapOf(
                GridPos(0, 0) to 0,
                GridPos(0, 1) to 5, // F — does not lock with A
                GridPos(1, 1) to 3,
                GridPos(2, 0) to 4,
                GridPos(2, 1) to 1,
                // tile 2 off-board; (1,0) stays empty
            ),
        )
        val e = engine(board)
        assertTrue(e.startDrag(GridPos(0, 0)))
        assertEquals(setOf(0), e.drag!!.liftedTileIds)
        val before = e.drag!!.grid.copyCells()
        val move = e.moveFinger(Vec2(0f, 60f), cell, cell)
        assertEquals(GridPos(1, 0), e.drag!!.committedAnchor)
        assertTrue(before.contentEquals(e.drag!!.grid.copyCells()))
        assertFalse(move.hadRestingImpact)
    }

    @Test
    fun multiCellPushInOneMoveReportsOneRestingImpactPerCommit() {
        val e = engine(
            // 5 above unlocked 3 above unlocked 4 — two Down pushes
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(5, 1, 3, 2, 4, 0)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        val move = e.moveFinger(Vec2(0f, 160f), cell, cell)
        assertEquals(GridPos(2, 0), e.drag!!.committedAnchor)
        assertEquals(2, move.restingImpacts)
    }

    @Test
    fun belowThresholdReportsNoRestingImpact() {
        val e = engine(
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        val move = e.moveFinger(Vec2(0f, 50f), cell, cell)
        assertFalse(move.hadRestingImpact)
    }

    @Test
    fun fingerDeltaStaysUnderFingerAfterSingleCellPush() {
        // Push commits at 0.5 cell while finger is still mid-cell. Clamping
        // fingerDelta up to committed jumps the lift ahead of the finger.
        val e = engine(
            PuzzleFixtures.boardFromRowMajor(manifest, listOf(2, 1, 0, 3, 4, 5)),
        )
        assertTrue(e.startDrag(GridPos(0, 0)))
        e.moveFinger(Vec2(0f, 51f), cell, cell)
        assertEquals(GridPos(1, 0), e.drag!!.committedAnchor)
        assertEquals(
            Vec2(0f, 51f),
            e.fingerDeltaPx,
            "Lifted tiles are drawn at start + fingerDelta; must match true finger travel",
        )
    }

    @Test
    fun fingerDeltaStaysUnderFingerWhenPushingConnectedGroup() {
        // Locked left column (A,C,E) dragging Right into unlocked B.
        val e = engine(PuzzleFixtures.lockedLeftColumnBoard(manifest))
        assertTrue(e.startDrag(LetterSlots.A))
        e.moveFinger(Vec2(60f, 0f), cell, cell)
        assertEquals(GridPos(0, 1), e.drag!!.committedAnchor)
        assertEquals(Vec2(60f, 0f), e.fingerDeltaPx)
        // Further travel continues from true finger, not from a clamped jump.
        e.moveFinger(Vec2(20f, 0f), cell, cell)
        assertEquals(Vec2(80f, 0f), e.fingerDeltaPx)
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
    fun multiCellTunnelWaitsForFingerNearLanding() {
        // E below locked (A,C): Up tunnels 2 cells to A. Must not commit at the
        // single-cell 0.5 threshold — only when residual > 1.5 cells.
        val board = LetterSlots.board("052134")
        val e = engine(board)
        assertTrue(e.startDrag(LetterSlots.E))
        e.moveFinger(Vec2(0f, -60f), cell, cell)
        assertEquals(LetterSlots.E, e.drag!!.committedAnchor)
        assertEquals(Vec2(0f, -60f), e.fingerDeltaPx)

        e.moveFinger(Vec2(0f, -100f), cell, cell) // total -160 > 1.5 cells
        assertEquals(LetterSlots.A, e.drag!!.committedAnchor)
        assertEquals(Vec2(0f, -160f), e.fingerDeltaPx)
    }

    @Test
    fun multiCellTunnelCanReverseWithoutCatchUpClamp() {
        val board = LetterSlots.board("052134")
        val e = engine(board)
        assertTrue(e.startDrag(LetterSlots.E))
        e.moveFinger(Vec2(0f, -160f), cell, cell)
        assertEquals(LetterSlots.A, e.drag!!.committedAnchor)

        // Drag back toward E; look-ahead Down needs > 1.5 cells of residual
        // from committed A (finger y > -50).
        e.moveFinger(Vec2(0f, 120f), cell, cell) // total -40
        assertEquals(LetterSlots.E, e.drag!!.committedAnchor)
        assertEquals(Vec2(0f, -40f), e.fingerDeltaPx)
    }
}
