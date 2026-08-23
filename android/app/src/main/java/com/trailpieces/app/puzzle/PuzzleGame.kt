package com.trailpieces.app.puzzle

import android.util.Log
import androidx.compose.ui.geometry.Offset

private const val TAG = "PuzzleGame"

/** Fraction of a cell the finger must cross past the committed slot to push. */
private const val PUSH_THRESHOLD = 0.5f

/**
 * Facade for UI: resting board, active drag, finger tracking, push advancement.
 */
class PuzzleGame(
    private val manifest: PuzzleManifest,
    initialBoard: PuzzleBoard = createInitialBoard(manifest),
) {
    var board: PuzzleBoard = initialBoard
        private set

    var drag: ComponentDrag? = null
        private set

    var fingerDeltaPx: Offset = Offset.Zero
        private set

    val isSolved: Boolean get() = board.isSolved

    private var boardBeforeDrag: PuzzleBoard? = null

    init {
        Log.d(TAG, "init isSolved=$isSolved cols=${manifest.cols} rows=${manifest.rows} tiles=${manifest.tiles.size}")
    }

    fun reshuffle() {
        cancelDragSafely()
        board = runSafely("reshuffle") { PuzzleShuffle.shuffled(manifest) } ?: board
        Log.d(TAG, "reshuffle isSolved=$isSolved")
    }

    fun startDrag(origin: GridPos): Boolean {
        if (drag != null) {
            Log.d(TAG, "startDrag ignored: already dragging")
            return false
        }
        if (board.isSolved) {
            Log.d(TAG, "startDrag ignored: board solved")
            return false
        }
        if (!board.grid.inBounds(origin)) {
            Log.d(TAG, "startDrag ignored: out of bounds $origin")
            return false
        }
        val tile = board.tileAt(origin)
        if (tile == null) {
            Log.d(TAG, "startDrag ignored: empty slot at $origin")
            return false
        }

        return runSafely("startDrag") {
            boardBeforeDrag = board
            val session = board.beginDrag(origin, grouped = true)
            if (session == null) {
                boardBeforeDrag = null
                Log.d(TAG, "startDrag failed: beginDrag returned null at $origin")
                return@runSafely false
            }
            drag = session
            fingerDeltaPx = Offset.Zero
            Log.d(TAG, "startDrag ok at $origin tile=$tile lifted=${session.liftedTileIds.size}")
            true
        } ?: false
    }

    fun moveFinger(deltaPx: Offset, cellWidthPx: Float, cellHeightPx: Float) {
        if (drag == null) return
        if (!isValidCellSize(cellWidthPx) || !isValidCellSize(cellHeightPx)) return
        if (!isValidOffset(deltaPx)) return

        runSafely("moveFinger") {
            fingerDeltaPx = sanitizeOffset(fingerDeltaPx + deltaPx)
            advancePushes(cellWidthPx, cellHeightPx)
        }
    }

    fun endDrag(): PuzzleBoard {
        if (drag == null) return board
        return runSafely("endDrag") {
            val active = drag ?: return@runSafely board
            board = active.settle(manifest)
            drag = null
            fingerDeltaPx = Offset.Zero
            boardBeforeDrag = null
            Log.d(TAG, "endDrag settled isSolved=$isSolved")
            board
        } ?: run {
            cancelDragSafely()
            board
        }
    }

    fun clearFingerDelta() {
        fingerDeltaPx = Offset.Zero
    }

    /** Aborts an in-progress drag and restores the last stable board if needed. */
    fun cancelDragSafely() {
        runSafely("cancelDrag") {
            drag = null
            fingerDeltaPx = Offset.Zero
            boardBeforeDrag?.let { board = it }
            boardBeforeDrag = null
        }
    }

    fun visualOffsetPx(): Offset = fingerDeltaPx

    fun lockedGroupSize(tileId: Int): Int =
        runSafely("lockedGroupSize") { board.componentContaining(tileId).size } ?: 1

    private fun advancePushes(cellWidthPx: Float, cellHeightPx: Float) {
        var lockedDirection: AxisDirection? = null
        var pushCount = 0
        // Bound only runaway same-frame multi-pushes (fast swipe), not drag length.
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
            // Strict half-cell: after a push at just over 0.5, residual is just under
            // -0.5 so the opposite direction does not immediately un-push (jitter).
            if (signed <= cellSize * PUSH_THRESHOLD) break

            val pushed = active.tryPush(direction)
            if (pushed == null) {
                Log.d(TAG, "push blocked $direction residual=$residual committed=$committedPx")
                break
            }
            drag = pushed
            pushCount++
            Log.d(TAG, "push $direction anchor=${pushed.committedAnchor} finger=$fingerDeltaPx")
        }
    }

    private fun anchorCommittedPx(
        active: ComponentDrag,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Offset {
        val dCol = active.committedAnchor.col - active.startAnchor.col
        val dRow = active.committedAnchor.row - active.startAnchor.row
        return Offset(dCol * cellWidthPx, dRow * cellHeightPx)
    }

    private fun residualAlong(residual: Offset, direction: AxisDirection): Float =
        when (direction) {
            AxisDirection.Down -> residual.y
            AxisDirection.Up -> -residual.y
            AxisDirection.Right -> residual.x
            AxisDirection.Left -> -residual.x
        }

    private inline fun <T> runSafely(label: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "$label failed — recovering drag state", e)
            cancelDragSafely()
            null
        }

    companion object {
        private fun createInitialBoard(manifest: PuzzleManifest): PuzzleBoard =
            try {
                PuzzleShuffle.shuffled(manifest)
            } catch (e: Exception) {
                Log.e(TAG, "shuffle failed — using solved board", e)
                PuzzleBoard.solved(manifest)
            }

        private fun isValidCellSize(size: Float): Boolean =
            size.isFinite() && size > 0f

        private fun isValidOffset(offset: Offset): Boolean =
            offset.x.isFinite() && offset.y.isFinite()

        private fun sanitizeOffset(offset: Offset): Offset =
            if (isValidOffset(offset)) offset else Offset.Zero
    }
}

private operator fun Offset.minus(other: Offset): Offset =
    Offset(x - other.x, y - other.y)
