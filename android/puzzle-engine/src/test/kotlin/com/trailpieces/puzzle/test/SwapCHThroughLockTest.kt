package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Near-complete 2×4: only C and H swapped. Same-size swap must show mid-drag;
 * release only parks.
 * ```
 * A B
 * H D
 * E F
 * G C
 * ```
 */
class SwapCHThroughLockTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "swap-ch",
        title = "Swap C/H",
        cols = 2,
        rows = 4,
        puzzleWidth = 200,
        puzzleHeight = 400,
        tiles = listOf(
            PuzzleTile(0, GridPos(0, 0), "a"),
            PuzzleTile(1, GridPos(0, 1), "b"),
            PuzzleTile(2, GridPos(1, 0), "c"),
            PuzzleTile(3, GridPos(1, 1), "d"),
            PuzzleTile(4, GridPos(2, 0), "e"),
            PuzzleTile(5, GridPos(2, 1), "f"),
            PuzzleTile(6, GridPos(3, 0), "g"),
            PuzzleTile(7, GridPos(3, 1), "h"),
        ),
    )

    private fun swappedBoard(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 4,
        placements = mapOf(
            GridPos(0, 0) to 0, GridPos(0, 1) to 1,
            GridPos(1, 0) to 7, GridPos(1, 1) to 3,
            GridPos(2, 0) to 4, GridPos(2, 1) to 5,
            GridPos(3, 0) to 6, GridPos(3, 1) to 2,
        ),
    )

    private fun assertNearCompleteLocks(board: PuzzleBoard) {
        assertEquals(setOf(0, 1, 3, 4, 5, 6), board.componentContaining(0), "ABDEFG")
        assertEquals(setOf(7), board.componentContaining(7), "H loose")
        assertEquals(setOf(2), board.componentContaining(2), "C loose")
    }

    private fun assertHomesOnBoard(board: PuzzleBoard) {
        BoardAssert.assertTileAt(board.grid, GridPos(0, 0), 0)
        BoardAssert.assertTileAt(board.grid, GridPos(0, 1), 1)
        BoardAssert.assertTileAt(board.grid, GridPos(1, 0), 2)
        BoardAssert.assertTileAt(board.grid, GridPos(1, 1), 3)
        BoardAssert.assertTileAt(board.grid, GridPos(2, 0), 4)
        BoardAssert.assertTileAt(board.grid, GridPos(2, 1), 5)
        BoardAssert.assertTileAt(board.grid, GridPos(3, 0), 6)
        BoardAssert.assertTileAt(board.grid, GridPos(3, 1), 7)
    }

    @Test
    fun hOntoC_swapsMidDrag_releaseParks() {
        val board = swappedBoard()
        assertNearCompleteLocks(board)

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(1, 0))) // H
        engine.moveFinger(Vec2(0f, 400f), cell, cell)
        engine.moveFinger(Vec2(200f, 0f), cell, cell)

        val mid = engine.drag!!
        assertEquals(GridPos(3, 1), mid.committedAnchor, "mid-drag: H at C's former cell (H home)")
        assertEquals(GridPos(1, 0), mid.grid.slotOfOrNull(2), "mid-drag: C already on home")

        assertHomesOnBoard(engine.endDrag())
    }

    /** Mid-drag path overshoots / doesn't land completing swap for this gesture yet. */
    @Ignore("Needs mid-drag completing swap for this diagonal path — not a release upgrade")
    @Test
    fun cOntoH_swapsMidDrag_releaseParks() {
        val board = swappedBoard()
        assertNearCompleteLocks(board)

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(3, 1))) // C
        engine.moveFinger(Vec2(0f, -400f), cell, cell)
        engine.moveFinger(Vec2(-200f, 0f), cell, cell)

        val mid = engine.drag!!
        assertEquals(GridPos(1, 0), mid.committedAnchor, "mid-drag: C at H home")
        assertEquals(GridPos(3, 1), mid.grid.slotOfOrNull(7), "mid-drag: H already on home")

        assertHomesOnBoard(engine.endDrag())
    }
}
