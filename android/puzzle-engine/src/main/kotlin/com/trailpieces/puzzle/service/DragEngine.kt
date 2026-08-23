package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.Vec2

private const val PUSH_THRESHOLD = 0.5f

/**
 * Pure puzzle game state: board, drag session, finger tracking, push advancement.
 * No Android or Compose dependencies — safe for JVM unit tests.
 */
class DragEngine(
    private val manifest: PuzzleManifest,
    initialBoard: PuzzleBoard = createInitialBoard(manifest),
) {
    var board: PuzzleBoard = initialBoard
        private set

    var drag: DragSession? = null
        private set

    var fingerDeltaPx: Vec2 = Vec2.Zero
        private set

    val isSolved: Boolean get() = board.isSolved

    private var boardBeforeDrag: PuzzleBoard? = null

    fun reshuffle() {
        cancelDragSafely()
        board = runSafely { ShuffleService.shuffled(manifest) } ?: board
    }

    fun startDrag(origin: GridPos): Boolean {
        if (drag != null) return false
        if (board.isSolved) return false
        if (!board.grid.inBounds(origin)) return false
        if (board.tileAt(origin) == null) return false

        return runSafely {
            boardBeforeDrag = board
            val session = board.beginDrag(origin, grouped = true) ?: run {
                boardBeforeDrag = null
                return@runSafely false
            }
            drag = session
            fingerDeltaPx = Vec2.Zero
            true
        } ?: false
    }

    fun moveFinger(deltaPx: Vec2, cellWidthPx: Float, cellHeightPx: Float) {
        if (drag == null) return
        if (!isValidCellSize(cellWidthPx) || !isValidCellSize(cellHeightPx)) return
        if (!isValidOffset(deltaPx)) return

        runSafely {
            fingerDeltaPx = sanitizeOffset(fingerDeltaPx + deltaPx)
            advancePushes(cellWidthPx, cellHeightPx)
        }
    }

    fun endDrag(): PuzzleBoard {
        if (drag == null) return board
        return runSafely {
            val active = drag ?: return@runSafely board
            board = active.settle(manifest)
            drag = null
            fingerDeltaPx = Vec2.Zero
            boardBeforeDrag = null
            board
        } ?: run {
            cancelDragSafely()
            board
        }
    }

    fun clearFingerDelta() {
        fingerDeltaPx = Vec2.Zero
    }

    fun cancelDragSafely() {
        runSafely {
            drag = null
            fingerDeltaPx = Vec2.Zero
            boardBeforeDrag?.let { board = it }
            boardBeforeDrag = null
        }
    }

    fun lockedGroupSize(tileId: Int): Int =
        runSafely { board.componentContaining(tileId).size } ?: 1

    private fun advancePushes(cellWidthPx: Float, cellHeightPx: Float) {
        var lockedDirection: AxisDirection? = null
        var pushCount = 0
        val maxPushesPerFrame = (manifest.rows + manifest.cols).coerceAtLeast(2)

        while (pushCount < maxPushesPerFrame) {
            val active = drag ?: return
            val committedPx = anchorCommittedPx(active, cellWidthPx, cellHeightPx)
            val residual = sanitizeOffset(fingerDeltaPx - committedPx)

            val direction = lockedDirection
                ?: AxisDirection.dominant(deltaRowPx = residual.y, deltaColPx = residual.x)
                ?: break
            lockedDirection = direction

            val cellSize = if (direction.dRow != 0) cellHeightPx else cellWidthPx
            if (!isValidCellSize(cellSize)) break

            val signed = residualAlong(residual, direction)
            if (signed <= cellSize * PUSH_THRESHOLD) break

            val pushed = active.tryPush(direction) ?: break
            drag = pushed
            pushCount++
        }
    }

    private fun anchorCommittedPx(
        active: DragSession,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Vec2 {
        val dCol = active.committedAnchor.col - active.startAnchor.col
        val dRow = active.committedAnchor.row - active.startAnchor.row
        return Vec2(dCol * cellWidthPx, dRow * cellHeightPx)
    }

    private fun residualAlong(residual: Vec2, direction: AxisDirection): Float =
        when (direction) {
            AxisDirection.Down -> residual.y
            AxisDirection.Up -> -residual.y
            AxisDirection.Right -> residual.x
            AxisDirection.Left -> -residual.x
        }

    private inline fun <T> runSafely(block: () -> T): T? =
        try {
            block()
        } catch (_: Exception) {
            cancelDragSafely()
            null
        }

    companion object {
        private fun createInitialBoard(manifest: PuzzleManifest): PuzzleBoard =
            try {
                ShuffleService.shuffled(manifest)
            } catch (_: Exception) {
                PuzzleBoard.solved(manifest)
            }

        private fun isValidCellSize(size: Float): Boolean =
            size.isFinite() && size > 0f

        private fun isValidOffset(offset: Vec2): Boolean =
            offset.x.isFinite() && offset.y.isFinite()

        private fun sanitizeOffset(offset: Vec2): Vec2 =
            if (isValidOffset(offset)) offset else Vec2.Zero
    }
}
