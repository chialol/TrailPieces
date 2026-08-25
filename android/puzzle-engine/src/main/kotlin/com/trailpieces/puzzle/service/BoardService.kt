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

    fun beginDrag(
        origin: GridPos,
        grouped: Boolean = true,
        enforceRigidLocks: Boolean = true,
        /** Test / tooling: lift an explicit id set instead of [componentContaining]. */
        liftOverride: Set<Int>? = null,
    ): DragSession? {
        if (!grid.inBounds(origin)) return null
        val tileId = grid.tileAt(origin) ?: return null
        val lifted = liftOverride ?: if (grouped) componentContaining(tileId) else setOf(tileId)
        if (lifted.isEmpty()) return null
        if (tileId !in lifted) return null

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

        // Freeze lock partitions at pointer-down (pre-lift geometry). Mid-drag
        // make-way may park resting tiles into lock geometry; those contacts
        // stay loose until settle recomputes on finger-up. Committed groups
        // remain rigid for the whole drag via this snapshot.
        val dragLocks = if (enforceRigidLocks) {
            FrozenLockGraph.freeze(grid, manifest)
        } else {
            FrozenLockGraph.isolated(manifest)
        }
        return DragSession(
            grid = gridWithHoles,
            liftedTileIds = lifted,
            shapeOffsets = shapeOffsets,
            startAnchor = anchor,
            committedAnchor = anchor,
            manifest = manifest,
            enforceRigidLocks = enforceRigidLocks,
            frozenLocks = dragLocks,
        )
    }

    companion object {
        fun solved(manifest: PuzzleManifest): PuzzleBoard {
            val grid = SlotGrid.solved(manifest)
            return PuzzleBoard(grid, LockGroupService.compute(grid, manifest), manifest)
        }
    }
}
