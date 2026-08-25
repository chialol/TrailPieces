package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.service.LockGroupService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks commit on finger-up only:
 * - Mid-drag: accidental correct contacts stay loose (no mega-group).
 * - Mid-drag: components locked at pointer-down stay rigid (no peel).
 * - Settle: final geometry may form new locks.
 */
class LockCommitOnSettleTest {

    private val manifest = PuzzleFixtures.miniManifest()

    /**
     * Scrambled start — every tile is a singleton. Lift F (id 5) from A, then
     * simulate make-way parking A(0) above C(2) into lock geometry:
     *
     * ```
     * _ 4
     * 0 3
     * 2 1
     * ```
     *
     * Live geometry would lock {0,2}. Push Down must still move only tile 0
     * into the hole — tile 2 stays put until settle.
     */
    @Test
    fun midDragAccidentalLockGeometryDoesNotRigidize() {
        val board = PuzzleFixtures.boardFromRowMajor(
            manifest,
            listOf(5, 4, 3, 2, 1, 0),
        )
        for (id in 0..5) {
            assertEquals(1, board.componentContaining(id).size, "start tile $id")
        }

        val session = board.beginDrag(LetterSlots.A, grouped = true)!!
        assertEquals(setOf(5), session.liftedTileIds)

        val parked = session.copy(
            grid = SlotGrid.empty(2, 3).withCells { cells ->
                cells[LetterSlots.B.index(2)] = 4
                cells[LetterSlots.C.index(2)] = 0
                cells[LetterSlots.D.index(2)] = 3
                cells[LetterSlots.E.index(2)] = 2
                cells[LetterSlots.F.index(2)] = 1
                // A remains EMPTY (lifted footprint)
            },
        )

        val live = LockGroupService.compute(parked.grid, manifest)
        assertEquals(
            setOf(0, 2),
            live.members(0, manifest.tiles.map { it.id }),
            "precondition: live geometry locks A+C",
        )

        val pushed = parked.tryPush(AxisDirection.Down)
        assertNotNull(pushed, "singleton 0 must make way into the hole")
        assertEquals(GridPos(0, 0), pushed.grid.slotOfOrNull(0), "0 into hole")
        assertEquals(
            GridPos(2, 0),
            pushed.grid.slotOfOrNull(2),
            "2 must stay — accidental mid-drag lock must not rigidize",
        )
    }

    /**
     * Same parked geometry as above: settle must commit the A–C lock from
     * final occupancy (lifted F returns onto A).
     */
    @Test
    fun newLockCommitsOnSettle() {
        val board = PuzzleFixtures.boardFromRowMajor(
            manifest,
            listOf(5, 4, 3, 2, 1, 0),
        )
        val session = board.beginDrag(LetterSlots.A, grouped = true)!!
        val parked = session.copy(
            grid = SlotGrid.empty(2, 3).withCells { cells ->
                cells[LetterSlots.B.index(2)] = 4
                cells[LetterSlots.C.index(2)] = 0
                cells[LetterSlots.D.index(2)] = 3
                cells[LetterSlots.E.index(2)] = 2
                cells[LetterSlots.F.index(2)] = 1
            },
        )

        val settled = parked.settle()
        assertEquals(5, settled.grid.tileAt(LetterSlots.A))
        assertEquals(0, settled.grid.tileAt(LetterSlots.C))
        assertEquals(2, settled.grid.tileAt(LetterSlots.E))
        assertTrue(
            settled.componentContaining(0).contains(2),
            "A–C lock must commit on settle when geometry is correct",
        )
    }

    /**
     * Committed vertical pair {3,5} at pointer-down must still move rigidly
     * when a singleton pushes into it (no peel).
     */
    @Test
    fun committedPairDoesNotPeelMidDrag() {
        val board = PuzzleFixtures.lockedVerticalPairInCol1(manifest)
        assertEquals(setOf(3, 5), board.componentContaining(3))

        val session = board.beginDrag(GridPos(0, 1), grouped = true)!!
        assertEquals(setOf(2), session.liftedTileIds)

        val before = session.grid
        val pushed = session.tryPush(AxisDirection.Down)
        assertNotNull(pushed)
        BoardAssert.assertSameRelativeShape(before, pushed.grid, setOf(3, 5))
        assertNotNull(BoardAssert.commonDisplacement(before, pushed.grid, setOf(3, 5)))
    }
}
