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
 * User dump: U at Z (12,1); X at W (11,0); empty at X's home (11,1).
 * Drag U onto X — mid-drag X should park on the empty (its home). On release,
 * X must stay there (no settle swap that dumps X down into U's old hole).
 */
class UXEmptyParkSettleTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "ux-empty-park",
        title = "U onto X empty park",
        cols = 2,
        rows = 12,
        puzzleWidth = 200,
        puzzleHeight = 1200,
        tiles = (0..23).map { id ->
            PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
        },
    )

    /** Exact user dump (13-row playfield; homes are 12 rows). */
    private fun dumpBoard(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 13,
        placements = mapOf(
            GridPos(0, 0) to 5, GridPos(0, 1) to 1, // F B
            GridPos(1, 0) to 9, GridPos(1, 1) to 4, // J E
            GridPos(2, 0) to 0, GridPos(2, 1) to 7, // A H
            GridPos(3, 0) to 2, GridPos(3, 1) to 3, // C D
            GridPos(4, 0) to 12, // M .
            GridPos(5, 0) to 14, GridPos(5, 1) to 15, // O P
            GridPos(6, 0) to 6, GridPos(6, 1) to 11, // G L
            GridPos(7, 0) to 8, GridPos(7, 1) to 18, // I S
            GridPos(8, 0) to 17, GridPos(8, 1) to 10, // R K
            GridPos(9, 0) to 13, GridPos(9, 1) to 21, // N V
            GridPos(10, 0) to 22, GridPos(10, 1) to 16, // W Q
            GridPos(11, 0) to 23, // X .
            GridPos(12, 0) to 19, GridPos(12, 1) to 20, // T U
        ),
    )

    @Test
    fun dump_uAtZ_xAtW_emptyAtXHome() {
        val b = dumpBoard()
        assertEquals(20, b.grid.tileAt(GridPos(12, 1)), "U at Z")
        assertEquals(23, b.grid.tileAt(GridPos(11, 0)), "X at W")
        assertEquals(null, b.grid.tileAt(GridPos(11, 1)), "empty at X home")
        assertEquals(GridPos(11, 1), manifest.tileOrNull(23)!!.home)
        assertEquals(setOf(20), b.componentContaining(20))
        assertEquals(setOf(23), b.componentContaining(23))
    }

    /**
     * Drag U up onto the empty (X's home), then left onto X — X parks on the empty.
     * Release must not dump X down into U's vacated Z (settle currently prefers
     * U's home cutline from the pre-drag board and overrides the mid-drag park).
     */
    @Test
    fun uOntoX_xParksOnEmpty_settleMustNotDumpXToZ() {
        val engine = DragEngine(manifest, dumpBoard())
        assertTrue(engine.startDrag(GridPos(12, 1))) // U at Z

        // Up onto empty at (11,1), then Left same-size onto X at W.
        engine.moveFinger(Vec2(0f, -60f), cell, cell)
        engine.moveFinger(Vec2(-60f, 0f), cell, cell)

        val mid = engine.drag
        assertNotNull(mid, "still dragging")
        assertEquals(
            GridPos(11, 1),
            mid.grid.slotOfOrNull(23),
            "mid-drag: X should already sit on the empty (its home)",
        )
        assertEquals(
            GridPos(11, 0),
            mid.committedAnchor,
            "mid-drag: U footprint on X's former cell W",
        )

        val settled = engine.endDrag()
        assertEquals(
            GridPos(11, 1),
            settled.grid.slotOfOrNull(23),
            "on release X must stay on empty/home — not fall to Z",
        )
        assertTrue(
            settled.grid.tileAt(GridPos(12, 1)) != 23,
            "X must not occupy U's old hole Z (was ${settled.grid.tileAt(GridPos(12, 1))})",
        )
        assertEquals(20, settled.grid.tileAt(GridPos(11, 0)), "U lands on W")
    }
}