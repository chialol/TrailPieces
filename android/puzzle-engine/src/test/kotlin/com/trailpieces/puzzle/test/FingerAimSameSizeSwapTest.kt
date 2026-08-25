package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.LockGroupService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Same-size loose tiles: swap must be visible mid-drag when the finger path
 * reaches the partner in one continuous diagonal (down-then-left). Release
 * sticks the layout; partners stay loose until settle recomputes locks.
 *
 * ```
 * A B
 * T X     X alone; {T,V} rigid in col0
 * V D
 * K H     K alone
 * ```
 */
class FingerAimSameSizeSwapTest {

    private val cell = 100f

    /** Homes tuned so only {T,V} lock; everyone else is loose at this layout. */
    private val manifest = PuzzleManifest(
        id = "finger-swap",
        title = "Finger swap",
        cols = 2,
        rows = 4,
        puzzleWidth = 200,
        puzzleHeight = 400,
        tiles = listOf(
            PuzzleTile(0, GridPos(0, 1), "a"),
            PuzzleTile(1, GridPos(2, 1), "b"),
            PuzzleTile(2, GridPos(0, 0), "t"), // T+V only
            PuzzleTile(3, GridPos(5, 0), "d"),
            PuzzleTile(4, GridPos(1, 0), "v"),
            PuzzleTile(5, GridPos(5, 1), "x"),
            PuzzleTile(6, GridPos(4, 0), "k"),
            PuzzleTile(7, GridPos(2, 1), "h"),
        ),
    )

    private fun board() = PuzzleFixtures.playfield(
        manifest,
        rows = 4,
        placements = mapOf(
            GridPos(0, 0) to 0, GridPos(0, 1) to 1,
            GridPos(1, 0) to 2, GridPos(1, 1) to 5, // T X
            GridPos(2, 0) to 4, GridPos(2, 1) to 3, // V D
            GridPos(3, 0) to 6, GridPos(3, 1) to 7, // K H
        ),
    )

    @Test
    fun locks_xAndKLoose_tvRigid() {
        val b = board()
        assertEquals(setOf(5), b.componentContaining(5), "X loose")
        assertEquals(setOf(6), b.componentContaining(6), "K loose")
        assertEquals(setOf(2, 4), b.componentContaining(2), "TV rigid")
    }

    @Test
    fun xOntoK_leftThenDown_swapsMidDrag() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(1, 1))) // X
        engine.moveFinger(Vec2(-150f, 0f), cell, cell)
        engine.moveFinger(Vec2(0f, 250f), cell, cell)
        engine.moveFinger(Vec2(-100f, 0f), cell, cell)

        val mid = engine.drag!!
        assertMidDragSwapVisible(mid)
        val settled = engine.endDrag()
        assertSwapLayout(settled)
    }

    @Test
    fun xOntoK_downThenLeft_swaps() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(1, 1))) // X
        engine.moveFinger(Vec2(0f, 250f), cell, cell)
        engine.moveFinger(Vec2(-250f, 0f), cell, cell)

        val mid = engine.drag!!
        assertMidDragSwapVisible(mid)
        val settled = engine.endDrag()
        assertSwapLayout(settled)
    }

    /** Swap must be visible while the finger is still down; partners stay loose. */
    private fun assertMidDragSwapVisible(mid: com.trailpieces.puzzle.service.DragSession) {
        assertTrue(
            mid.committedAnchor != mid.startAnchor,
            "swap must commit mid-drag before release",
        )
        BoardAssert.assertEmpty(mid.grid, GridPos(3, 0))
        BoardAssert.assertTileAt(mid.grid, GridPos(1, 1), 6)
        val live = LockGroupService.compute(mid.grid, manifest)
        assertEquals(setOf(6), live.members(6, manifest.tiles.map { it.id }), "K stays loose mid-drag")
    }

    private fun assertSwapLayout(board: com.trailpieces.puzzle.service.PuzzleBoard) {
        BoardAssert.assertTileAt(board.grid, GridPos(3, 0), 5) // X where K was
        BoardAssert.assertTileAt(board.grid, GridPos(1, 1), 6) // K where X was
    }
}
