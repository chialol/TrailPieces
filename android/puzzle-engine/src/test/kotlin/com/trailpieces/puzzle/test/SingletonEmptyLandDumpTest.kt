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
 * Dump 2×13: empties at (5,0) and (6,0). A singleton must always be able to
 * land on an empty — adjacent, off-axis, or jumping past a rigid mass.
 */
class SingletonEmptyLandDumpTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "empty-land-dump",
        title = "Empty land dump",
        cols = 2,
        rows = 12,
        puzzleWidth = 200,
        puzzleHeight = 1200,
        tiles = (0..23).map { id ->
            PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
        },
    )

    private fun dumpBoard(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 13,
        placements = mapOf(
            GridPos(0, 0) to 5, GridPos(0, 1) to 12,
            GridPos(1, 0) to 6, GridPos(1, 1) to 0,
            GridPos(2, 0) to 7, GridPos(2, 1) to 1,
            GridPos(3, 0) to 13, GridPos(3, 1) to 17,
            GridPos(4, 0) to 15, GridPos(4, 1) to 2,
            GridPos(5, 1) to 11,
            GridPos(6, 1) to 8,
            GridPos(7, 0) to 14, GridPos(7, 1) to 22,
            GridPos(8, 0) to 16, GridPos(8, 1) to 3,
            GridPos(9, 0) to 18, GridPos(9, 1) to 19,
            GridPos(10, 0) to 20, GridPos(10, 1) to 21,
            GridPos(11, 0) to 10, GridPos(11, 1) to 23,
            GridPos(12, 0) to 9, GridPos(12, 1) to 4,
        ),
    )

    @Test
    fun lLeftOntoAdjacentEmpty() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(5, 1))) // L
        engine.moveFinger(Vec2(-60f, 0f), cell, cell)
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(5, 0), 11)
        BoardAssert.assertEmpty(settled.grid, GridPos(5, 1))
    }

    @Test
    fun cDownLeftOntoEmpty_offAxis() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(4, 1))) // C at J
        engine.moveFinger(Vec2(-60f, 60f), cell, cell) // onto (5,0)
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(5, 0), 2)
        BoardAssert.assertEmpty(settled.grid, GridPos(4, 1))
    }

    @Test
    fun kUpOntoNearestEmpty_jumpsPastMass() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(11, 0))) // K
        // Empty at (6,0) is 5 cells up; look-ahead needs > 4.5 cells.
        engine.moveFinger(Vec2(0f, -460f), cell, cell)
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(6, 0), 10)
        BoardAssert.assertEmpty(settled.grid, GridPos(11, 0))
        assertEquals(
            setOf(14, 16, 18, 19, 20, 21, 23),
            settled.componentContaining(14),
            "Mass must stay put; K only swaps with the hole",
        )
    }
}
