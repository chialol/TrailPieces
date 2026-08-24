package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
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
 * Dump regression: singleton E at home; rigid mass between E and loose A in the
 * same column. Dragging E down toward A must same-size swap — not freeze because
 * the adjacent contact CC is larger than the lift.
 *
 * ```
 * G H
 * I J
 * E L     E loose @ home; L in mass
 * M N     both in mass (M under E)
 * A C     A loose
 * B D
 * ```
 */
class SingletonSwapPastRigidMassTest {

    private val cell = 100f

    /**
     * Mass homes are a contiguous block starting at row 3 so E@home (2,0) does
     * not lock into L beside it, while M under E still joins the mass via N.
     */
    private val manifest = PuzzleManifest(
        id = "dump-trim",
        title = "Dump trim",
        cols = 2,
        rows = 6,
        puzzleWidth = 200,
        puzzleHeight = 600,
        tiles = listOf(
            PuzzleTile(0, GridPos(0, 0), "a"),
            PuzzleTile(1, GridPos(0, 1), "b"),
            PuzzleTile(2, GridPos(1, 0), "c"),
            PuzzleTile(3, GridPos(1, 1), "d"),
            PuzzleTile(4, GridPos(2, 0), "e"),
            PuzzleTile(5, GridPos(6, 1), "n"),
            PuzzleTile(6, GridPos(3, 0), "g"),
            PuzzleTile(7, GridPos(3, 1), "h"),
            PuzzleTile(8, GridPos(4, 0), "i"),
            PuzzleTile(9, GridPos(4, 1), "j"),
            PuzzleTile(10, GridPos(6, 0), "m"),
            PuzzleTile(11, GridPos(5, 1), "l"),
        ),
    )

    private fun board(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 6,
        placements = mapOf(
            GridPos(0, 0) to 6, GridPos(0, 1) to 7,
            GridPos(1, 0) to 8, GridPos(1, 1) to 9,
            GridPos(2, 0) to 4, GridPos(2, 1) to 11,
            GridPos(3, 0) to 10, GridPos(3, 1) to 5,
            GridPos(4, 0) to 0, GridPos(4, 1) to 2,
            GridPos(5, 0) to 1, GridPos(5, 1) to 3,
        ),
    )

    @Test
    fun locks_eAndALoose_massIncludesMUnderE() {
        val b = board()
        assertEquals(setOf(4), b.componentContaining(4), "E loose")
        assertEquals(setOf(0), b.componentContaining(0), "A loose")
        val mass = b.componentContaining(6)
        assertTrue(
            mass.containsAll(setOf(6, 7, 8, 9, 11, 10, 5)),
            "GHIJLMN mass, got $mass",
        )
        assertTrue(4 !in mass && 0 !in mass)
    }

    @Test
    fun eDown_intoMass_jumpsToNearestSameSizeA() {
        val session = board().beginDrag(GridPos(2, 0), grouped = true)!!
        val pushed = session.tryPush(AxisDirection.Down)
        assertNotNull(pushed, "Must same-size jump past rigid mass to A")
        assertEquals(
            GridPos(4, 0),
            pushed.committedAnchor,
            "Nearest same-size is A at row 4, not +1 into the mass",
        )
        BoardAssert.assertTileAt(pushed.grid, GridPos(2, 0), 0) // A parked in E's hole
    }

    @Test
    fun eDownOntoA_sameColumn_swapsOnSettle() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(2, 0))) // E
        // Exactly 2 cells down → aim at A (overshoot rounds to B).
        engine.moveFinger(Vec2(0f, 200f), cell, cell)
        val settled = engine.endDrag()

        BoardAssert.assertTileAt(settled.grid, GridPos(4, 0), 4) // E where A was
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 0) // A where E was
    }

    @Test
    fun eDownOntoA_midDragCommitsWhenFingerCoversJump() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(2, 0)))
        // Jump E→A is 2 cells; look-ahead needs > (2 - 0.5) = 1.5 cells.
        engine.moveFinger(Vec2(0f, 151f), cell, cell)
        assertNotNull(engine.drag)
        assertEquals(
            GridPos(4, 0),
            engine.drag!!.committedAnchor,
            "same-size swap with A should commit once finger covers the jump",
        )
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(4, 0), 4)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 0)
    }

    @Test
    fun locks_cLoose_offAxisFromE() {
        val b = board()
        assertEquals(setOf(2), b.componentContaining(2), "C loose")
    }

    /**
     * Principle 1 off-axis: finger aims E at C (col 1), not A (col 0).
     * Diagonal aim must swap with C, not the on-axis singleton A.
     */
    @Test
    fun eOntoC_offAxis_diagonalAim_swapsOnSettle() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(2, 0))) // E
        // 2 down + 1 right → finger cell (4,1) == C
        engine.moveFinger(Vec2(100f, 200f), cell, cell)
        val settled = engine.endDrag()

        BoardAssert.assertTileAt(settled.grid, GridPos(4, 1), 4) // E where C was
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 2) // C where E was
    }

    @Test
    fun eOntoC_offAxis_diagonalAim_midDragSwapsWithC() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(2, 0))) // E
        engine.moveFinger(Vec2(100f, 151f), cell, cell)
        assertNotNull(engine.drag)
        assertEquals(
            GridPos(4, 1),
            engine.drag!!.committedAnchor,
            "Off-axis finger aim must swap with C, not on-axis A",
        )
        BoardAssert.assertTileAt(engine.drag!!.grid, GridPos(2, 0), 2) // C parked in E's hole
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(4, 1), 4)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 2)
    }
}
