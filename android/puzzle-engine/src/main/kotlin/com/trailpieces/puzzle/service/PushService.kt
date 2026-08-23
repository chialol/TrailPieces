package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid

/**
 * Axis-aligned push for arbitrary lifted footprints (not just rectangles).
 */
object PushService {

    fun tryPush(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        direction: AxisDirection,
    ): SlotGrid? {
        if (holes.isEmpty()) return null

        val moves = mutableListOf<Pair<GridPos, GridPos>>()

        for (segment in contiguousSegments(holes, direction)) {
            val source = segment.source(direction)
            val dest = segment.trailing(direction)
            if (!grid.inBounds(source)) return null
            val tile = grid.tileAt(source) ?: return null
            if (tile in liftedTileIds) return null
            moves += source to dest
        }

        return grid.withCells { cells ->
            for ((source, dest) in moves) {
                cells[dest.index(grid.cols)] = cells[source.index(grid.cols)]
                cells[source.index(grid.cols)] = EMPTY
            }
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
