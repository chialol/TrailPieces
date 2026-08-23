package com.trailpieces.app.puzzle

import android.util.Log

private const val TAG = "LockGroups"

/**
 * Union-find over tile ids. Two adjacent tiles lock when their slot offset
 * equals their home offset — they form a rigid component for dragging.
 */
class LockGroups(private val parent: IntArray) {

    fun find(x: Int): Int {
        if (x !in parent.indices) return x
        var root = x
        var hops = 0
        while (parent[root] != root) {
            if (++hops > parent.size) {
                Log.w(TAG, "find($x) exceeded hop limit — union-find may be corrupt")
                return root
            }
            val next = parent[root]
            if (next !in parent.indices) return root
            parent[root] = parent[next]
            root = parent[root]
        }
        return root
    }

    fun union(a: Int, b: Int) {
        if (a !in parent.indices || b !in parent.indices) return
        val rootA = find(a)
        val rootB = find(b)
        if (rootA == rootB) return
        parent[rootB] = rootA
    }

    fun members(tileId: Int, allTileIds: Iterable<Int>): Set<Int> {
        if (tileId !in parent.indices) return setOf(tileId)
        val root = find(tileId)
        return allTileIds.filter { it in parent.indices && find(it) == root }.toSet()
    }

    companion object {
        fun compute(grid: SlotGrid, manifest: PuzzleManifest): LockGroups {
            val size = manifest.tiles.maxOf { it.id } + 1
            val groups = LockGroups(IntArray(size) { it })

            for (row in 0 until grid.rows) {
                for (col in 0 until grid.cols) {
                    val pos = GridPos(row, col)
                    val tileA = grid.tileAt(pos) ?: continue
                    for (direction in AxisDirection.entries) {
                        val neighbor = pos.step(direction)
                        val tileB = grid.tileAt(neighbor) ?: continue
                        if (areCorrectlyAdjacent(manifest, grid, tileA, tileB)) {
                            groups.union(tileA, tileB)
                        }
                    }
                }
            }
            return groups
        }

        private fun areCorrectlyAdjacent(
            manifest: PuzzleManifest,
            grid: SlotGrid,
            tileA: Int,
            tileB: Int,
        ): Boolean {
            val slotA = grid.slotOfOrNull(tileA) ?: return false
            val slotB = grid.slotOfOrNull(tileB) ?: return false
            val homeA = manifest.tileOrNull(tileA)?.home ?: return false
            val homeB = manifest.tileOrNull(tileB)?.home ?: return false
            return slotA.row - slotB.row == homeA.row - homeB.row &&
                slotA.col - slotB.col == homeA.col - homeB.col
        }

        fun isolated(manifest: PuzzleManifest): LockGroups =
            LockGroups(IntArray(manifest.tiles.maxOf { it.id } + 1) { it })
    }
}
