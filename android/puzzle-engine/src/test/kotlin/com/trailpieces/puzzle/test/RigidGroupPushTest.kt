package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.LockGroupService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resting locked groups are rigid: a lone tile pushing into a connected
 * component either shifts the whole group one step or is blocked — never peels
 * a single member off.
 */
class RigidGroupPushTest {

    private val manifest = PuzzleFixtures.miniManifest()

    @Test
    fun loneTileCannotPeelLockedVerticalPair() {
        val board = PuzzleFixtures.lockedVerticalPairInCol1(manifest)
        assertEquals(setOf(3, 5), board.componentContaining(3))

        val session = board.beginDrag(GridPos(0, 1), grouped = true)!!
        assertEquals(setOf(2), session.liftedTileIds)

        val before = session.grid
        val group = setOf(3, 5)
        val pushed = session.tryPush(AxisDirection.Down)

        if (pushed == null) {
            BoardAssert.assertSameRelativeShape(before, before, group)
            return
        }
        val delta = BoardAssert.commonDisplacement(before, pushed.grid, group)
        assertNotNull(
            delta,
            "Peel detected: locked pair {3,5} did not move as a rigid body",
        )
        BoardAssert.assertSameRelativeShape(before, pushed.grid, group)
    }

    @Test
    fun loneTileCannotPeelLockedHorizontalPair() {
        val board = PuzzleFixtures.lockedHorizontalPairOnRow0(manifest)
        assertEquals(setOf(0, 1), board.componentContaining(0))

        val session = board.beginDrag(GridPos(1, 0), grouped = false)!!
        assertEquals(setOf(3), session.liftedTileIds)

        val before = session.grid
        val pushed = session.tryPush(AxisDirection.Up)
        BoardAssert.assertRigidOrBlocked(before, pushed?.grid, setOf(0, 1))
        if (pushed != null) {
            if (before.slotOfOrNull(0) != pushed.grid.slotOfOrNull(0)) {
                assertTrue(
                    before.slotOfOrNull(1) != pushed.grid.slotOfOrNull(1),
                    "Tile 0 moved but tile 1 stayed — peeled horizontal pair",
                )
            }
        }
    }

    @Test
    fun axialPushIntoVerticalPairTunnelsInsteadOfPeeling() {
        // Hole directly above a vertical lock: the pair shifts toward the hole
        // and the lifted tile tunnels to the far side (same idea as case 1).
        val board = PuzzleFixtures.lockedVerticalPairInCol1(manifest)
        val session = board.beginDrag(GridPos(0, 1), grouped = true)!!
        val pushed = session.tryPush(AxisDirection.Down)
        assertNotNull(pushed)
        BoardAssert.assertSameRelativeShape(session.grid, pushed.grid, setOf(3, 5))
        val d3 = BoardAssert.commonDisplacement(session.grid, pushed.grid, setOf(3, 5))
        assertNotNull(d3)
    }

    @Test
    fun rigidGroupSlidesWhenSpaceAllowsPerpendicular() {
        // After the top pair has already moved down one row, holes sit on {4,5}.
        // Pushing again slides that horizontal lock as one rigid body.
        val board = PuzzleFixtures.horizontalPairWithLooseRowBelow(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        val once = session.tryPush(AxisDirection.Down)
        assertNotNull(once)

        val beforeBottom = once.grid
        val pushed = once.tryPush(AxisDirection.Down)
        assertNotNull(pushed)
        BoardAssert.assertTileAt(pushed.grid, GridPos(1, 0), 4)
        BoardAssert.assertTileAt(pushed.grid, GridPos(1, 1), 5)
        BoardAssert.assertEmpty(pushed.grid, GridPos(2, 0))
        BoardAssert.assertEmpty(pushed.grid, GridPos(2, 1))
        assertEquals(GridPos(2, 0), pushed.committedAnchor)
        BoardAssert.assertSameRelativeShape(beforeBottom, pushed.grid, setOf(4, 5))
    }

    @Test
    fun rigidGroupAtEdgeShiftsTowardHoleWithoutPeeling() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(0, 1), grouped = true)!!
        assertEquals(setOf(5), session.liftedTileIds)

        val before = session.grid
        val pushed = session.tryPush(AxisDirection.Left)
        assertNotNull(pushed)
        BoardAssert.assertSameRelativeShape(before, pushed.grid, setOf(0, 2, 4))
    }

    @Test
    fun looseTileIntoLooseTileStillSlides() {
        val board = PuzzleFixtures.boardFromRowMajor(
            manifest,
            listOf(5, 4, 3, 2, 1, 0),
        )
        assertEquals(1, board.componentContaining(5).size)
        assertEquals(1, board.componentContaining(3).size)

        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        val pushed = session.tryPush(AxisDirection.Down)
        assertNotNull(pushed)
        BoardAssert.assertTileAt(pushed.grid, GridPos(0, 0), 3)
        BoardAssert.assertEmpty(pushed.grid, GridPos(1, 0))
    }

    @Test
    fun dragEngineLoneTileDoesNotPeelLockedPair() {
        val board = PuzzleFixtures.lockedVerticalPairInCol1(manifest)
        val locksBefore = LockGroupService.compute(board.grid, manifest)
        assertEquals(setOf(3, 5), locksBefore.members(3, manifest.tiles.map { it.id }))

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(0, 1)))
        val beforeSlots = mapOf(
            3 to board.grid.slotOf(3),
            5 to board.grid.slotOf(5),
        )
        engine.moveFinger(Vec2(0f, 60f), cellWidthPx = 100f, cellHeightPx = 100f)

        val drag = engine.drag
        if (drag == null || drag.committedAnchor == GridPos(0, 1)) {
            BoardAssert.assertTileAt(board.grid, beforeSlots.getValue(3), 3)
            BoardAssert.assertTileAt(board.grid, beforeSlots.getValue(5), 5)
            return
        }
        val after3 = drag.grid.slotOfOrNull(3)
        val after5 = drag.grid.slotOfOrNull(5)
        assertNotNull(after3)
        assertNotNull(after5)
        val d3 = GridPos(after3.row - beforeSlots.getValue(3).row, after3.col - beforeSlots.getValue(3).col)
        val d5 = GridPos(after5.row - beforeSlots.getValue(5).row, after5.col - beforeSlots.getValue(5).col)
        assertEquals(d3, d5, "Locked pair peeled under DragEngine push")
    }

    @Test
    fun cannotPeelMemberFromLTriomino() {
        val board = PuzzleFixtures.lockedLTriominoBoard(manifest)
        val group = setOf(0, 2, 3)
        assertEquals(group, board.componentContaining(0))

        val session = board.beginDrag(GridPos(0, 1), grouped = true)!!
        val before = session.grid
        val pushed = session.tryPush(AxisDirection.Left)
        BoardAssert.assertRigidOrBlocked(before, pushed?.grid, group)
    }
}
