package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.service.LockGroupService
import com.trailpieces.puzzle.service.PushService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PushServiceTest {

    private val manifest = PuzzleFixtures.miniManifest()
    private val allIds get() = manifest.tiles.map { it.id }

    /** Segment mechanics without rigid groups (isolated locks). */
    private fun pushLoose(
        grid: SlotGrid,
        holes: Set<GridPos>,
        lifted: Set<Int>,
        direction: AxisDirection,
    ) = PushService.tryPush(
        grid = grid,
        holes = holes,
        liftedTileIds = lifted,
        direction = direction,
        locks = LockGroupService.isolated(manifest),
        allTileIds = allIds,
    )

    private fun pushRigid(
        grid: SlotGrid,
        holes: Set<GridPos>,
        lifted: Set<Int>,
        direction: AxisDirection,
    ) = PushService.tryPush(
        grid = grid,
        holes = holes,
        liftedTileIds = lifted,
        direction = direction,
        locks = LockGroupService.compute(grid, manifest),
        allTileIds = allIds,
    )

    @Test
    fun singleHoleDownSlidesTileFromBelow() {
        val board = PuzzleFixtures.boardFromRowMajor(manifest, listOf(0, 1, 2, 3, 4, 5))
        val session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        val next = pushLoose(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Down)
        assertNotNull(next)
        BoardAssert.assertTileAt(next.grid, GridPos(0, 0), 2)
        BoardAssert.assertEmpty(next.grid, GridPos(1, 0))
    }

    @Test
    fun singleHoleRightSlidesTileFromRight() {
        val board = PuzzleFixtures.boardFromRowMajor(manifest, listOf(0, 1, 2, 3, 4, 5))
        val session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        val next = pushLoose(session.grid, setOf(GridPos(0, 0)), setOf(0), AxisDirection.Right)
        assertNotNull(next)
        BoardAssert.assertTileAt(next.grid, GridPos(0, 0), 1)
        BoardAssert.assertEmpty(next.grid, GridPos(0, 1))
    }

    @Test
    fun edgePushBlockedWhenSourceOutOfBounds() {
        val board = PuzzleFixtures.boardFromRowMajor(manifest, listOf(0, 1, 2, 3, 4, 5))
        val session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        assertNull(pushLoose(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Up))
        assertNull(pushLoose(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Left))
    }

    @Test
    fun emptyHolesReturnsNull() {
        val grid = PuzzleFixtures.solvedBoard(manifest).grid
        assertNull(pushLoose(grid, emptySet(), emptySet(), AxisDirection.Down))
    }

    @Test
    fun contiguousVerticalHolesNeedOneFillerBelow() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        assertEquals(setOf(0, 2, 4), session.liftedTileIds)
        assertNull(pushRigid(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Down))
    }

    @Test
    fun verticalDigominoPushDownMovesRestingTiles() {
        // Resting row-1 tiles are not locked to row-2, so each is a size-1 slide.
        val board = PuzzleFixtures.boardFromRowMajor(manifest, listOf(0, 1, 3, 2, 4, 5))
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        assertEquals(setOf(0, 1), session.liftedTileIds)
        val next = pushRigid(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Down)
        assertNotNull(next)
        BoardAssert.assertTileAt(next.grid, GridPos(0, 0), 3)
        BoardAssert.assertTileAt(next.grid, GridPos(0, 1), 2)
        BoardAssert.assertEmpty(next.grid, GridPos(1, 0))
        BoardAssert.assertEmpty(next.grid, GridPos(1, 1))
    }

    @Test
    fun multiSegmentFailsAtomicallyWhenOneSideBlocked() {
        val board = PuzzleFixtures.lockedLTriominoBoard(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = true)!!
        assertEquals(setOf(0, 2, 3), session.liftedTileIds)
        assertNull(pushRigid(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Left))
    }

    @Test
    fun emptySourceBlocksWhenDestinationOccupied() {
        // Hole at top with nothing above for Up — blocked.
        val board = PuzzleFixtures.boardFromRowMajor(manifest, listOf(0, 1, 2, 3, 4, 5))
        val session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        assertNull(pushLoose(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Up))
    }

    @Test
    fun footprintMaySlideIntoPersistentEmpty() {
        val board = PuzzleFixtures.playfield(
            manifest,
            rows = 3,
            placements = mapOf(
                GridPos(0, 0) to 0,
                GridPos(0, 1) to 1,
                // (1,0) empty
                GridPos(1, 1) to 3,
                GridPos(2, 0) to 4,
                GridPos(2, 1) to 5,
            ),
        )
        val session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        val next = pushLoose(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Down)
        assertNotNull(next)
        assertEquals(setOf(GridPos(1, 0)), next.newHoles)
        // Resting grid unchanged — only the hole moved into the empty
        BoardAssert.assertTileAt(next.grid, GridPos(1, 1), 3)
    }

    @Test
    fun rigidLocksBlockPeelOnNearlySolvedBoard() {
        // Lift one tile from a solved board; remaining tiles are one giant lock.
        // Push must not peel a single neighbor — block instead.
        val board = PuzzleFixtures.solvedBoard(manifest)
        val session = board.beginDrag(GridPos(0, 0), grouped = false)!!
        assertNull(pushRigid(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Down))
        assertNull(pushRigid(session.grid, session.targetSlots, session.liftedTileIds, AxisDirection.Right))
    }
}
