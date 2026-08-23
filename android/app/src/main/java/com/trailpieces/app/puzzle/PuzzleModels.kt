package com.trailpieces.app.puzzle

data class PuzzleTile(
    val index: Int,
    val row: Int,
    val col: Int,
    val assetPath: String,
)

data class PuzzleManifest(
    val id: String,
    val title: String,
    val cols: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val puzzleWidth: Int,
    val puzzleHeight: Int,
    val emptyIndex: Int,
    val tiles: List<PuzzleTile>,
)

data class SlidingPuzzleState(
    val manifest: PuzzleManifest,
    val tileOrder: List<Int>,
    val emptyIndex: Int,
) {
    val cols: Int get() = manifest.cols
    val rows: Int get() = manifest.rows
    val isSolved: Boolean get() = tileOrder == manifest.tiles.map { it.index }

    fun tileAt(cellIndex: Int): Int? {
        if (cellIndex == emptyIndex) return null
        return tileOrder[cellIndex]
    }

    fun canMove(cellIndex: Int): Boolean {
        if (cellIndex == emptyIndex) return false
        val emptyRow = emptyIndex / cols
        val emptyCol = emptyIndex % cols
        val row = cellIndex / cols
        val col = cellIndex % cols
        return (row == emptyRow && kotlin.math.abs(col - emptyCol) == 1) ||
            (col == emptyCol && kotlin.math.abs(row - emptyRow) == 1)
    }

    fun move(cellIndex: Int): SlidingPuzzleState? {
        if (!canMove(cellIndex)) return null
        val nextOrder = tileOrder.toMutableList()
        nextOrder[emptyIndex] = nextOrder[cellIndex]
        nextOrder[cellIndex] = manifest.emptyIndex
        return copy(tileOrder = nextOrder, emptyIndex = cellIndex)
    }

    companion object {
        fun shuffled(manifest: PuzzleManifest, moves: Int = 200): SlidingPuzzleState {
            var state = solved(manifest)
            repeat(moves) {
                val movable = state.tileOrder.indices.filter { state.canMove(it) }
                if (movable.isEmpty()) return@repeat
                state = state.move(movable.random()) ?: state
            }
            return state
        }

        fun solved(manifest: PuzzleManifest): SlidingPuzzleState {
            val order = List(manifest.cols * manifest.rows) { it }
            return SlidingPuzzleState(manifest, order, manifest.emptyIndex)
        }
    }
}
