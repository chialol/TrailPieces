package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.service.MidDragMotion
import com.trailpieces.puzzle.service.PlacementService
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests for aim-swap partner hole selection. */
class SwapPartnerHolesTest {

    private val fingerManifest = PuzzleManifest(
        id = "finger-swap-holes",
        title = "Finger swap holes",
        cols = 2,
        rows = 4,
        puzzleWidth = 200,
        puzzleHeight = 400,
        tiles = listOf(
            PuzzleTile(0, GridPos(0, 1), "a"),
            PuzzleTile(1, GridPos(2, 1), "b"),
            PuzzleTile(2, GridPos(0, 0), "t"),
            PuzzleTile(3, GridPos(5, 0), "d"),
            PuzzleTile(4, GridPos(1, 0), "v"),
            PuzzleTile(5, GridPos(5, 1), "x"),
            PuzzleTile(6, GridPos(4, 0), "k"),
            PuzzleTile(7, GridPos(2, 1), "h"),
        ),
    )

    @Test
    fun usesStartHolesAfterMultiCellPushBeforeOffAxisSwap() {
        val board = PuzzleFixtures.playfield(
            fingerManifest,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 5,
                GridPos(2, 0) to 4, GridPos(2, 1) to 3,
                GridPos(3, 0) to 6, GridPos(3, 1) to 7,
            ),
        )
        var session = board.beginDrag(GridPos(1, 1))!!
        session = session.tryPush(AxisDirection.Down)!!
        session = session.tryPush(AxisDirection.Down)!!
        assertEquals(setOf(GridPos(1, 1)), PlacementService.swapPartnerHoles(session))
    }

    @Test
    fun usesStartHolesWhenAimNotAdjacentToCommitted() {
        val board = PuzzleFixtures.playfield(
            fingerManifest,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 5,
                GridPos(2, 0) to 4, GridPos(2, 1) to 3,
                GridPos(3, 0) to 6, GridPos(3, 1) to 7,
            ),
        )
        var session = board.beginDrag(GridPos(1, 1))!!
        session = session.tryPush(AxisDirection.Left)!!
        val aim = GridPos(3, 0) // K — not adjacent to committed (1,0)
        assertEquals(
            setOf(GridPos(1, 1)),
            MidDragMotion.swapPartnerHoles(session, aim),
        )
    }

    @Test
    fun usesStartHolesAfterDiagonalPath() {
        val board = PuzzleFixtures.playfield(
            fingerManifest,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 5,
                GridPos(2, 0) to 4, GridPos(2, 1) to 3,
                GridPos(3, 0) to 6, GridPos(3, 1) to 7,
            ),
        )
        var session = board.beginDrag(GridPos(1, 1))!!
        session = session.tryPush(AxisDirection.Left)!!
        session = session.tryPush(AxisDirection.Down)!!
        session = session.tryPush(AxisDirection.Down)!!
        assertEquals(setOf(GridPos(1, 1)), PlacementService.swapPartnerHoles(session))
    }

    @Test
    fun usesCommittedHolesAfterOneCellPush() {
        val manifest = PuzzleManifest(
            id = "s-holes",
            title = "S holes",
            cols = 2,
            rows = 12,
            puzzleWidth = 200,
            puzzleHeight = 1200,
            tiles = (0..23).map { id ->
                PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
            },
        )
        val board = PuzzleFixtures.playfield(
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
                GridPos(9, 0) to 16, GridPos(9, 1) to 18,
                GridPos(10, 0) to 21, GridPos(10, 1) to 22,
                GridPos(11, 0) to 17, GridPos(11, 1) to 23,
            ),
        )
        var session = board.beginDrag(GridPos(9, 1))!!
        session = session.tryPush(AxisDirection.Down)!!
        assertEquals(setOf(GridPos(10, 1)), session.targetSlots)
        assertEquals(session.targetSlots, PlacementService.swapPartnerHoles(session))
    }
}
