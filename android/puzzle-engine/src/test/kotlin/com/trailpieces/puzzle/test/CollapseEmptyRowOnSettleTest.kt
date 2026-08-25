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
 * After QRS release, a fully empty row must collapse on settle — not kept as a
 * spacer to block future locks (locking commits on finger-up only).
 */
class CollapseEmptyRowOnSettleTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "collapse-on-settle",
        title = "Collapse on settle",
        cols = 2,
        rows = 12,
        puzzleWidth = 200,
        puzzleHeight = 1200,
        tiles = (0..23).map { id ->
            PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
        },
    )

    /** Post-QRS-settle shape from user dump (13 rows with empty row 11). */
    private fun boardBeforeLDrag(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 13,
        placements = mapOf(
            GridPos(0, 0) to 2, GridPos(0, 1) to 9,
            GridPos(1, 0) to 12, GridPos(1, 1) to 7,
            GridPos(2, 0) to 14, GridPos(2, 1) to 4,
            GridPos(3, 0) to 1, GridPos(3, 1) to 5,
            GridPos(4, 0) to 3, GridPos(4, 1) to 6,
            GridPos(5, 0) to 0, GridPos(5, 1) to 10,
            GridPos(6, 0) to 15, GridPos(6, 1) to 8,
            GridPos(7, 0) to 11, GridPos(7, 1) to 19,
            GridPos(8, 0) to 16, GridPos(8, 1) to 18,
            GridPos(9, 0) to 20, GridPos(9, 1) to 21,
            GridPos(10, 0) to 22, GridPos(10, 1) to 23,
            // row 11 fully empty
            GridPos(12, 0) to 13, GridPos(12, 1) to 17,
        ),
    )

    @Test
    fun fullyEmptyRow_collapsesOnSettle_evenIfWouldHomeLock() {
        val board = boardBeforeLDrag()
        assertEquals(null, board.grid.tileAt(GridPos(11, 0)))
        assertEquals(null, board.grid.tileAt(GridPos(11, 1)))

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(7, 0))) // L
        engine.moveFinger(Vec2(0f, 60f), cell, cell)
        val settled = engine.endDrag()

        assertEquals(12, settled.rows, "Fully empty row must collapse on settle")
        // No row may be entirely empty after settle.
        for (r in 0 until settled.rows) {
            assertTrue(
                (0 until settled.cols).any { c -> settled.grid.tileAt(GridPos(r, c)) != null },
                "row $r should not be fully empty",
            )
        }
    }
}
