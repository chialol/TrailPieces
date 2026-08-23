package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.model.step

/**
 * Result of a successful axis push. [newHoles] is where the lifted footprint
 * should sit afterward (may jump more than one cell when tunneling through a
 * rigid group).
 */
data class PushResult(
    val grid: SlotGrid,
    val newHoles: Set<GridPos>,
)

/**
 * Axis-aligned push for arbitrary lifted footprints (not just rectangles).
 *
 * Locked groups (size > 1) move as rigid bodies. When a push would peel a
 * member, the whole group shifts; lone tiles in the way are displaced to make
 * room. Tunneling through an axial lock group can advance the hole past the
 * whole group in one push.
 */
object PushService {

    fun tryPush(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        direction: AxisDirection,
        locks: LockGroupService,
        allTileIds: Iterable<Int>,
    ): PushResult? {
        if (holes.isEmpty()) return null

        // Footprint can slide into already-empty cells (persistent empties) with
        // no resting tiles moved — just relocate the hole.
        val stepped = holes.map { it.step(direction) }.toSet()
        if (stepped.all { grid.inBounds(it) && grid.tileAt(it) == null && it !in holes }) {
            return PushResult(grid, stepped)
        }
        // Also allow stepping into a cell that is part of the current footprint
        // (multi-tile slide) when every stepped cell is empty or already a hole.
        if (stepped.all { grid.inBounds(it) && (grid.tileAt(it) == null || it in holes) } &&
            stepped != holes
        ) {
            return PushResult(grid, stepped)
        }

        val moves = planMovesWithMakeWay(grid, holes, liftedTileIds, locks, allTileIds, direction)
            ?: return null
        if (moves.isEmpty()) return null
        if (!validateMoves(grid, moves, liftedTileIds)) return null

        val next = applyMoves(grid, moves)
        val newHoles = resolveNewHoles(holes, direction, next) ?: return null
        return PushResult(next, newHoles)
    }

    /**
     * Prefer a normal +1 step of the footprint. If those cells are occupied
     * (axial tunnel through a rigid group), snap the footprint to the far-side
     * empties created by the shift, preserving shape relative to the min hole.
     */
    private fun resolveNewHoles(
        oldHoles: Set<GridPos>,
        direction: AxisDirection,
        next: SlotGrid,
    ): Set<GridPos>? {
        val stepped = oldHoles.map { it.step(direction) }.toSet()
        if (stepped.all { next.inBounds(it) && next.tileAt(it) == null }) {
            return stepped
        }

        // Tunnel: empties further along [direction] from each old hole.
        val oldAnchor = oldHoles.minWith(compareBy({ it.row }, { it.col }))
        val offsets = oldHoles.map { GridPos(it.row - oldAnchor.row, it.col - oldAnchor.col) }

        fun candidateFrom(far: GridPos): Set<GridPos>? {
            val holes = offsets.map { far.offset(it.row, it.col) }.toSet()
            if (holes.any { !next.inBounds(it) || next.tileAt(it) != null }) return null
            return holes
        }

        // Walk from one step along [direction]; take the nearest valid landing
        // (not the farthest — that overshoots and twitches the finger residual).
        var cursor = oldAnchor.step(direction)
        repeat(next.rows + next.cols) {
            if (!next.inBounds(cursor)) return null
            candidateFrom(cursor)?.let { return it }
            cursor = cursor.step(direction)
        }
        return null
    }

    private fun planMovesWithMakeWay(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        locks: LockGroupService,
        allTileIds: Iterable<Int>,
        direction: AxisDirection,
    ): List<TileMove>? {
        val planned = mutableListOf<TileMove>()
        val handledTiles = mutableSetOf<Int>()

        for (segment in contiguousSegments(holes, direction)) {
            val sourcePos = segment.source(direction)
            val trailPos = segment.trailing(direction)
            if (!grid.inBounds(sourcePos)) return null
            val tileId = grid.tileAt(sourcePos) ?: return null
            if (tileId in liftedTileIds) return null
            if (tileId in handledTiles) continue

            val group = locks.members(tileId, allTileIds)
                .filter { it !in liftedTileIds && grid.slotOfOrNull(it) != null }
                .toSet()
            if (group.isEmpty()) return null

            val dRow = trailPos.row - sourcePos.row
            val dCol = trailPos.col - sourcePos.col

            if (group.size <= 1) {
                planned += TileMove(tileId, sourcePos, trailPos)
                handledTiles += tileId
            } else {
                for (member in group) {
                    if (member in handledTiles) continue
                    val from = grid.slotOfOrNull(member) ?: return null
                    val to = from.offset(dRow, dCol)
                    if (!grid.inBounds(to)) return null
                    planned += TileMove(member, from, to)
                }
                handledTiles += group
            }
        }

        // Displace lone tiles (or rigid groups) that sit on planned destinations
        // into cells vacated by this push (opposite delta).
        var guard = 0
        while (guard++ < grid.rows * grid.cols) {
            val byDest = planned.associateBy { it.to }
            val movers = planned.map { it.tileId }.toSet()
            var added = false
            for (move in planned.toList()) {
                val occupant = grid.tileAt(move.to) ?: continue
                if (occupant in movers || occupant in liftedTileIds) continue
                if (planned.any { it.from == move.to && it.tileId == occupant }) continue

                val blockerGroup = locks.members(occupant, allTileIds)
                    .filter { it !in liftedTileIds && grid.slotOfOrNull(it) != null }
                    .toSet()
                // Blockers that are locked with nowhere to go → fail (caller may insert a row)
                val dRow = move.to.row - move.from.row
                val dCol = move.to.col - move.from.col
                val oppRow = -dRow
                val oppCol = -dCol

                for (member in blockerGroup) {
                    if (member in handledTiles) continue
                    val from = grid.slotOfOrNull(member) ?: return null
                    val to = from.offset(oppRow, oppCol)
                    if (!grid.inBounds(to)) return null
                    val destOcc = grid.tileAt(to)
                    val destOk = destOcc == null ||
                        destOcc in movers ||
                        planned.any { it.from == to }
                    if (!destOk && destOcc !in blockerGroup) return null
                    planned += TileMove(member, from, to)
                    handledTiles += member
                    added = true
                }
            }
            if (!added) break
        }

        return planned
    }

    private fun validateMoves(
        grid: SlotGrid,
        moves: List<TileMove>,
        liftedTileIds: Set<Int>,
    ): Boolean {
        if (moves.any { it.tileId in liftedTileIds }) return false
        if (moves.groupBy { it.to }.any { it.value.size > 1 }) return false

        for (move in moves) {
            val occupant = grid.tileAt(move.to) ?: continue
            if (occupant == move.tileId) continue
            val vacating = moves.any { it.from == move.to && it.tileId == occupant }
            if (!vacating) return false
        }
        return true
    }

    private fun applyMoves(grid: SlotGrid, moves: List<TileMove>): SlotGrid =
        grid.withCells { cells ->
            for (move in moves) {
                cells[move.from.index(grid.cols)] = EMPTY
            }
            for (move in moves) {
                cells[move.to.index(grid.cols)] = move.tileId
            }
        }

    private fun contiguousSegments(
        holes: Set<GridPos>,
        direction: AxisDirection,
    ): List<HoleSegment> {
        val byLine: Map<Int, List<Int>> = when (direction) {
            AxisDirection.Up, AxisDirection.Down ->
                holes.groupBy({ it.col }, { it.row })
            AxisDirection.Left, AxisDirection.Right ->
                holes.groupBy({ it.row }, { it.col })
        }

        return buildList {
            for ((line, coords) in byLine) {
                val sorted = coords.distinct().sorted()
                var start = sorted.first()
                var prev = start
                for (i in 1 until sorted.size) {
                    val c = sorted[i]
                    if (c == prev + 1) {
                        prev = c
                    } else {
                        add(HoleSegment(line = line, start = start, end = prev))
                        start = c
                        prev = c
                    }
                }
                add(HoleSegment(line = line, start = start, end = prev))
            }
        }
    }

    private data class TileMove(val tileId: Int, val from: GridPos, val to: GridPos)

    private data class HoleSegment(
        val line: Int,
        val start: Int,
        val end: Int,
    ) {
        fun source(direction: AxisDirection): GridPos = when (direction) {
            AxisDirection.Down -> GridPos(row = end + 1, col = line)
            AxisDirection.Up -> GridPos(row = start - 1, col = line)
            AxisDirection.Right -> GridPos(row = line, col = end + 1)
            AxisDirection.Left -> GridPos(row = line, col = start - 1)
        }

        fun trailing(direction: AxisDirection): GridPos = when (direction) {
            AxisDirection.Down -> GridPos(row = start, col = line)
            AxisDirection.Up -> GridPos(row = end, col = line)
            AxisDirection.Right -> GridPos(row = line, col = start)
            AxisDirection.Left -> GridPos(row = line, col = end)
        }
    }
}
