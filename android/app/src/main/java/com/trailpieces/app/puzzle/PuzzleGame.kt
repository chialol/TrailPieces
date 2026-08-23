package com.trailpieces.app.puzzle

import androidx.compose.ui.geometry.Offset
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.DragSession
import com.trailpieces.puzzle.service.MoveFingerResult
import com.trailpieces.puzzle.service.PuzzleBoard
import com.trailpieces.puzzle.service.ShuffleService

/**
 * Android/Compose facade over [DragEngine]. All puzzle rules live in `:puzzle-engine`
 * and are covered by JVM unit tests.
 */
class PuzzleGame(
    manifest: PuzzleManifest,
    initialBoard: PuzzleBoard = ShuffleService.shuffled(manifest),
) {
    private val engine = DragEngine(manifest, initialBoard)

    val board: PuzzleBoard get() = engine.board
    val drag: DragSession? get() = engine.drag
    val isSolved: Boolean get() = engine.isSolved

    fun reshuffle() = engine.reshuffle()

    fun startDrag(origin: GridPos): Boolean = engine.startDrag(origin)

    fun moveFinger(deltaPx: Offset, cellWidthPx: Float, cellHeightPx: Float): MoveFingerResult =
        engine.moveFinger(Vec2(deltaPx.x, deltaPx.y), cellWidthPx, cellHeightPx)

    fun endDrag(): PuzzleBoard = engine.endDrag()

    fun clearFingerDelta() = engine.clearFingerDelta()

    fun cancelDragSafely() = engine.cancelDragSafely()

    fun visualOffsetPx(): Offset {
        val v = engine.fingerDeltaPx
        return Offset(v.x, v.y)
    }

    fun lockedGroupSize(tileId: Int): Int = engine.lockedGroupSize(tileId)
}
