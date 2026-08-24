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
 * User dump: {S,T,U,W} at rows 8–10. Down one row should make-way (M up 1, X up 3)
 * into vacated holes — no insert-row / cutline growth.
 */
class STUWDownMakeWayTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "stuw-down",
        title = "STUW down",
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
            GridPos(0, 0) to 1, GridPos(0, 1) to 0,
            GridPos(1, 0) to 5, GridPos(1, 1) to 7,
            GridPos(2, 0) to 2, GridPos(2, 1) to 3,
            GridPos(3, 0) to 14, GridPos(3, 1) to 9,
            GridPos(4, 0) to 10, GridPos(4, 1) to 4,
            GridPos(5, 0) to 17, GridPos(5, 1) to 6,
            GridPos(6, 0) to 8, GridPos(6, 1) to 11,
            GridPos(7, 0) to 13, GridPos(7, 1) to 21,
            GridPos(8, 0) to 18, GridPos(8, 1) to 19,
            GridPos(9, 0) to 20, GridPos(9, 1) to 12,
            GridPos(10, 0) to 22, GridPos(10, 1) to 16,
            GridPos(11, 0) to 23, GridPos(11, 1) to 15,
        ),
    )

    @Test
    fun locks_stuwRigidGroup() {
        val b = dumpBoard()
        assertEquals(setOf(18, 19, 20, 22), b.componentContaining(18))
    }

    @Test
    fun stuwDownOneRow_makeWayWithoutInsertRow() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(9, 0))) // U in {S,T,U,W}
        engine.moveFinger(Vec2(0f, 60f), cell, cell)
        assertNotNull(engine.drag)
        assertEquals(12, engine.drag!!.grid.rows, "Down 1 row must not insert rows; got ${engine.drag!!.grid.rows}")

        val g = engine.drag!!.grid
        BoardAssert.assertTileAt(g, GridPos(8, 1), 12) // M moved up into T's hole
        BoardAssert.assertTileAt(g, GridPos(8, 0), 23) // X moved up into S's hole
        BoardAssert.assertTileAt(g, GridPos(10, 1), 16) // Q stays
        BoardAssert.assertTileAt(g, GridPos(11, 1), 15) // P stays
        // STUW footprint after +1 down (lifted tiles are holes on the resting grid)
        assertEquals(
            setOf(GridPos(9, 0), GridPos(9, 1), GridPos(10, 0), GridPos(11, 0)),
            engine.drag!!.targetSlots,
        )
        BoardAssert.assertEmpty(g, GridPos(9, 0))
        BoardAssert.assertEmpty(g, GridPos(9, 1))
        BoardAssert.assertEmpty(g, GridPos(10, 0))
        BoardAssert.assertEmpty(g, GridPos(11, 0))

        val settled = engine.endDrag()
        assertEquals(12, settled.grid.rows, "Settle must not grow the playfield")
        BoardAssert.assertTileAt(settled.grid, GridPos(9, 0), 18) // S
        BoardAssert.assertTileAt(settled.grid, GridPos(9, 1), 19) // T
        BoardAssert.assertTileAt(settled.grid, GridPos(10, 0), 20) // U
        BoardAssert.assertTileAt(settled.grid, GridPos(11, 0), 22) // W
    }
}
