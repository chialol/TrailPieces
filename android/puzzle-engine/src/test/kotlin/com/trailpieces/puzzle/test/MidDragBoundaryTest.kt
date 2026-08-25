package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.service.FrozenLockGraph
import com.trailpieces.puzzle.service.MidDragMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Architecture: mid-drag push uses [FrozenLockGraph], not live lock compute. */
class MidDragBoundaryTest {

    private val manifest = PuzzleFixtures.miniManifest()

    @Test
    fun pushService_acceptsFrozenLockGraph() {
        val board = PuzzleFixtures.boardFromRowMajor(manifest, listOf(5, 4, 3, 2, 1, 0))
        val session = board.beginDrag(com.trailpieces.puzzle.model.GridPos(0, 0), grouped = false)!!
        val pushed = MidDragMotion.tryPush(session, AxisDirection.Down)
        assertNotNull(pushed)
        assertEquals(1, pushed.committedAnchor.row)
    }

    @Test
    fun frozenGraph_membersMatchPointerDownSnapshot() {
        val board = PuzzleFixtures.boardFromRowMajor(manifest, listOf(5, 4, 3, 2, 1, 0))
        val session = board.beginDrag(com.trailpieces.puzzle.model.GridPos(0, 0), grouped = false)!!
        val frozen = session.rigidLocks()
        assertEquals(setOf(0), frozen.members(0))
    }
}
