package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dump regression (default 2×12): singleton W at X (11,1); home W (11,0) blocked by
 * {ACE} column + rigid mass. Drag W left onto home must cutline-insert a row,
 * repack below, land W at (11,0) with empty (11,1).
 *
 * ```
 * …
 * A X     row 9
 * C B     row 10
 * E W     row 11 — W lifted from X; E occupies home slot W
 * ```
 */
class WHomeCutlineDumpTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "w-home-dump",
        title = "W home dump",
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

    private fun dumpBoard(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 12,
        placements = mapOf(
            GridPos(0, 0) to 3, GridPos(0, 1) to 5,
            GridPos(1, 0) to 6, GridPos(1, 1) to 7,
            GridPos(2, 0) to 8, GridPos(2, 1) to 9,
            GridPos(3, 0) to 10, GridPos(3, 1) to 11,
            GridPos(4, 0) to 12, GridPos(4, 1) to 13,
            GridPos(5, 0) to 14, GridPos(5, 1) to 15,
            GridPos(6, 0) to 16, GridPos(6, 1) to 17,
            GridPos(7, 0) to 18, GridPos(7, 1) to 19,
            GridPos(8, 0) to 20, GridPos(8, 1) to 21,
            GridPos(9, 0) to 0, GridPos(9, 1) to 23,
            GridPos(10, 0) to 2, GridPos(10, 1) to 1,
            GridPos(11, 0) to 4, GridPos(11, 1) to 22,
        ),
    )

    @Test
    fun locks_wSingleton_aceColumnBelowMass() {
        val b = dumpBoard()
        assertEquals(setOf(22), b.componentContaining(22), "W loose")
        assertEquals(setOf(0, 2, 4), b.componentContaining(0), "ACE")
        assertTrue(b.componentContaining(5).size > 10, "rigid mass above")
    }

    /** W left onto home W (11,0) — cutline + insert row, not blocked by ACE / mass. */
    @Test
    fun wOntoHome_leftFromX_cutlineInsertsRow_repacksBelow() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(11, 1))) // W at X
        engine.moveFinger(Vec2(-120f, 0f), cell, cell)
        val settled = engine.endDrag()

        BoardAssert.assertTileAt(settled.grid, GridPos(11, 0), 22) // W home
        BoardAssert.assertEmpty(settled.grid, GridPos(11, 1)) // original W hole
        assertTrue(
            settled.grid.rows >= 13,
            "Expected insert-row growth after cutline, got ${settled.grid.rows}",
        )
    }

    /**
     * App gesture: drag W toward A (9,0) in the ACE column — must still stick W at
     * home (11,0) with cutline insert-row on release.
     */
    @Test
    fun wTowardA_upLeftFinger_sticksAtHomeWithCutline() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(11, 1))) // W
        engine.moveFinger(Vec2(-110f, -220f), cell, cell) // toward A at (9,0)
        val settled = engine.endDrag()

        BoardAssert.assertTileAt(settled.grid, GridPos(11, 0), 22)
        BoardAssert.assertEmpty(settled.grid, GridPos(11, 1))
        assertTrue(settled.grid.rows >= 13, "Expected insert-row, got ${settled.grid.rows}")
    }

    @Test
    fun wTowardA_midDragUsesAxisPushNotHomeCutline() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(11, 1)))
        engine.moveFinger(Vec2(-110f, -220f), cell, cell)
        assertNotNull(engine.drag)
        assertEquals(
            12,
            engine.drag!!.grid.rows,
            "Home cutline must not insert-row mid-drag; settle handles home cutline",
        )
    }
}
