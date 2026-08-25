package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.PlacementService
import com.trailpieces.puzzle.service.PuzzleBoard
import com.trailpieces.puzzle.service.settlePlain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settle must match mid-drag committed layout (WYSIWYG). Locks recompute on
 * release; tile positions must not jump unless cutline / completing-home swap
 * with clear finger intent.
 */
class SettleHonorsCommittedTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "settle-committed",
        title = "Settle honors committed",
        cols = 2,
        rows = 12,
        puzzleWidth = 200,
        puzzleHeight = 1200,
        tiles = (0..23).map { id ->
            PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
        },
    )

    /** User dump: drag S down-left under Q; mid-drag hole at (10,0). */
    private fun sUnderQBoard(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 12,
        placements = mapOf(
            GridPos(0, 0) to 11, GridPos(0, 1) to 15,
            GridPos(1, 0) to 9, GridPos(1, 1) to 2,
            GridPos(2, 0) to 4, GridPos(2, 1) to 1,
            GridPos(3, 0) to 8, GridPos(3, 1) to 10,
            GridPos(4, 0) to 19, GridPos(4, 1) to 7,
            GridPos(5, 0) to 6, GridPos(5, 1) to 0,
            GridPos(6, 0) to 14, GridPos(6, 1) to 20,
            GridPos(7, 0) to 5, GridPos(7, 1) to 12,
            GridPos(8, 0) to 13, GridPos(8, 1) to 3,
            GridPos(9, 0) to 16, GridPos(9, 1) to 18, // Q, S
            GridPos(10, 0) to 21, GridPos(10, 1) to 22,
            GridPos(11, 0) to 17, GridPos(11, 1) to 23,
        ),
    )

    @Test
    fun sDragUnderQ_committedAwayFromStart_sticksOnRelease() {
        val engine = DragEngine(manifest, sUnderQBoard())
        assertTrue(engine.startDrag(GridPos(9, 1))) // S at T

        engine.moveFinger(Vec2(0f, 60f), cell, cell) // Down
        engine.moveFinger(Vec2(-60f, 0f), cell, cell) // Left onto hole col

        val mid = engine.drag!!
        assertEquals(GridPos(10, 0), mid.committedAnchor, "precondition: committed under Q")
        BoardAssert.assertEmpty(mid.grid, GridPos(10, 0))
        assertEquals(16, mid.grid.tileAt(GridPos(9, 0)), "mid-drag: Q above")
        assertEquals(22, mid.grid.tileAt(GridPos(9, 1)), "mid-drag: W beside Q")
        assertEquals(21, mid.grid.tileAt(GridPos(10, 1)), "mid-drag: V beside hole")
        assertEquals(
            setOf(18),
            com.trailpieces.puzzle.service.LockGroupService.compute(mid.grid, manifest)
                .members(18, manifest.tiles.map { it.id }),
            "S stays loose mid-drag",
        )

        val settled = engine.endDrag()
        assertEquals(18, settled.grid.tileAt(GridPos(10, 0)), "S must stay under Q")
        assertEquals(16, settled.grid.tileAt(GridPos(9, 0)), "Q stays above")
        assertEquals(22, settled.grid.tileAt(GridPos(9, 1)), "W stays beside Q")
        assertEquals(21, settled.grid.tileAt(GridPos(10, 1)), "V stays below W")
    }

    @Test
    fun committedAwayFromStart_zeroFinger_equalsSettlePlain() {
        val board = sUnderQBoard()
        var session = board.beginDrag(GridPos(9, 1))!!
        session = session.tryPush(com.trailpieces.puzzle.model.AxisDirection.Down)!!
        session = session.tryPush(com.trailpieces.puzzle.model.AxisDirection.Left)!!

        val plain = session.settlePlain()
        val preferred = PlacementService.settlePreferred(
            session = session,
            fingerDeltaPx = Vec2.Zero,
            cellWidthPx = cell,
            cellHeightPx = cell,
            originalBoard = board,
        )
        assertEquals(plain.grid.rows, preferred.grid.rows)
        for (id in manifest.tiles.map { it.id }) {
            assertEquals(
                plain.grid.slotOfOrNull(id),
                preferred.grid.slotOfOrNull(id),
                "tile $id slot",
            )
        }
    }

    /** Scrambled pre-drag board must not override committed mid-drag layout. */
    @Test
    fun latentHomeSnap_scrambledOriginal_stillSettlePlain() {
        val board = sUnderQBoard()
        var session = board.beginDrag(GridPos(9, 1))!!
        session = session.tryPush(com.trailpieces.puzzle.model.AxisDirection.Down)!!
        session = session.tryPush(com.trailpieces.puzzle.model.AxisDirection.Left)!!
        assertTrue(session.committedAnchor != session.startAnchor)

        val scrambled = PuzzleFixtures.playfield(
            manifest,
            rows = 12,
            placements = mapOf(
                GridPos(0, 0) to 11, GridPos(0, 1) to 15,
                GridPos(1, 0) to 9, GridPos(1, 1) to 2,
                GridPos(2, 0) to 4, GridPos(2, 1) to 1,
                GridPos(3, 0) to 8, GridPos(3, 1) to 10,
                GridPos(4, 0) to 19, GridPos(4, 1) to 7,
                GridPos(5, 0) to 6, GridPos(5, 1) to 0,
                GridPos(6, 0) to 14, GridPos(6, 1) to 20,
                GridPos(7, 0) to 5, GridPos(7, 1) to 12,
                GridPos(8, 0) to 13, GridPos(8, 1) to 3,
                GridPos(9, 0) to 18, GridPos(9, 1) to 16, // S at home, Q swapped
                GridPos(10, 0) to 21, GridPos(10, 1) to 22,
                GridPos(11, 0) to 17, GridPos(11, 1) to 23,
            ),
        )

        val plain = session.settlePlain()
        val preferred = PlacementService.settlePreferred(
            session = session,
            fingerDeltaPx = Vec2.Zero,
            cellWidthPx = cell,
            cellHeightPx = cell,
            originalBoard = scrambled,
        )
        assertEquals(GridPos(10, 0), preferred.grid.slotOfOrNull(18), "S must not snap to home in scrambled original")
        for (id in manifest.tiles.map { it.id }) {
            assertEquals(plain.grid.slotOfOrNull(id), preferred.grid.slotOfOrNull(id), "tile $id")
        }
    }

    @Test
    fun weakFingerTowardHome_committedAway_sticksPlain() {
        val board = sUnderQBoard()
        var session = board.beginDrag(GridPos(9, 1))!!
        session = session.tryPush(com.trailpieces.puzzle.model.AxisDirection.Down)!!
        session = session.tryPush(com.trailpieces.puzzle.model.AxisDirection.Left)!!

        val plain = session.settlePlain()
        val preferred = PlacementService.settlePreferred(
            session = session,
            fingerDeltaPx = Vec2(0f, 30f), // toward home but below dominant threshold
            cellWidthPx = cell,
            cellHeightPx = cell,
            originalBoard = board,
        )
        assertEquals(GridPos(10, 0), preferred.grid.slotOfOrNull(18))
        for (id in manifest.tiles.map { it.id }) {
            assertEquals(plain.grid.slotOfOrNull(id), preferred.grid.slotOfOrNull(id), "tile $id")
        }
    }

    @Test
    fun strongFingerTowardHome_committedUnderQ_sticksPlain() {
        val board = sUnderQBoard()
        var session = board.beginDrag(GridPos(9, 1))!!
        session = session.tryPush(com.trailpieces.puzzle.model.AxisDirection.Down)!!
        session = session.tryPush(com.trailpieces.puzzle.model.AxisDirection.Left)!!
        assertEquals(GridPos(10, 0), session.committedAnchor)

        // User dump [62]: finger=Vec2(-469.5, -117.75) while committed (10,0).
        // Old settle snapped S home (9,0) and shuffled neighbors; must stay plain.
        val finger = Vec2(-469.5f, -117.75f)
        val plain = session.settlePlain()
        val preferred = PlacementService.settlePreferred(
            session = session,
            fingerDeltaPx = finger,
            cellWidthPx = cell,
            cellHeightPx = cell,
            originalBoard = board,
        )
        assertEquals(GridPos(10, 0), preferred.grid.slotOfOrNull(18), "S must not snap home on release")
        for (id in manifest.tiles.map { it.id }) {
            assertEquals(plain.grid.slotOfOrNull(id), preferred.grid.slotOfOrNull(id), "tile $id")
        }
    }

    @Test
    fun emptyHoleSlide_committedOneCellDown_settlePlainNotHome() {
        val mini = PuzzleManifest(
            id = "empty-hole",
            title = "Empty hole slide",
            cols = 2,
            rows = 3,
            puzzleWidth = 200,
            puzzleHeight = 300,
            tiles = listOf(
                PuzzleTile(0, GridPos(0, 0), "A"),
                PuzzleTile(1, GridPos(0, 1), "B"),
                PuzzleTile(2, GridPos(1, 0), "C"),
                PuzzleTile(3, GridPos(1, 1), "D"),
                PuzzleTile(4, GridPos(2, 0), "E"),
                PuzzleTile(5, GridPos(2, 1), "F"),
            ),
        )
        val board = PuzzleFixtures.boardWithPlacements(
            mini,
            mapOf(
                GridPos(0, 0) to 0, // A at home
                GridPos(0, 1) to 5, // F breaks lock with B
                GridPos(1, 1) to 3,
                GridPos(2, 0) to 4,
                GridPos(2, 1) to 2,
                // (1,0) persistent empty
            ),
        )
        val engine = DragEngine(mini, board)
        assertTrue(engine.startDrag(GridPos(0, 0)))
        engine.moveFinger(Vec2(0f, 60f), cell, cell)

        val mid = engine.drag!!
        assertEquals(GridPos(1, 0), mid.committedAnchor)
        assertTrue(mid.committedAnchor != mid.startAnchor)

        val settled = engine.endDrag()
        assertEquals(0, settled.grid.tileAt(GridPos(1, 0)), "A slides into empty, not home")
    }
}
