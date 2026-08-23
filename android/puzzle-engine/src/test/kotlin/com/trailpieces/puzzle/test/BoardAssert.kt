package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared assertions for grid occupancy and lock-group geometry. */
object BoardAssert {

    fun occupancy(grid: SlotGrid): Map<GridPos, Int> = buildMap {
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val pos = GridPos(row, col)
                val tile = grid.tileAt(pos) ?: continue
                put(pos, tile)
            }
        }
    }

    fun assertOccupancy(grid: SlotGrid, expected: Map<GridPos, Int>) {
        assertEquals(expected, occupancy(grid), "Unexpected board occupancy")
    }

    fun assertTileAt(grid: SlotGrid, pos: GridPos, tileId: Int) {
        assertEquals(tileId, grid.tileAt(pos), "Expected tile $tileId at $pos")
    }

    fun assertEmpty(grid: SlotGrid, pos: GridPos) {
        assertEquals(null, grid.tileAt(pos), "Expected empty at $pos")
    }

    /**
     * Relative geometry of [tileIds] as offsets from the lexicographic-min member.
     * Used to detect peels (geometry changes) vs rigid translations (geometry preserved).
     */
    fun relativeShape(grid: SlotGrid, tileIds: Set<Int>): Map<Int, GridPos> {
        val slots = tileIds.associateWith { id ->
            assertNotNull(grid.slotOfOrNull(id), "Tile $id missing from grid")
        }
        val anchor = slots.values.minWith(compareBy({ it.row }, { it.col }))
        return slots.mapValues { (_, slot) ->
            GridPos(slot.row - anchor.row, slot.col - anchor.col)
        }
    }

    fun assertSameRelativeShape(before: SlotGrid, after: SlotGrid, tileIds: Set<Int>) {
        assertEquals(
            relativeShape(before, tileIds),
            relativeShape(after, tileIds),
            "Lock group $tileIds changed shape (peeled or distorted)",
        )
    }

    /**
     * Every member of [tileIds] moved by the same (dRow, dCol), or none moved.
     * Returns the common displacement, or null if the group was peeled/distorted.
     */
    fun commonDisplacement(
        before: SlotGrid,
        after: SlotGrid,
        tileIds: Set<Int>,
    ): GridPos? {
        val deltas = tileIds.map { id ->
            val from = before.slotOfOrNull(id) ?: return null
            val to = after.slotOfOrNull(id) ?: return null
            GridPos(to.row - from.row, to.col - from.col)
        }
        val first = deltas.first()
        return if (deltas.all { it == first }) first else null
    }

    fun assertRigidOrBlocked(
        before: SlotGrid,
        after: SlotGrid?,
        group: Set<Int>,
    ) {
        if (after == null) return // blocked is allowed
        val delta = commonDisplacement(before, after, group)
        assertNotNull(delta, "Group $group was peeled instead of moving rigidly")
        assertSameRelativeShape(before, after, group)
        assertTrue(
            delta == GridPos(0, 0) || delta.row != 0 || delta.col != 0,
            "Unexpected rigid delta $delta",
        )
    }
}
