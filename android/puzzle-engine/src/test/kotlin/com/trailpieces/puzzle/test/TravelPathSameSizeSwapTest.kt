package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dump regression: singleton W beside a rigid mass that blocks left make-way.
 * Diagonal travel toward lower-left L must same-size swap mid-drag (route-aware):
 * pass over U without moving it, land on first congruent spot (L).
 *
 * Route matters:
 * - left+down toward L → swap with L (N stays)
 * - down first → swap with N, then left → swap with L (different resting layout)
 *
 * ```
 * S A     {S,U} rigid
 * U W     W loose (lift)
 * L N     L,N loose
 * ```
 */
class TravelPathSameSizeSwapTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "travel-path-swap",
        title = "Travel path swap",
        cols = 2,
        rows = 3,
        puzzleWidth = 200,
        puzzleHeight = 300,
        tiles = listOf(
            // S+U lock vertically; A must not join S horizontally.
            PuzzleTile(0, GridPos(0, 0), "s"),
            PuzzleTile(1, GridPos(3, 1), "a"),
            PuzzleTile(2, GridPos(1, 0), "u"),
            // W's home is L's cell (connects with U) — dump case.
            PuzzleTile(3, GridPos(2, 0), "w"),
            PuzzleTile(4, GridPos(4, 1), "l"),
            PuzzleTile(5, GridPos(5, 1), "n"),
        ),
    )

    private fun board() = PuzzleFixtures.playfield(
        manifest,
        rows = 3,
        placements = mapOf(
            GridPos(0, 0) to 0, GridPos(0, 1) to 1,
            GridPos(1, 0) to 2, GridPos(1, 1) to 3, // U W
            GridPos(2, 0) to 4, GridPos(2, 1) to 5, // L N
        ),
    )

    @Test
    fun locks_wAndLLoose_suRigid() {
        val b = board()
        assertEquals(setOf(3), b.componentContaining(3), "W loose")
        assertEquals(setOf(4), b.componentContaining(4), "L loose")
        assertEquals(setOf(5), b.componentContaining(5), "N loose")
        assertEquals(setOf(0, 2), b.componentContaining(0), "SU rigid")
    }

    @Test
    fun wTowardL_leftAndDown_swapsWithL_midDrag() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(1, 1))) // W
        // Strong left + enough down to cover L at (2,0); U cannot be displaced.
        engine.moveFinger(Vec2(-150f, 100f), cell, cell)

        val mid = engine.drag
        assertNotNull(mid)
        assertEquals(
            GridPos(2, 0),
            mid.committedAnchor,
            "first landable same-size along travel must be L",
        )
        BoardAssert.assertEmpty(mid.grid, GridPos(2, 0))
        BoardAssert.assertTileAt(mid.grid, GridPos(1, 1), 4) // L in W's hole
        BoardAssert.assertTileAt(mid.grid, GridPos(1, 0), 2) // U unmoved
        BoardAssert.assertTileAt(mid.grid, GridPos(2, 1), 5) // N unmoved

        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 3) // W where L was
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 1), 4) // L where W was
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 2)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 1), 5)
    }

    @Test
    fun wDownThenLeft_swapsNThenL_differentLayout() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(1, 1))) // W
        engine.moveFinger(Vec2(0f, 100f), cell, cell)
        assertEquals(GridPos(2, 1), engine.drag!!.committedAnchor, "down lands on N")
        BoardAssert.assertTileAt(engine.drag!!.grid, GridPos(1, 1), 5) // N in hole

        engine.moveFinger(Vec2(-100f, 0f), cell, cell)
        assertEquals(GridPos(2, 0), engine.drag!!.committedAnchor, "then left lands on L")
        BoardAssert.assertTileAt(engine.drag!!.grid, GridPos(2, 1), 4) // L where N was
        BoardAssert.assertTileAt(engine.drag!!.grid, GridPos(1, 1), 5) // N still in pickup

        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 3) // W
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 1), 4) // L
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 1), 5) // N
    }

    /**
     * Left-heavy finger that still has a down component: even when left push into
     * U is impossible, travel must keep seeking the first landable cell (L).
     */
    @Test
    fun wLeftHeavyTowardL_doesNotStickOnBlockedU() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(1, 1)))
        // Mirrors dump proportions: mostly left, some down (~4.5 left, ~1.1 down).
        engine.moveFinger(Vec2(-450f, 110f), cell, cell)

        val mid = engine.drag
        assertNotNull(mid)
        assertEquals(
            GridPos(2, 0),
            mid.committedAnchor,
            "must not stay on pickup when L is covered along travel",
        )
        BoardAssert.assertTileAt(mid.grid, GridPos(1, 0), 2) // U still fixed
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 3)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 1), 4)
    }
}
