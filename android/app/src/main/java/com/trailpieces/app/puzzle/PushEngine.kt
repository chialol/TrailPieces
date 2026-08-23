package com.trailpieces.app.puzzle

/**
 * Axis-aligned push for arbitrary lifted footprints (not just rectangles).
 *
 * Holes are grouped into contiguous runs along each line parallel to [direction]
 * (columns when moving vertically, rows when moving horizontally). Each run of
 * length L needs exactly one tile just past its leading edge; that tile slides
 * into the trailing hole and the hole-block shifts by one.
 *
 * Why the old per-hole check failed: for a vertical pair moving down, holes at
 * (0,0) and (1,0) — the "source" of (0,0) is (1,0), which is also empty — so
 * push was rejected. Perpendicular moves worked because each line had a single hole.
 */
object PushEngine {

    fun tryPush(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        direction: AxisDirection,
    ): SlotGrid? {
        if (holes.isEmpty()) return null

        val moves = mutableListOf<Pair<GridPos, GridPos>>() // source → trailing hole

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

    /**
     * Contiguous hole runs along lines parallel to [direction].
     * Vertical move → group by column, runs of rows.
     * Horizontal move → group by row, runs of cols.
     */
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

    /** One contiguous hole run on a single row or column. */
    private data class HoleSegment(
        /** Column index (vertical move) or row index (horizontal move). */
        val line: Int,
        /** Inclusive min coord along the push axis. */
        val start: Int,
        /** Inclusive max coord along the push axis. */
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
