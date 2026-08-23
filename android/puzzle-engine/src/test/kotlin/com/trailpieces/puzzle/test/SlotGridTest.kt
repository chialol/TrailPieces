package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlotGridTest {

    private val manifest = PuzzleFixtures.miniManifest()

    @Test
    fun emptyGridHasAllEmptySlots() {
        val grid = SlotGrid.empty(2, 3)
        assertEquals(6, grid.emptySlots().size)
        assertNull(grid.tileAt(GridPos(0, 0)))
        assertFalse(grid.inBounds(GridPos(-1, 0)))
        assertFalse(grid.inBounds(GridPos(0, 2)))
    }

    @Test
    fun solvedPlacesEveryTileAtHome() {
        val grid = SlotGrid.solved(manifest)
        for (tile in manifest.tiles) {
            assertEquals(tile.id, grid.tileAt(tile.home))
            assertEquals(tile.home, grid.slotOf(tile.id))
        }
        assertTrue(grid.emptySlots().isEmpty())
    }

    @Test
    fun withCellIsImmutable() {
        val original = SlotGrid.solved(manifest)
        val modified = original.withCell(GridPos(0, 0), EMPTY)
        assertEquals(0, original.tileAt(GridPos(0, 0)))
        assertNull(modified.tileAt(GridPos(0, 0)))
        assertEquals(setOf(GridPos(0, 0)), modified.emptySlots())
    }

    @Test
    fun slotOfThrowsWhenMissing() {
        val grid = SlotGrid.empty(2, 3)
        assertFailsWith<IllegalArgumentException> { grid.slotOf(0) }
        assertNull(grid.slotOfOrNull(0))
    }

    @Test
    fun copyCellsIsIndependent() {
        val grid = SlotGrid.solved(manifest)
        val copy = grid.copyCells()
        copy[0] = EMPTY
        assertEquals(0, grid.tileAt(GridPos(0, 0)))
    }
}
