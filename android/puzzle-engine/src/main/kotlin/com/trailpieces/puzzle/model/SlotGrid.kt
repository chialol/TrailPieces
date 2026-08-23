package com.trailpieces.puzzle.model

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

    /** Insert an empty row at [rowIndex]; existing rows at and below shift down. */
    fun insertRow(rowIndex: Int): SlotGrid {
        require(rowIndex in 0..rows) { "rowIndex $rowIndex out of 0..$rows" }
        val next = IntArray(cols * (rows + 1)) { EMPTY }
        for (r in 0 until rows) {
            val dest = if (r >= rowIndex) r + 1 else r
            for (c in 0 until cols) {
                next[dest * cols + c] = cells[r * cols + c]
            }
        }
        return SlotGrid(cols, rows + 1, next)
    }

    /**
     * Remove every row that is entirely empty. Row order of remaining content
     * is preserved. No-op if nothing to remove.
     */
    fun collapseEmptyRows(): SlotGrid {
        val kept = (0 until rows).filter { row ->
            (0 until cols).any { col -> cells[row * cols + col] != EMPTY }
        }
        if (kept.size == rows) return this
        if (kept.isEmpty()) return empty(cols, 1)
        val next = IntArray(cols * kept.size) { EMPTY }
        kept.forEachIndexed { destRow, srcRow ->
            for (c in 0 until cols) {
                next[destRow * cols + c] = cells[srcRow * cols + c]
            }
        }
        return SlotGrid(cols, kept.size, next)
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
