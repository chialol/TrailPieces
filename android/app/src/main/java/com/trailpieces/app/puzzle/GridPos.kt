package com.trailpieces.app.puzzle

/**
 * Grid position: [row][col], origin top-left.
 * - row (r): increases downward
 * - col (c): increases rightward
 */
data class GridPos(val row: Int, val col: Int) {
    fun index(cols: Int): Int = row * cols + col

    fun offset(dRow: Int, dCol: Int): GridPos = GridPos(row + dRow, col + dCol)

    companion object {
        fun fromIndex(index: Int, cols: Int): GridPos = GridPos(index / cols, index % cols)
    }
}

enum class AxisDirection(val dRow: Int, val dCol: Int) {
    Up(-1, 0),
    Down(1, 0),
    Left(0, -1),
    Right(0, 1),
    ;

    companion object {
        /** @param deltaRowPx vertical finger movement (screen y, down positive) */
        fun dominant(deltaRowPx: Float, deltaColPx: Float): AxisDirection? {
            if (kotlin.math.abs(deltaRowPx) < 0.01f && kotlin.math.abs(deltaColPx) < 0.01f) return null
            return if (kotlin.math.abs(deltaRowPx) >= kotlin.math.abs(deltaColPx)) {
                if (deltaRowPx < 0) Up else Down
            } else {
                if (deltaColPx < 0) Left else Right
            }
        }

        fun opposite(dir: AxisDirection): AxisDirection = when (dir) {
            Up -> Down
            Down -> Up
            Left -> Right
            Right -> Left
        }
    }
}
