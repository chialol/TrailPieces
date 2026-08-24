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
 * Prefer landing that completes locks. When H lands on its home under A,
 * displace occupants by pushing the strip below the cutline down and
 * **inserting one empty row** (do not collapse that separator away if it
 * is required to keep the correct top intact — see settle policy when
 * implementing).
 *
 * Start (with F):
 * ```
 * A B
 * C D
 * E F
 * G H
 * ```
 * After H → under A:
 * ```
 * A B
 * H D
 * . .
 * C E
 * G F
 * ```
 *
 * Start (empty beside E):
 * ```
 * A B
 * C D
 * E .
 * G H
 * . F
 * ```
 * After:
 * ```
 * A B
 * H D
 * . .
 * C E
 * G .
 * . F
 * ```
 */
class CorrectPlacementMakeWayTest {

    private val cell = 100f

    private val tiles = listOf(
        PuzzleTile(0, GridPos(0, 0), "a"),
        PuzzleTile(1, GridPos(0, 1), "b"),
        PuzzleTile(2, GridPos(2, 0), "c"),
        PuzzleTile(3, GridPos(1, 1), "d"),
        PuzzleTile(4, GridPos(3, 0), "e"),
        PuzzleTile(5, GridPos(4, 1), "f"), // not under D — avoid D–F lock at start
        PuzzleTile(6, GridPos(3, 1), "g"),
        PuzzleTile(7, GridPos(1, 0), "h"),
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

    private fun dragHOntoHomeUnderA(board: PuzzleBoard): PuzzleBoard {
        val engine = DragEngine(board.manifest, board)
        assertTrue(engine.startDrag(GridPos(3, 1)), "lift H")
        engine.moveFinger(Vec2(-250f, 0f), cell, cell)
        engine.moveFinger(Vec2(0f, -350f), cell, cell)
        return engine.endDrag()
    }

    @Test
    fun hOntoHomeUnderA_withF_pushesCegDown_insertsEmptyRow() {
        val start = boardWithF()
        assertLocks(start)
        assertEquals(4, start.rows)

        val settled = dragHOntoHomeUnderA(start)

        // A B / H D / empty / C E / G F  — one extra row vs start.
        assertEquals(5, settled.grid.rows, "Need one extra row after pushing C,E,G down")
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 1), 1)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 7)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 1), 3)
        BoardAssert.assertEmpty(settled.grid, GridPos(2, 0))
        BoardAssert.assertEmpty(settled.grid, GridPos(2, 1))
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 0), 2) // C
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 1), 4) // E
        BoardAssert.assertTileAt(settled.grid, GridPos(4, 0), 6) // G
        BoardAssert.assertTileAt(settled.grid, GridPos(4, 1), 5) // F
        assertTrue(settled.componentContaining(0).contains(7), "H locks under A")
    }

    @Test
    fun hOntoHomeUnderA_emptyBesideE_pushesCegAndHoleDown_lastRowEmptyF() {
        val start = boardWithEmptyBesideE()
        assertLocks(start)
        BoardAssert.assertEmpty(start.grid, GridPos(2, 1))
        assertEquals(5, start.rows)

        val settled = dragHOntoHomeUnderA(start)

        // A B / H D / empty / C E / G . / . F
        assertEquals(6, settled.grid.rows, "Need one extra row")
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 1), 1)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 7)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 1), 3)
        BoardAssert.assertEmpty(settled.grid, GridPos(2, 0))
        BoardAssert.assertEmpty(settled.grid, GridPos(2, 1))
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 0), 2) // C
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 1), 4) // E
        BoardAssert.assertTileAt(settled.grid, GridPos(4, 0), 6) // G
        BoardAssert.assertEmpty(settled.grid, GridPos(4, 1))
        BoardAssert.assertEmpty(settled.grid, GridPos(5, 0))
        BoardAssert.assertTileAt(settled.grid, GridPos(5, 1), 5) // F — last row *, F
        assertTrue(settled.componentContaining(0).contains(7), "H locks under A")
    }

    @Test
    fun afterCorrectLand_tilesOnGrownRowsRemainDraggable() {
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
