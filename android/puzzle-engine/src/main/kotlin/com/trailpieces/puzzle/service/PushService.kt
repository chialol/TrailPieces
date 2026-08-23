package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.model.step
import kotlin.math.abs

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

        val cleared = clearFootprintLanding(
            grid = grid,
            holes = holes,
            direction = direction,
            primaryMoves = moves,
            liftedTileIds = liftedTileIds,
            locks = locks,
            allTileIds = allTileIds,
        ) ?: return null

        val next = applyMoves(grid, cleared)
        val newHoles = resolveNewHoles(holes, direction, next) ?: return null
        return PushResult(next, newHoles)
    }

    /**
     * After primary resting-tile moves, the lifted footprint may need cells that
     * are still occupied (e.g. H sitting on ACD's tunnel landing). Displace those
     * occupants — whole lock groups — into cells vacated by the primary moves.
     */
    private fun clearFootprintLanding(
        grid: SlotGrid,
        holes: Set<GridPos>,
        direction: AxisDirection,
        primaryMoves: List<TileMove>,
        liftedTileIds: Set<Int>,
        locks: LockGroupService,
        allTileIds: Iterable<Int>,
    ): List<TileMove>? {
        val afterPrimary = applyMoves(grid, primaryMoves)
        if (resolveNewHoles(holes, direction, afterPrimary) != null) {
            return primaryMoves
        }

        val landing = findNearestLandingAllowingOccupants(
            holes,
            direction,
            afterPrimary,
            moverIds = primaryMoves.map { it.tileId }.toSet(),
        ) ?: return null

        val moverIds = primaryMoves.map { it.tileId }.toSet()
        val primaryDests = primaryMoves.map { it.to }.toSet()
        val vacated = primaryMoves
            .map { it.from }
            .filter { it !in primaryDests && it !in landing }
            .toMutableSet()

        val planned = primaryMoves.toMutableList()
        val handled = moverIds.toMutableSet()

        val blockerIds = landing.mapNotNull { pos ->
            afterPrimary.tileAt(pos)?.takeIf { it !in liftedTileIds && it !in moverIds }
        }.toSet()
        if (blockerIds.isEmpty()) return null

        for (blockerId in blockerIds) {
            if (blockerId in handled) continue
            val group = locks.members(blockerId, allTileIds)
                .filter { it !in liftedTileIds && grid.slotOfOrNull(it) != null }
                .toSet()
            if (group.isEmpty()) return null

            val shift = findEscapeTranslation(
                group = group,
                grid = grid,
                afterPrimary = afterPrimary,
                landing = landing,
                vacated = vacated,
                plannedDests = planned.map { it.to }.toSet(),
            ) ?: return null

            for (member in group) {
                if (member in handled) continue
                val from = grid.slotOfOrNull(member) ?: return null
                val to = from.offset(shift.first, shift.second)
                if (!grid.inBounds(to)) return null
                planned += TileMove(member, from, to)
                vacated.remove(to)
                handled += member
            }
        }

        if (!validateMoves(grid, planned, liftedTileIds)) return null
        val cleared = applyMoves(grid, planned)
        if (resolveNewHoles(holes, direction, cleared) == null) return null
        return planned
    }

    /**
     * Nearest footprint landing along [direction]. Cells may hold displaceable
     * resting tiles, but not tiles already placed by the primary push.
     */
    private fun findNearestLandingAllowingOccupants(
        oldHoles: Set<GridPos>,
        direction: AxisDirection,
        next: SlotGrid,
        moverIds: Set<Int>,
    ): Set<GridPos>? {
        val oldAnchor = oldHoles.minWith(compareBy({ it.row }, { it.col }))
        val offsets = oldHoles.map { GridPos(it.row - oldAnchor.row, it.col - oldAnchor.col) }

        fun candidateFrom(far: GridPos): Set<GridPos>? {
            val cand = offsets.map { far.offset(it.row, it.col) }.toSet()
            if (cand.any { !next.inBounds(it) }) return null
            if (cand.any { pos -> next.tileAt(pos)?.let { it in moverIds } == true }) return null
            return cand
        }

        var cursor = oldAnchor.step(direction)
        repeat(next.rows + next.cols) {
            if (!next.inBounds(cursor)) return null
            candidateFrom(cursor)?.let { return it }
            cursor = cursor.step(direction)
        }
        return null
    }

    /**
     * Rigid translation for [group] onto cells that are empty after primary moves
     * (vacated or already empty), clear of [landing].
     */
    private fun findEscapeTranslation(
        group: Set<Int>,
        grid: SlotGrid,
        afterPrimary: SlotGrid,
        landing: Set<GridPos>,
        vacated: Set<GridPos>,
        plannedDests: Set<GridPos>,
    ): Pair<Int, Int>? {
        val slots = group.mapNotNull { grid.slotOfOrNull(it) }
        if (slots.size != group.size) return null
        val anchor = slots.minWith(compareBy({ it.row }, { it.col }))
        val offsets = slots.map { GridPos(it.row - anchor.row, it.col - anchor.col) }

        fun fits(dRow: Int, dCol: Int): Boolean {
            if (dRow == 0 && dCol == 0) return false
            val dests = offsets.map { anchor.offset(it.row + dRow, it.col + dCol) }
            if (dests.any { !grid.inBounds(it) || it in landing }) return false
            for (to in dests) {
                if (to in plannedDests) return false
                val occ = afterPrimary.tileAt(to)
                val destOk = when {
                    to in vacated -> true
                    occ == null -> true
                    occ in group -> true
                    else -> false
                }
                if (!destOk) return false
            }
            return true
        }

        val maxDist = grid.rows + grid.cols
        // Prefer short moves; scan ring by Manhattan distance.
        for (dist in 1..maxDist) {
            for (dRow in -dist..dist) {
                val rest = dist - abs(dRow)
                for (dCol in listOf(-rest, rest).distinct()) {
                    if (fits(dRow, dCol)) return dRow to dCol
                }
            }
        }
        return null
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
        val stepped = holes.map { it.step(direction) }.toSet()

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
            val groupSpillsSegment = group.any { id ->
                val slot = grid.slotOfOrNull(id) ?: return@any false
                when (direction) {
                    AxisDirection.Up, AxisDirection.Down -> slot.col != segment.line
                    AxisDirection.Left, AxisDirection.Right -> slot.row != segment.line
                }
            }

            if (group.size <= 1) {
                planned += TileMove(tileId, sourcePos, trailPos)
                handledTiles += tileId
            } else {
                val multiCellTrailUp = direction == AxisDirection.Down && dRow < 0 &&
                    (abs(dRow) + abs(dCol) > 1)
                val allTrailDestsAreHoles = group.all { id ->
                    grid.slotOfOrNull(id)?.offset(dRow, dCol) in holes
                }
                val preferStepDown = direction == AxisDirection.Down && dRow < 0 &&
                    !allTrailDestsAreHoles && (multiCellTrailUp || groupSpillsSegment)
                val shift = when {
                    allTrailDestsAreHoles -> dRow to dCol
                    preferStepDown && groupFitsShift(group, grid, 1, 0) -> 1 to 0
                    direction == AxisDirection.Down && dRow < 0 && !allTrailDestsAreHoles ->
                        findPackTranslation(group, grid, direction, stepped) ?: (dRow to dCol)
                    else -> dRow to dCol
                }
                for (member in group) {
                    if (member in handledTiles) continue
                    val from = grid.slotOfOrNull(member) ?: return null
                    val to = from.offset(shift.first, shift.second)
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

    /**
     * Park [group] on empty cells clear of [steppedFootprint]. Prefer shifting
     * opposite [direction] (up when pushing down).
     */
    private fun findPackTranslation(
        group: Set<Int>,
        grid: SlotGrid,
        direction: AxisDirection,
        steppedFootprint: Set<GridPos>,
    ): Pair<Int, Int>? {
        val slots = group.mapNotNull { grid.slotOfOrNull(it) }
        if (slots.size != group.size) return null
        val anchor = slots.minWith(compareBy({ it.row }, { it.col }))
        val offsets = slots.map { GridPos(it.row - anchor.row, it.col - anchor.col) }

        fun fits(dRow: Int, dCol: Int): Boolean {
            if (dRow == 0 && dCol == 0) return false
            for (offset in offsets) {
                val to = anchor.offset(offset.row + dRow, offset.col + dCol)
                if (!grid.inBounds(to)) return false
                if (grid.tileAt(to) != null) return false
                if (to in steppedFootprint) return false
            }
            return true
        }

        val (oppRow, oppCol) = when (direction) {
            AxisDirection.Down -> -1 to 0
            AxisDirection.Up -> 1 to 0
            AxisDirection.Right -> 0 to -1
            AxisDirection.Left -> 0 to 1
        }

        val maxDist = grid.rows + grid.cols
        for (dist in 1..maxDist) {
            if (fits(oppRow * dist, oppCol * dist)) return oppRow * dist to oppCol * dist
        }
        return null
    }

    private fun groupFitsShift(
        group: Set<Int>,
        grid: SlotGrid,
        dRow: Int,
        dCol: Int,
    ): Boolean {
        for (id in group) {
            val from = grid.slotOfOrNull(id) ?: return false
            val to = from.offset(dRow, dCol)
            if (!grid.inBounds(to)) return false
            val occupant = grid.tileAt(to)
            if (occupant != null && occupant !in group) return false
        }
        return true
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
