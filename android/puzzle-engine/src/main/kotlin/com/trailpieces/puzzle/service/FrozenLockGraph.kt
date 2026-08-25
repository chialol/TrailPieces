package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.SlotGrid

/**
 * Pointer-down lock snapshot for mid-drag motion. Exposes [members] only —
 * no [LockGroupService.compute], so accidental mid-drag geometry cannot
 * rigidize new groups during push / swap.
 */
class FrozenLockGraph private constructor(
    private val backing: LockGroupService,
    private val allTileIds: List<Int>,
) {
    fun members(tileId: Int): Set<Int> = backing.members(tileId, allTileIds)

    internal fun asLockGroupService(): LockGroupService = backing

    internal fun allIds(): List<Int> = allTileIds

    companion object {
        fun freeze(grid: SlotGrid, manifest: PuzzleManifest): FrozenLockGraph {
            val ids = manifest.tiles.map { it.id }
            return FrozenLockGraph(LockGroupService.compute(grid, manifest), ids)
        }

        fun isolated(manifest: PuzzleManifest): FrozenLockGraph {
            val ids = manifest.tiles.map { it.id }
            return FrozenLockGraph(LockGroupService.isolated(manifest), ids)
        }
    }
}
