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
 * Dump regression: singleton U at N (6,1); home U (10,0). Drag toward col-0 / home
 * must cutline-insert a separator row, repack below, land U at home with empty
 * (10,1) and empty original hole N (6,1).
 */
class UHomeCutlineDumpTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "u-home-dump",
        title = "U home dump",
        cols = 2,
        rows = 12,
        puzzleWidth = 200,
        puzzleHeight = 1200,
        tiles = listOf(
            PuzzleTile(0, GridPos(0, 0), "a"),
            PuzzleTile(1, GridPos(0, 1), "b"),
            PuzzleTile(2, GridPos(1, 0), "c"),
            PuzzleTile(3, GridPos(1, 1), "d"),
            PuzzleTile(4, GridPos(2, 0), "e"),
            PuzzleTile(5, GridPos(2, 1), "f"),
            PuzzleTile(6, GridPos(3, 0), "g"),
            PuzzleTile(7, GridPos(3, 1), "h"),
            PuzzleTile(8, GridPos(4, 0), "i"),
            PuzzleTile(9, GridPos(4, 1), "j"),
            PuzzleTile(10, GridPos(5, 0), "k"),
            PuzzleTile(11, GridPos(5, 1), "l"),
            PuzzleTile(12, GridPos(6, 0), "m"),
            PuzzleTile(13, GridPos(6, 1), "n"),
            PuzzleTile(14, GridPos(7, 0), "o"),
            PuzzleTile(15, GridPos(7, 1), "p"),
            PuzzleTile(16, GridPos(8, 0), "q"),
            PuzzleTile(17, GridPos(8, 1), "r"),
            PuzzleTile(18, GridPos(9, 0), "s"),
            PuzzleTile(19, GridPos(9, 1), "t"),
            PuzzleTile(20, GridPos(10, 0), "u"),
            PuzzleTile(21, GridPos(10, 1), "v"),
            PuzzleTile(22, GridPos(11, 0), "w"),
            PuzzleTile(23, GridPos(11, 1), "x"),
        ),
    )

    /** Exact dump layout (home letters in comments). */
    private fun dumpBoard(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 12,
        placements = mapOf(
            GridPos(0, 0) to 1, GridPos(0, 1) to 3, // B D
            GridPos(1, 0) to 0, GridPos(1, 1) to 5, // A F
            GridPos(2, 0) to 10, GridPos(2, 1) to 7, // K H
            GridPos(3, 0) to 12, GridPos(3, 1) to 9, // M J
            GridPos(4, 0) to 14, GridPos(4, 1) to 15, // O P
            GridPos(5, 0) to 16, GridPos(5, 1) to 17, // Q R
            GridPos(6, 0) to 18, GridPos(6, 1) to 20, // S U
            GridPos(7, 0) to 6, GridPos(7, 1) to 19, // G T
            GridPos(8, 0) to 8, GridPos(8, 1) to 21, // I V
            GridPos(9, 0) to 4, GridPos(9, 1) to 23, // E X
            GridPos(10, 0) to 2, GridPos(10, 1) to 11, // C L
            GridPos(11, 0) to 22, GridPos(11, 1) to 13, // W N
        ),
    )

    @Test
    fun locks_uSingleton_atN() {
        val b = dumpBoard()
        assertEquals(setOf(20), b.componentContaining(20))
        assertEquals(GridPos(6, 1), b.grid.slotOf(20))
        assertEquals(GridPos(10, 0), manifest.tileOrNull(20)!!.home)
    }

    /** Drag U toward col-0 stack (past G at O) onto home U (10,0). */
    @Test
    fun uOntoHome_cutlineInsertsRow_repacksBelow() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(6, 1))) // U at N
        // Left into col 0, down toward home row 10.
        engine.moveFinger(Vec2(-120f, 0f), cell, cell)
        engine.moveFinger(Vec2(0f, 450f), cell, cell)
        val settled = engine.endDrag()

        // Debug-friendly assertions first
        BoardAssert.assertTileAt(settled.grid, GridPos(10, 0), 20) // U home
        BoardAssert.assertEmpty(settled.grid, GridPos(10, 1)) // empty right of U
        BoardAssert.assertEmpty(settled.grid, GridPos(6, 1)) // original U hole
        assertTrue(settled.grid.rows >= 13, "Expected insert-row growth, got ${settled.grid.rows}")
    }
}
