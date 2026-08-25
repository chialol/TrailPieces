package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dump regression: locked vertical pair {U,W} dragged down through a mass toward
 * homes occupied by loose {B,A} (same footprint, not locked together). Mid-drag
 * must congruent-footprint swap; release parks.
 *
 * ```
 * C D
 * E F
 * G H
 * U P     UW lifted (rigid); homes at BA
 * W O
 * Q R     rigid mass between
 * S T
 * B V     B,A loose (same footprint, unconnected)
 * A X
 * ```
 */
class CongruentFootprintSwapTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "uw-ba-footprint",
        title = "UW BA footprint",
        cols = 2,
        rows = 9,
        puzzleWidth = 200,
        puzzleHeight = 900,
        tiles = listOf(
            PuzzleTile(0, GridPos(0, 0), "c"),
            PuzzleTile(1, GridPos(0, 1), "d"),
            PuzzleTile(2, GridPos(1, 0), "e"),
            PuzzleTile(3, GridPos(1, 1), "f"),
            PuzzleTile(4, GridPos(2, 0), "g"),
            PuzzleTile(5, GridPos(2, 1), "h"),
            PuzzleTile(6, GridPos(7, 0), "u"), // U home under B
            PuzzleTile(7, GridPos(3, 1), "p"),
            PuzzleTile(8, GridPos(8, 0), "w"), // W home under A
            PuzzleTile(9, GridPos(4, 1), "o"),
            // QRST rigid 2×2
            PuzzleTile(10, GridPos(5, 0), "q"),
            PuzzleTile(11, GridPos(5, 1), "r"),
            PuzzleTile(12, GridPos(6, 0), "s"),
            PuzzleTile(13, GridPos(6, 1), "t"),
            // B,A homes are UW pickup; placed crossed on UW homes so they stay loose.
            PuzzleTile(14, GridPos(3, 0), "b"),
            PuzzleTile(15, GridPos(7, 1), "v"),
            PuzzleTile(16, GridPos(4, 0), "a"),
            PuzzleTile(17, GridPos(8, 1), "x"),
        ),
    )

    private fun board() = PuzzleFixtures.playfield(
        manifest,
        rows = 9,
        placements = mapOf(
            GridPos(0, 0) to 0, GridPos(0, 1) to 1,
            GridPos(1, 0) to 2, GridPos(1, 1) to 3,
            GridPos(2, 0) to 4, GridPos(2, 1) to 5,
            GridPos(3, 0) to 6, GridPos(3, 1) to 7, // U P
            GridPos(4, 0) to 8, GridPos(4, 1) to 9, // W O
            GridPos(5, 0) to 10, GridPos(5, 1) to 11, // Q R
            GridPos(6, 0) to 12, GridPos(6, 1) to 13, // S T
            GridPos(7, 0) to 16, GridPos(7, 1) to 15, // A V (A loose on U home)
            GridPos(8, 0) to 14, GridPos(8, 1) to 17, // B X (B loose on W home)
        ),
    )

    @Test
    fun locks_uwRigid_baLoose_massBlocksCol0() {
        val b = board()
        assertEquals(setOf(6, 8), b.componentContaining(6), "UW rigid")
        assertEquals(setOf(14), b.componentContaining(14), "B loose")
        assertEquals(setOf(16), b.componentContaining(16), "A loose")
        assertTrue(
            b.componentContaining(10).size > 2,
            "mass between UW and BA must block make-way",
        )
    }

    @Test
    fun uwDownOntoBa_congruentFootprint_swapsMidDrag() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(3, 0))) // U in {UW}
        assertEquals(setOf(6, 8), engine.drag!!.liftedTileIds)

        // Jump UW → BA is 4 cells; look-ahead needs > 3.5 cells.
        engine.moveFinger(Vec2(0f, 360f), cell, cell)

        val mid = engine.drag!!
        assertEquals(
            setOf(GridPos(7, 0), GridPos(8, 0)),
            mid.targetSlots,
            "UW footprint must land on BA mid-drag",
        )
        BoardAssert.assertTileAt(mid.grid, GridPos(3, 0), 16) // A in U hole
        BoardAssert.assertTileAt(mid.grid, GridPos(4, 0), 14) // B in W hole

        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(7, 0), 6) // U
        BoardAssert.assertTileAt(settled.grid, GridPos(8, 0), 8) // W
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 0), 16)
        BoardAssert.assertTileAt(settled.grid, GridPos(4, 0), 14)
    }
}
