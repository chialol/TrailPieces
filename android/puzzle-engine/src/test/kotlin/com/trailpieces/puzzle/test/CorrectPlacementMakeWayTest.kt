package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Priority: when a lifted tile/group lands on a **correct** (home) slot that
 * completes locks with resting neighbors, make way aggressively — push everything
 * below the cutline down (insert rows as needed), rather than blocking the land.
 *
 * ```
 * A B
 * C D
 * E F   (or E .)
 * G H
 * ```
 * Locks: {A,B,D}, {C,E}. H's home is under A → landing H at (1,0) should yield:
 * ```
 * A B
 * H D
 * … (possible empty separator row(s)) …
 * C E …  (and G, F) pushed below the cutline
 * ```
 */
class CorrectPlacementMakeWayTest {

    private val cell = 100f

    /**
     * A,B,D at homes → ABD lock.
     * C,E stacked with home delta (1,0) → CE lock (C is *on* H's home slot).
     * H home (1,0) under A. F,G loose.
     */
    private val tiles = listOf(
        PuzzleTile(0, GridPos(0, 0), "a"),
        PuzzleTile(1, GridPos(0, 1), "b"),
        PuzzleTile(2, GridPos(2, 0), "c"), // CE homes; C currently sits on H's home slot
        PuzzleTile(3, GridPos(1, 1), "d"),
        PuzzleTile(4, GridPos(3, 0), "e"),
        PuzzleTile(5, GridPos(0, 1), "f"),
        PuzzleTile(6, GridPos(1, 1), "g"),
        PuzzleTile(7, GridPos(1, 0), "h"), // home under A
    )

    private val manifest = PuzzleManifest(
        id = "correct-place",
        title = "Correct place",
        cols = 2,
        rows = 4,
        puzzleWidth = 200,
        puzzleHeight = 400,
        tiles = tiles,
    )

    private fun boardWithF(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 4,
        placements = mapOf(
            GridPos(0, 0) to 0, GridPos(0, 1) to 1,
            GridPos(1, 0) to 2, GridPos(1, 1) to 3,
            GridPos(2, 0) to 4, GridPos(2, 1) to 5,
            GridPos(3, 0) to 6, GridPos(3, 1) to 7,
        ),
    )

    /** Same layout but (2,1) empty; F parked on an extra bottom row. */
    private fun boardWithEmptyBesideE(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 5,
        placements = mapOf(
            GridPos(0, 0) to 0, GridPos(0, 1) to 1,
            GridPos(1, 0) to 2, GridPos(1, 1) to 3,
            GridPos(2, 0) to 4,
            GridPos(3, 0) to 6, GridPos(3, 1) to 7,
            GridPos(4, 1) to 5,
        ),
    )

    private fun assertLocks(board: PuzzleBoard) {
        assertEquals(setOf(0, 1, 3), board.componentContaining(0), "ABD")
        assertEquals(setOf(2, 4), board.componentContaining(2), "CE")
        assertEquals(setOf(7), board.componentContaining(7), "H alone")
    }

    private fun assertTopCorrect(board: PuzzleBoard) {
        BoardAssert.assertTileAt(board.grid, GridPos(0, 0), 0) // A
        BoardAssert.assertTileAt(board.grid, GridPos(0, 1), 1) // B
        BoardAssert.assertTileAt(board.grid, GridPos(1, 0), 7) // H under A
        BoardAssert.assertTileAt(board.grid, GridPos(1, 1), 3) // D
    }

    private fun assertPushedBelowCutline(board: PuzzleBoard, vararg tileIds: Int) {
        for (id in tileIds) {
            val slot = board.grid.slotOfOrNull(id)
            assertNotNull(slot, "tile $id missing")
            assertTrue(slot.row >= 2, "tile $id should be below cutline, was $slot")
        }
    }

    private fun dragHOntoHomeUnderA(board: PuzzleBoard): PuzzleBoard {
        val engine = DragEngine(board.manifest, board)
        assertTrue(engine.startDrag(GridPos(3, 1)), "lift H")
        // Any path that ends with H on home (1,0); left then up is enough travel.
        engine.moveFinger(Vec2(-250f, 0f), cell, cell)
        engine.moveFinger(Vec2(0f, -350f), cell, cell)
        return engine.endDrag()
    }

    @Ignore("Correct-placement cutline make-way not implemented yet")
    @Test
    fun hOntoHomeUnderA_withF_pushesBelowCutline_insertsOrSeparates() {
        val start = boardWithF()
        assertLocks(start)

        val settled = dragHOntoHomeUnderA(start)
        assertTopCorrect(settled)
        assertPushedBelowCutline(settled, 2, 4, 5, 6) // C, E, F, G
        assertTrue(
            settled.grid.rows > start.grid.rows ||
                (0 until settled.grid.rows).any { r ->
                    (0 until settled.grid.cols).all { c ->
                        settled.grid.tileAt(GridPos(r, c)) == null
                    }
                },
            "Expected insert-row growth and/or an empty separator below the correct top",
        )
    }

    @Ignore("Correct-placement cutline make-way not implemented yet")
    @Test
    fun hOntoHomeUnderA_emptyBesideE_pushesCegDown() {
        val start = boardWithEmptyBesideE()
        assertLocks(start)
        BoardAssert.assertEmpty(start.grid, GridPos(2, 1))

        val settled = dragHOntoHomeUnderA(start)
        assertTopCorrect(settled)
        assertPushedBelowCutline(settled, 2, 4, 6) // C, E, G
    }

    /**
     * Regression guard for the reported bug: bottom / overflow playfield tiles
     * must stay draggable. (UI hit-testing must use playfield rows, not only
     * manifest.rows — see PuzzleScreen gesture layer.)
     */
    @Test
    fun afterCorrectLand_tilesOnGrownRowsRemainDraggable() {
        // Grown board with tiles below manifest height — engine must allow drag.
        val grown = PuzzleFixtures.playfield(
            manifest,
            rows = 6,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 7, GridPos(1, 1) to 3,
                GridPos(3, 0) to 2, GridPos(3, 1) to 4,
                GridPos(4, 0) to 6, GridPos(4, 1) to 5,
            ),
        )
        assertTrue(grown.rows > manifest.rows)
        for (pos in listOf(GridPos(3, 0), GridPos(4, 0), GridPos(4, 1))) {
            assertNotNull(grown.beginDrag(pos), "beginDrag at $pos")
            val engine = DragEngine(grown.manifest, grown)
            assertTrue(engine.startDrag(pos), "startDrag at $pos")
            engine.endDrag()
        }
    }
}
