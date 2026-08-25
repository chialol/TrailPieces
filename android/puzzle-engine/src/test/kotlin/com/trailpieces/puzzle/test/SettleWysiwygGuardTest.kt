package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.ParkLifted
import com.trailpieces.puzzle.service.PuzzleBoard
import com.trailpieces.puzzle.service.SettleService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release is park-only: committed mid-drag layout is never overridden on finger-up.
 */
class SettleWysiwygGuardTest {

    private val cell = 100f
    private val mini = PuzzleFixtures.miniManifest()

    @Test
    fun committedAway_settleEqualsParkLifted_despiteFingerTowardHome() {
        val board = PuzzleFixtures.boardFromRowMajor(mini, listOf(5, 4, 3, 2, 1, 0))
        var session = board.beginDrag(LetterSlots.A)!!
        session = session.tryPush(AxisDirection.Right)!!

        val plain = ParkLifted.apply(session)
        val settled = SettleService.settle(
            session = session,
            fingerDeltaPx = Vec2(0f, -500f),
            cellWidthPx = cell,
            cellHeightPx = cell,
            originalBoard = board,
        )
        assertBoardsEqual(plain, settled)
    }

    @Test
    fun parkLifted_restingMatchesMidDrag() {
        val board = PuzzleFixtures.boardFromRowMajor(mini, listOf(5, 4, 3, 2, 1, 0))
        var session = board.beginDrag(LetterSlots.A)!!
        session = session.tryPush(AxisDirection.Down)!!
        val parked = ParkLifted.apply(session)
        val before = restingOccupancy(session)
        val after = restingOccupancy(parked, session.liftedTileIds)
        assertEquals(before, after, "park must not move resting tiles")
    }

    private fun restingOccupancy(
        session: com.trailpieces.puzzle.service.DragSession,
        excludeLifted: Set<Int> = session.liftedTileIds,
    ): Map<Int, GridPos> = buildMap {
        for (r in 0 until session.grid.rows) {
            for (c in 0 until session.grid.cols) {
                val id = session.grid.tileAt(GridPos(r, c)) ?: continue
                if (id !in excludeLifted) put(id, GridPos(r, c))
            }
        }
    }

    private fun restingOccupancy(
        board: PuzzleBoard,
        excludeLifted: Set<Int>,
    ): Map<Int, GridPos> = buildMap {
        for (r in 0 until board.rows) {
            for (c in 0 until board.cols) {
                val id = board.tileAt(GridPos(r, c)) ?: continue
                if (id !in excludeLifted) put(id, GridPos(r, c))
            }
        }
    }

    private fun assertBoardsEqual(a: PuzzleBoard, b: PuzzleBoard) {
        for (id in mini.tiles.map { it.id }) {
            assertEquals(a.grid.slotOfOrNull(id), b.grid.slotOfOrNull(id), "tile $id")
        }
    }
}
