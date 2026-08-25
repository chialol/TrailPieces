package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.model.step

/**
 * Axis-aligned hole motion helpers used by [PushService]. No settle logic;
 * no dependency on [PlacementService].
 */
object AxisMotion {

    fun trySameSizeSwap(
        grid: SlotGrid,
        holes: Set<GridPos>,
        stepped: Set<GridPos>,
        liftedTileIds: Set<Int>,
        locks: FrozenLockGraph,
    ): PushResult? {
        if (holes.isEmpty() || stepped.size != holes.size) return null
        if (stepped.any { !grid.inBounds(it) }) return null

        val contactIds = stepped.mapNotNull { pos ->
            grid.tileAt(pos)?.takeIf { it !in liftedTileIds }
        }.toSet()
        if (contactIds.isEmpty()) return null

        val seed = contactIds.first()
        val group = locks.members(seed)
            .filter { it !in liftedTileIds && grid.slotOfOrNull(it) != null }
            .toSet()
        if (group.size != holes.size) return null
        if (!contactIds.all { it in group }) return null
        val groupSlots = group.map { grid.slotOf(it) }.toSet()
        if (groupSlots != stepped) return null
        val shift = GridGeometry.translation(holes, groupSlots) ?: return null
        val destinations = group.map { id ->
            val from = grid.slotOf(id)
            val to = from.offset(-shift.first, -shift.second)
            if (!grid.inBounds(to) || to !in holes) return null
            id to to
        }
        val next = grid.withCells { cells ->
            for (id in group) {
                cells[grid.slotOf(id).index(grid.cols)] = EMPTY
            }
            for ((id, to) in destinations) {
                cells[to.index(grid.cols)] = id
            }
        }
        return PushResult(next, groupSlots)
    }

    fun tryNearestSameSizeSwapAlongAxis(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        locks: FrozenLockGraph,
        direction: AxisDirection,
    ): PushResult? {
        if (holes.isEmpty()) return null
        var landing = holes.map { it.step(direction) }.toSet()
        repeat(grid.rows + grid.cols) {
            if (landing.any { !grid.inBounds(it) }) return null
            trySameSizeSwap(
                grid = grid,
                holes = holes,
                stepped = landing,
                liftedTileIds = liftedTileIds,
                locks = locks,
            )?.let { return it }
            landing = landing.map { it.step(direction) }.toSet()
        }
        return null
    }

    fun tryNearestEmptyAlongAxis(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        direction: AxisDirection,
    ): PushResult? {
        if (liftedTileIds.size != 1 || holes.size != 1) return null
        var landing = holes.map { it.step(direction) }.toSet()
        repeat(grid.rows + grid.cols) {
            if (landing.any { !grid.inBounds(it) }) return null
            if (landing.all { grid.tileAt(it) == null || it in holes }) {
                return PushResult(grid, landing)
            }
            landing = landing.map { it.step(direction) }.toSet()
        }
        return null
    }
}
