package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connected component A,B,C moved to position P — what happens to resting tiles.
 * These describe current (correct) lift+push behavior for a group you control.
 */
class ConnectedComponentMoveTest {

    private val manifest = PuzzleFixtures.miniManifest()

    @Test
    fun verticalTrioPushRightFillsHolesWithSideColumn() {
        // Lift locked {0,2,4} in col 0; push Right → col1 tiles slide into holes
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        assertEquals(setOf(0, 2, 4), session.liftedTileIds)

        val pushed = session.tryPush(AxisDirection.Right)
        assertNotNull(pushed)
        assertEquals(GridPos(0, 1), pushed.committedAnchor)

        // Resting tiles that filled the vacated column
        BoardAssert.assertOccupancy(
            pushed.grid,
            mapOf(
                GridPos(0, 0) to 5,
                GridPos(1, 0) to 1,
                GridPos(2, 0) to 3,
            ),
        )
        assertEquals(
            setOf(GridPos(0, 1), GridPos(1, 1), GridPos(2, 1)),
            pushed.grid.emptySlots(),
        )

        val settled = pushed.settle(manifest)
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 1), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 1), 2)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 1), 4)
        assertEquals(setOf(0, 2, 4), settled.componentContaining(0))
    }

    @Test
    fun verticalTrioCannotPushDownOffBoard() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        assertNull(session.tryPush(AxisDirection.Down))
        assertNull(session.tryPush(AxisDirection.Up))
        assertNull(session.tryPush(AxisDirection.Left))
    }

    @Test
    fun horizontalPairPushDownCascadesRestingTilesBehindFootprint() {
        val board = PuzzleFixtures.horizontalPairWithLooseRowBelow(manifest)
        // 0 1 / 3 2 / 4 5
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        val once = session.tryPush(AxisDirection.Down)!!
        BoardAssert.assertTileAt(once.grid, GridPos(0, 0), 3)
        BoardAssert.assertTileAt(once.grid, GridPos(0, 1), 2)
        assertEquals(GridPos(1, 0), once.committedAnchor)

        val twice = once.tryPush(AxisDirection.Down)!!
        // Bottom {4,5} locked horizontally — slides as a rigid unit
        BoardAssert.assertTileAt(twice.grid, GridPos(1, 0), 4)
        BoardAssert.assertTileAt(twice.grid, GridPos(1, 1), 5)
        BoardAssert.assertEmpty(twice.grid, GridPos(2, 0))
        BoardAssert.assertEmpty(twice.grid, GridPos(2, 1))
        assertEquals(GridPos(2, 0), twice.committedAnchor)

        val settled = twice.settle(manifest)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 1), 1)
    }

    @Test
    fun lTriominoPushRightMovesRestingTilesIntoEachHoleSegment() {
        val board = PuzzleFixtures.lockedLTriominoBoard(manifest)
        // 0 5 / 2 3 / 4 1 — lift {0,2,3}
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        assertEquals(setOf(0, 2, 3), session.liftedTileIds)

        // Right: row0 hole at (0,0) needs source (0,1)=5; row1 holes (1,0)+(1,1) contiguous
        // → source (1,2) OOB → entire push blocked
        assertNull(session.tryPush(AxisDirection.Right))
    }

    @Test
    fun dragEngineMovesConnectedPairAlongAxisPath() {
        val board = PuzzleFixtures.horizontalPairWithLooseRowBelow(manifest)
        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(0, 0)))
        engine.moveFinger(Vec2(0f, 160f), cellWidthPx = 100f, cellHeightPx = 100f)
        assertEquals(GridPos(2, 0), engine.drag!!.committedAnchor)
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 1), 1)
    }

    @Test
    fun restingTilesDoNotTeleportAcrossUnrelatedColumns() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val before = BoardAssert.occupancy(board.grid)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        val pushed = session.tryPush(AxisDirection.Right)!!
        // Only col0/col1 swap roles for the three rows — no tile invents a new id
        val afterResting = BoardAssert.occupancy(pushed.grid)
        assertEquals(setOf(5, 1, 3), afterResting.values.toSet())
        assertEquals(before.values.toSet() - setOf(0, 2, 4), afterResting.values.toSet())
    }
}
