package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.SlotGrid

/**
 * Resting board: tile occupancy + lock groups.
 */
data class PuzzleBoard(
    val grid: SlotGrid,
    val locks: LockGroupService,
    val manifest: PuzzleManifest,
) {
    val cols: Int get() = grid.cols
    val rows: Int get() = grid.rows

    val isSolved: Boolean
        get() = manifest.tiles.all { tile ->
            grid.slotOfOrNull(tile.id) == tile.home
        }

    fun tileAt(pos: GridPos): Int? = grid.tileAt(pos)

    fun componentContaining(tileId: Int): Set<Int> =
        locks.members(tileId, manifest.tiles.map { it.id })

    fun beginDrag(origin: GridPos, grouped: Boolean = true): DragSession? {
        if (!grid.inBounds(origin)) return null
        val tileId = grid.tileAt(origin) ?: return null
        val lifted = if (grouped) componentContaining(tileId) else setOf(tileId)
        if (lifted.isEmpty()) return null

        val slots = lifted.mapNotNull { grid.slotOfOrNull(it) }
        if (slots.size != lifted.size) return null

        val anchor = slots.minWith(compareBy({ it.row }, { it.col }))
        val shapeOffsets = buildMap {
            for (id in lifted) {
                val slot = grid.slotOfOrNull(id) ?: return null
                put(id, slot.offset(-anchor.row, -anchor.col))
            }
        }

        val gridWithHoles = grid.withCells { cells ->
            slots.forEach { cells[it.index(cols)] = EMPTY }
        }

        return DragSession(
            grid = gridWithHoles,
            liftedTileIds = lifted,
            shapeOffsets = shapeOffsets,
            startAnchor = anchor,
            committedAnchor = anchor,
        )
    }

    companion object {
        fun solved(manifest: PuzzleManifest): PuzzleBoard {
            val grid = SlotGrid.solved(manifest)
            return PuzzleBoard(grid, LockGroupService.compute(grid, manifest), manifest)
        }
    }
}
