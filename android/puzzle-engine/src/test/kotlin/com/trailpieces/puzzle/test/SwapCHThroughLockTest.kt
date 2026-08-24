package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Near-complete 2×4: only C and H are swapped.
 * ```
 * A B
 * H D
 * E F
 * G C
 * ```
 * {A,B,D,E,F,G} rigid; C and H loose.
 * Same-size (1×1) CC onto the other → **full swap** (rule 1).
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

    /** C↔H swapped; everyone else home. */
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

    private fun assertFullySwapped(board: PuzzleBoard) {
        // Solved except the big lock may still hold; C and H are on their homes.
        BoardAssert.assertTileAt(board.grid, GridPos(0, 0), 0)
        BoardAssert.assertTileAt(board.grid, GridPos(0, 1), 1)
        BoardAssert.assertTileAt(board.grid, GridPos(1, 0), 2) // C home
        BoardAssert.assertTileAt(board.grid, GridPos(1, 1), 3)
        BoardAssert.assertTileAt(board.grid, GridPos(2, 0), 4)
        BoardAssert.assertTileAt(board.grid, GridPos(2, 1), 5)
        BoardAssert.assertTileAt(board.grid, GridPos(3, 0), 6)
        BoardAssert.assertTileAt(board.grid, GridPos(3, 1), 7) // H home
    }

    @Test
    fun hCanMoveOntoCsCell() {
        val board = swappedBoard()
        assertNearCompleteLocks(board)

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(1, 0))) // H
        engine.moveFinger(Vec2(0f, 400f), cell, cell)
        engine.moveFinger(Vec2(200f, 0f), cell, cell)
        assertFullySwapped(engine.endDrag())
    }

    @Test
    fun cCanMoveOntoHsCell() {
        val board = swappedBoard()
        assertNearCompleteLocks(board)

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(3, 1))) // C
        engine.moveFinger(Vec2(0f, -400f), cell, cell)
        engine.moveFinger(Vec2(-200f, 0f), cell, cell)
        assertFullySwapped(engine.endDrag())
    }
}
