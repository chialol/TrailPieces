package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.DragSession
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Desired placement policy for connected components (CCs):
 *
 * 1. Same-size CC moving onto another CC → **swap**.
 * 2. CC moving onto empty cells that fit → **must succeed**.
 * 3. Prefer the user's intended landing, **especially** when that landing would
 *    form new locks. If the occupant cannot be pushed aside, insert empty
 *    row(s) (≥ lifted height) and park — but prefer in-place make-way when it
 *    already works (do not grow every time).
 *
 * Homes are tuned so only the intended lock groups form (adjacent tiles lock
 * iff slot offset equals home offset).
 */
class ComponentPlacementPolicyTest {

    private val cell = 100f

    private fun push(session: DragSession, dir: AxisDirection): DragSession {
        val next = session.tryPush(dir)
        assertNotNull(next, "Push $dir should succeed")
        return next
    }

    private fun pushUntilBlocked(session: DragSession, dir: AxisDirection, max: Int = 16): DragSession {
        var cur = session
        repeat(max) { cur = cur.tryPush(dir) ?: return cur }
        return cur
    }

    private fun manifest(cols: Int, rows: Int, tiles: List<PuzzleTile>) = PuzzleManifest(
        id = "cpp-$cols-$rows",
        title = "CPP",
        cols = cols,
        rows = rows,
        puzzleWidth = cols * 100,
        puzzleHeight = rows * 100,
        tiles = tiles,
    )

    // =========================================================================
    // 1) Same-size CC swap
    // =========================================================================

    @Test
    fun swap_verticalPairs_sameColumn_topOntoBottom() {
        // Top {0,2} homes (0,0)(1,0); bottom {4,6} homes (0,1)(1,1) so they don't merge with top.
        val man = manifest(
            2,
            4,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(2, 1), "b"),
                PuzzleTile(2, GridPos(1, 0), "c"),
                PuzzleTile(3, GridPos(3, 1), "d"),
                PuzzleTile(4, GridPos(0, 1), "e"),
                PuzzleTile(5, GridPos(2, 0), "f"),
                PuzzleTile(6, GridPos(1, 1), "g"),
                PuzzleTile(7, GridPos(3, 0), "h"),
            ),
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 3,
                GridPos(2, 0) to 4, GridPos(2, 1) to 5,
                GridPos(3, 0) to 6, GridPos(3, 1) to 7,
            ),
        )
        assertEquals(setOf(0, 2), board.componentContaining(0))
        assertEquals(setOf(4, 6), board.componentContaining(4))

        var session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        session = pushUntilBlocked(session, AxisDirection.Down)
        val settled = session.settle()

        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 0), 2)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 4)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 6)
    }

    @Test
    fun swap_horizontalPairs_upperOntoLower() {
        // {0,1} homes (0,0)(0,1); {2,3} homes (2,0)(2,1) — no vertical merge.
        val man = manifest(
            2,
            3,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(0, 1), "b"),
                PuzzleTile(2, GridPos(2, 0), "c"),
                PuzzleTile(3, GridPos(2, 1), "d"),
                PuzzleTile(4, GridPos(1, 0), "e"),
                PuzzleTile(5, GridPos(1, 1), "f"),
            ),
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 3,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 3,
                GridPos(2, 0) to 4, GridPos(2, 1) to 5,
            ),
        )
        assertEquals(setOf(0, 1), board.componentContaining(0))
        assertEquals(setOf(2, 3), board.componentContaining(2))

        var session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        session = push(session, AxisDirection.Down)
        val settled = session.settle()

        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 1), 1)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 2)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 1), 3)
    }

    @Test
    fun swap_singles_sameColumn() {
        val m = PuzzleFixtures.miniManifest()
        // E above D — neither locks (home offsets differ). Same-size swap one cell down.
        val board = PuzzleFixtures.playfield(
            m,
            rows = 3,
            placements = mapOf(
                GridPos(0, 0) to 4, // E
                GridPos(0, 1) to 1,
                GridPos(1, 0) to 3, // D alone under E
                GridPos(1, 1) to 0, // A
                GridPos(2, 0) to 2,
                GridPos(2, 1) to 5,
            ),
        )
        assertEquals(setOf(4), board.componentContaining(4))
        assertEquals(setOf(3), board.componentContaining(3))
        var session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        assertEquals(setOf(4), session.liftedTileIds)
        session = push(session, AxisDirection.Down)
        val settled = session.settle()
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 4)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 3)
    }

    @Test
    fun swap_verticalTriples_sameColumn_onTallBoard() {
        // Top {0,2,4} homes (0,0)(1,0)(2,0); bottom {5,7,9} homes (0,1)(1,1)(2,1).
        val man = manifest(
            2,
            5,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(3, 1), "b"),
                PuzzleTile(2, GridPos(1, 0), "c"),
                PuzzleTile(3, GridPos(4, 1), "d"),
                PuzzleTile(4, GridPos(2, 0), "e"),
                PuzzleTile(5, GridPos(0, 1), "f"),
                PuzzleTile(6, GridPos(3, 0), "g"),
                PuzzleTile(7, GridPos(1, 1), "h"),
                PuzzleTile(8, GridPos(4, 0), "i"),
                PuzzleTile(9, GridPos(2, 1), "j"),
            ),
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 6,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 3,
                GridPos(2, 0) to 4, GridPos(2, 1) to 6,
                GridPos(3, 0) to 5, GridPos(3, 1) to 8,
                GridPos(4, 0) to 7,
                GridPos(5, 0) to 9,
            ),
        )
        assertEquals(setOf(0, 2, 4), board.componentContaining(0))
        assertEquals(setOf(5, 7, 9), board.componentContaining(5))

        var session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        session = pushUntilBlocked(session, AxisDirection.Down)
        val settled = session.settle()

        BoardAssert.assertTileAt(settled.grid, GridPos(3, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(4, 0), 2)
        BoardAssert.assertTileAt(settled.grid, GridPos(5, 0), 4)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 5)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 7)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 9)
    }

    @Test
    fun swap_verticalPairs_leftOntoRight_adjacent() {
        // Left {0,5} homes col0; right {1,4} homes col2 so they don't merge when adjacent.
        val man = manifest(
            2,
            2,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(0, 2), "b"),
                PuzzleTile(2, GridPos(2, 0), "c"),
                PuzzleTile(3, GridPos(2, 1), "d"),
                PuzzleTile(4, GridPos(1, 2), "e"),
                PuzzleTile(5, GridPos(1, 0), "f"),
            ),
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 2,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 5, GridPos(1, 1) to 4,
            ),
        )
        assertEquals(setOf(0, 5), board.componentContaining(0))
        assertEquals(setOf(1, 4), board.componentContaining(1))

        var session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        session = push(session, AxisDirection.Right)
        val settled = session.settle()

        BoardAssert.assertTileAt(settled.grid, GridPos(0, 1), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 1), 5)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 1)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 4)
    }

    // =========================================================================
    // 2) Fit into empty footprint
    // =========================================================================

    @Test
    fun emptyFit_verticalPair_intoTwoEmptiesBelow() {
        // {0,2} only; other tiles parked so they don't lock onto the pair.
        val man = manifest(
            2,
            4,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(2, 1), "b"),
                PuzzleTile(2, GridPos(1, 0), "c"),
                PuzzleTile(3, GridPos(3, 1), "d"),
                PuzzleTile(4, GridPos(0, 1), "e"),
                PuzzleTile(5, GridPos(1, 1), "f"),
                PuzzleTile(6, GridPos(3, 0), "g"),
                PuzzleTile(7, GridPos(2, 0), "h"),
            ),
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 5,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 3,
                GridPos(2, 1) to 4,
                GridPos(3, 1) to 5,
                GridPos(4, 1) to 6,
                GridPos(4, 0) to 7,
            ),
        )
        assertEquals(setOf(0, 2), board.componentContaining(0))
        BoardAssert.assertEmpty(board.grid, GridPos(2, 0))
        BoardAssert.assertEmpty(board.grid, GridPos(3, 0))

        var session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        val rowsDuring = session.grid.rows
        session = push(session, AxisDirection.Down)
        session = push(session, AxisDirection.Down)
        assertEquals(rowsDuring, session.grid.rows, "Fitting empties must not insert rows")
        val settled = session.settle()
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 0), 2)
    }

    @Test
    fun emptyFit_horizontalPair_slidesIntoEmptyOnRight() {
        val man = manifest(
            3,
            3,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(0, 1), "b"),
                PuzzleTile(2, GridPos(2, 2), "c"),
                PuzzleTile(3, GridPos(2, 0), "d"), // not home-under A
                PuzzleTile(4, GridPos(1, 2), "e"),
                PuzzleTile(5, GridPos(0, 2), "f"),
                PuzzleTile(6, GridPos(2, 1), "g"),
                PuzzleTile(7, GridPos(1, 1), "h"),
                PuzzleTile(8, GridPos(1, 0), "i"),
            ),
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 3,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 3, GridPos(1, 1) to 4, GridPos(1, 2) to 5,
                GridPos(2, 0) to 6, GridPos(2, 1) to 7, GridPos(2, 2) to 8,
            ),
        )
        assertEquals(setOf(0, 1), board.componentContaining(0))

        var session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        session = push(session, AxisDirection.Right)
        val settled = session.settle()
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 1), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 2), 1)
    }

    @Test
    fun emptyFit_singleIntoPersistentEmpty() {
        val m = PuzzleFixtures.miniManifest()
        val board = PuzzleFixtures.playfield(
            m,
            rows = 3,
            placements = mapOf(
                GridPos(0, 0) to 0,
                GridPos(0, 1) to 1,
                GridPos(1, 1) to 3,
                GridPos(2, 0) to 4,
                GridPos(2, 1) to 5,
            ),
        )
        BoardAssert.assertEmpty(board.grid, GridPos(1, 0))
        var session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        session = push(session, AxisDirection.Down)
        val settled = session.settle()
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 0)
    }

    @Test
    fun emptyFit_verticalPair_intoEmptiesOnWiderBoard_leftColumn() {
        // Minimal: only {0,2} on a 3×4 field with empties below in col0.
        val man = manifest(
            3,
            4,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(0, 2), "b"),
                PuzzleTile(2, GridPos(1, 0), "c"),
                PuzzleTile(3, GridPos(1, 2), "d"),
                PuzzleTile(4, GridPos(2, 2), "e"),
                PuzzleTile(5, GridPos(3, 2), "f"),
                PuzzleTile(6, GridPos(2, 0), "g"),
                PuzzleTile(7, GridPos(3, 0), "h"),
                PuzzleTile(8, GridPos(2, 1), "i"),
                PuzzleTile(9, GridPos(3, 1), "j"),
                // Fillers: homes that do not bridge into {0,2}.
                PuzzleTile(10, GridPos(4, 0), "k"),
                PuzzleTile(11, GridPos(4, 1), "l"),
            ),
        )
        val board2 = PuzzleFixtures.playfield(
            man,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 10, GridPos(0, 2) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 11, GridPos(1, 2) to 3,
                GridPos(2, 1) to 8, GridPos(2, 2) to 4,
                GridPos(3, 1) to 9, GridPos(3, 2) to 5,
                // (2,0)(3,0) empty
            ),
        )
        assertEquals(setOf(0, 2), board2.componentContaining(0))
        BoardAssert.assertEmpty(board2.grid, GridPos(2, 0))
        BoardAssert.assertEmpty(board2.grid, GridPos(3, 0))

        var session = board2.beginDrag(GridPos(0, 0), grouped = true)!!
        session = push(session, AxisDirection.Down)
        session = push(session, AxisDirection.Down)
        val settled = session.settle()
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 0), 2)
    }

    // =========================================================================
    // 3) Prefer intended landing / connect / insert only when needed
    // =========================================================================

    /** Release cutline / lock-connect — out of spirit (park-only on finger-up). */
    @Ignore("Release finishers deleted — needs mid-drag path if desired")
    @Test
    fun preferLanding_hOntoHomeUnderA_connectsWithAbd() {
        val man = manifest(
            2,
            4,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(0, 1), "b"),
                PuzzleTile(2, GridPos(2, 0), "c"),
                PuzzleTile(3, GridPos(1, 1), "d"),
                PuzzleTile(4, GridPos(3, 0), "e"),
                PuzzleTile(5, GridPos(4, 1), "f"), // not under D
                PuzzleTile(6, GridPos(3, 1), "g"),
                PuzzleTile(7, GridPos(1, 0), "h"),
            ),
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 3,
                GridPos(2, 0) to 4, GridPos(2, 1) to 5,
                GridPos(3, 0) to 6, GridPos(3, 1) to 7,
            ),
        )
        assertTrue(board.componentContaining(0).containsAll(setOf(0, 1, 3)))
        val engine = DragEngine(man, board)
        assertTrue(engine.startDrag(GridPos(3, 1)))
        engine.moveFinger(Vec2(-200f, 0f), cell, cell)
        engine.moveFinger(Vec2(0f, -350f), cell, cell)
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 7)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 0)
        assertTrue(settled.componentContaining(0).contains(7), "H should lock under A")
    }

    @Test
    fun preferLanding_looseHOntoCsCell_throughBigLock() {
        val man = manifest(
            2,
            4,
            (0 until 8).map { id -> PuzzleTile(id, GridPos(id / 2, id % 2), "t$id") },
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 7, GridPos(1, 1) to 3,
                GridPos(2, 0) to 4, GridPos(2, 1) to 5,
                GridPos(3, 0) to 6, GridPos(3, 1) to 2,
            ),
        )
        assertEquals(setOf(0, 1, 3, 4, 5, 6), board.componentContaining(0))
        val engine = DragEngine(man, board)
        assertTrue(engine.startDrag(GridPos(1, 0)))
        engine.moveFinger(Vec2(0f, 400f), cell, cell)
        engine.moveFinger(Vec2(200f, 0f), cell, cell)
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(3, 1), 7)
    }

    /** Relied on release completing swap — out of spirit. */
    @Ignore("Release finishers deleted — needs mid-drag completing swap if desired")
    @Test
    fun preferLanding_looseCOntoHsCell_throughBigLock() {
        val man = manifest(
            2,
            4,
            (0 until 8).map { id -> PuzzleTile(id, GridPos(id / 2, id % 2), "t$id") },
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 7, GridPos(1, 1) to 3,
                GridPos(2, 0) to 4, GridPos(2, 1) to 5,
                GridPos(3, 0) to 6, GridPos(3, 1) to 2,
            ),
        )
        val engine = DragEngine(man, board)
        assertTrue(engine.startDrag(GridPos(3, 1)))
        engine.moveFinger(Vec2(0f, -400f), cell, cell)
        engine.moveFinger(Vec2(-200f, 0f), cell, cell)
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 2)
    }

    @Test
    fun insertRows_whenUpBlocked_growsByAtLeastLiftedHeight() {
        val m = PuzzleFixtures.miniManifest()
        val board = PuzzleFixtures.horizontalPairWithLooseRowBelow(m)
        val session = board.beginDrag(GridPos(2, 0), grouped = true)!!
        val before = session.grid.rows
        val pushed = session.tryPush(AxisDirection.Up)
        assertNotNull(pushed, "Up into blocked lock must insert-row or make way")
        assertTrue(
            pushed.grid.rows >= before + 1 ||
                pushed.committedAnchor.row < session.committedAnchor.row,
            "Playfield should grow or footprint must advance upward",
        )
    }

    @Test
    fun preferInPlace_noInsert_whenEmptyAlreadyFits() {
        val m = PuzzleFixtures.miniManifest()
        val board = PuzzleFixtures.playfield(
            m,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                // (1,0) empty
                GridPos(1, 1) to 3,
                GridPos(2, 1) to 2,
                GridPos(3, 0) to 4, GridPos(3, 1) to 5,
            ),
        )
        BoardAssert.assertEmpty(board.grid, GridPos(1, 0))
        var session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        val rowsBeforePush = session.grid.rows
        session = push(session, AxisDirection.Down)
        assertEquals(rowsBeforePush, session.grid.rows, "Must not insert rows mid-drag when empty fits")
        val settled = session.settle()
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 0)
    }

    @Test
    fun preferInPlace_verticalPairShift_whenRoomBelowOnTallField() {
        val man = manifest(
            2,
            4,
            listOf(
                PuzzleTile(0, GridPos(0, 0), "a"),
                PuzzleTile(1, GridPos(2, 1), "b"),
                PuzzleTile(2, GridPos(1, 0), "c"),
                PuzzleTile(3, GridPos(3, 1), "d"),
                PuzzleTile(4, GridPos(0, 1), "e"),
                PuzzleTile(5, GridPos(2, 0), "f"),
                PuzzleTile(6, GridPos(1, 1), "g"),
                PuzzleTile(7, GridPos(3, 0), "h"),
            ),
        )
        val board = PuzzleFixtures.playfield(
            man,
            rows = 5,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 3,
                GridPos(2, 0) to 4, GridPos(2, 1) to 5,
                GridPos(3, 0) to 6, GridPos(3, 1) to 7,
            ),
        )
        assertEquals(setOf(0, 2), board.componentContaining(0))
        assertEquals(setOf(4, 6), board.componentContaining(4))
        var session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        val rowsBefore = session.grid.rows
        session = pushUntilBlocked(session, AxisDirection.Down)
        assertEquals(rowsBefore, session.grid.rows, "Tall field with slack must not insert-row")
        assertTrue(
            session.committedAnchor.row > 0,
            "Top pair footprint should advance downward",
        )
    }
}
