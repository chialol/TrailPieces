package com.trailpieces.app.puzzle

const val EMPTY = -1

/**
 * Fixed grid of slots. Each cell holds a tile id or [EMPTY].
 * Slots never move — tiles move between them.
 */
class SlotGrid(
    val cols: Int,
    val rows: Int,
    private val cells: IntArray,
) {
    init {
        require(cells.size == cols * rows)
    }

    operator fun get(pos: GridPos): Int = cells[pos.index(cols)]

    fun tileAt(pos: GridPos): Int? {
        if (!inBounds(pos)) return null
        val value = cells[pos.index(cols)]
        return if (value == EMPTY) null else value
    }

    fun inBounds(pos: GridPos): Boolean =
        pos.row in 0 until rows && pos.col in 0 until cols

    fun slotOf(tileId: Int): GridPos {
        val index = cells.indexOf(tileId)
        require(index >= 0) { "Tile $tileId is not on the board" }
        return GridPos.fromIndex(index, cols)
    }

    fun slotOfOrNull(tileId: Int): GridPos? {
        val index = cells.indexOf(tileId)
        if (index < 0) return null
        return GridPos.fromIndex(index, cols)
    }

    fun copyCells(): IntArray = cells.copyOf()

    fun withCell(pos: GridPos, value: Int): SlotGrid {
        val next = cells.copyOf()
        next[pos.index(cols)] = value
        return SlotGrid(cols, rows, next)
    }

    fun withCells(mutator: (IntArray) -> Unit): SlotGrid {
        val next = cells.copyOf()
        mutator(next)
        return SlotGrid(cols, rows, next)
    }

    /** Slots with no tile — the holes left while a component is lifted. */
    fun emptySlots(): Set<GridPos> = buildSet {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val pos = GridPos(row, col)
                if (cells[pos.index(cols)] == EMPTY) add(pos)
            }
        }
    }

    companion object {
        fun empty(cols: Int, rows: Int): SlotGrid =
            SlotGrid(cols, rows, IntArray(cols * rows) { EMPTY })

        fun solved(manifest: PuzzleManifest): SlotGrid {
            val grid = empty(manifest.cols, manifest.rows)
            return grid.withCells { cells ->
                manifest.tiles.forEach { tile ->
                    cells[tile.home.index(manifest.cols)] = tile.id
                }
            }
        }
    }
}

fun GridPos.step(direction: AxisDirection): GridPos =
    offset(direction.dRow, direction.dCol)
