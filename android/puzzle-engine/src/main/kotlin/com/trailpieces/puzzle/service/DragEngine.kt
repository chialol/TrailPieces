package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.Vec2
import kotlin.math.abs

private const val PUSH_THRESHOLD = 0.5f

/**
 * Outcome of one [DragEngine.moveFinger] call — for UI feedback (haptics).
 *
 * [restingImpacts] counts committed pushes that changed resting-tile occupancy
 * (make-way, tunnel, insert-row). Empty-hole slides do not count: the lift
 * moved, but nothing on the board was bumped.
 */
data class MoveFingerResult(val restingImpacts: Int) {
    val hadRestingImpact: Boolean get() = restingImpacts > 0

    companion object {
        val None = MoveFingerResult(0)
    }
}

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
    private var lastCellWidthPx: Float = 0f
    private var lastCellHeightPx: Float = 0f

    /** Recent start / commit / release steps for debug dumps. */
    val dragTrace = DragTrace()

    fun clearDragTrace() = dragTrace.clear()

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
            val letters = BoardDebug.homeOrderLetters(manifest)
            val lifted = session.liftedTileIds.sorted().joinToString("") { letters[it] ?: "?$it" }
            dragTrace.record(
                kind = "start",
                note = "lift {$lifted} at $origin → holes=${session.targetSlots}",
                gridBrief = BoardDebug.gridBrief(session.grid, manifest, session.targetSlots),
            )
            true
        } ?: false
    }

    fun moveFinger(deltaPx: Vec2, cellWidthPx: Float, cellHeightPx: Float): MoveFingerResult {
        if (drag == null) return MoveFingerResult.None
        if (!isValidCellSize(cellWidthPx) || !isValidCellSize(cellHeightPx)) {
            return MoveFingerResult.None
        }
        if (!isValidOffset(deltaPx)) return MoveFingerResult.None

        return runSafely {
            lastCellWidthPx = cellWidthPx
            lastCellHeightPx = cellHeightPx
            fingerDeltaPx = sanitizeOffset(fingerDeltaPx + deltaPx)
            MoveFingerResult(advancePushes(cellWidthPx, cellHeightPx))
        } ?: MoveFingerResult.None
    }

    fun endDrag(): PuzzleBoard {
        if (drag == null) return board
        return runSafely {
            val active = drag ?: return@runSafely board
            dragTrace.record(
                kind = "release-before-settle",
                note = "committed=${active.committedAnchor} finger=$fingerDeltaPx",
                gridBrief = BoardDebug.gridBrief(active.grid, manifest, active.targetSlots),
            )
            board = SettleService.settle(
                session = active,
                fingerDeltaPx = fingerDeltaPx,
                cellWidthPx = lastCellWidthPx,
                cellHeightPx = lastCellHeightPx,
                originalBoard = boardBeforeDrag,
            )
            drag = null
            fingerDeltaPx = Vec2.Zero
            boardBeforeDrag = null
            dragTrace.record(
                kind = "release-after-settle",
                note = "board ${board.cols}x${board.rows}",
                gridBrief = BoardDebug.gridBrief(board.grid, manifest),
            )
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

    /** @return number of commits that changed resting-tile occupancy this call. */
    private fun advancePushes(cellWidthPx: Float, cellHeightPx: Float): Int {
        var lockedDirection: AxisDirection? = null
        var pushCount = 0
        var restingImpacts = 0
        val maxPushesPerFrame = (manifest.rows + manifest.cols).coerceAtLeast(2)

        while (pushCount < maxPushesPerFrame) {
            val active = drag ?: return restingImpacts

            val homeSwap = active.tryCompletingHomeSameSizeSwap(
                fingerDeltaPx,
                cellWidthPx,
                cellHeightPx,
            )
            if (homeSwap != null) {
                if (restingOccupancyChanged(active, homeSwap)) restingImpacts++
                recordCommit("home-swap", active, homeSwap)
                drag = homeSwap
                pushCount++
                continue
            }

            // Same-size swap before empty-land — swap must win when the finger
            // aims at an occupied congruent tile, not park on a vacated cell beside it.
            val aimSwap = active.tryFingerAimSameSizeSwap(fingerDeltaPx, cellWidthPx, cellHeightPx)
            if (aimSwap != null &&
                hasOffAxisFingerAim(active, fingerDeltaPx, cellWidthPx, cellHeightPx) &&
                PlacementService.fingerCoversAnchor(
                    active,
                    aimSwap.committedAnchor,
                    fingerDeltaPx,
                    cellWidthPx,
                    cellHeightPx,
                )
            ) {
                if (restingOccupancyChanged(active, aimSwap)) restingImpacts++
                recordCommit("aim-swap", active, aimSwap)
                drag = aimSwap
                pushCount++
                continue
            }

            val aimEmpty = active.tryFingerAimEmptyLand(fingerDeltaPx, cellWidthPx, cellHeightPx)
            if (aimEmpty != null &&
                PlacementService.fingerCoversAnchor(
                    active,
                    aimEmpty.committedAnchor,
                    fingerDeltaPx,
                    cellWidthPx,
                    cellHeightPx,
                )
            ) {
                if (restingOccupancyChanged(active, aimEmpty)) restingImpacts++
                recordCommit("aim-empty", active, aimEmpty)
                drag = aimEmpty
                pushCount++
                continue
            }

            val committedPx = anchorCommittedPx(active, cellWidthPx, cellHeightPx)
            val residual = sanitizeOffset(fingerDeltaPx - committedPx)

            val direction = lockedDirection
                ?: AxisDirection.dominant(deltaRowPx = residual.y, deltaColPx = residual.x)
                ?: break
            lockedDirection = direction

            val cellSize = if (direction.dRow != 0) cellHeightPx else cellWidthPx
            if (!isValidCellSize(cellSize)) break

            val signed = residualAlong(residual, direction)
            // Singleton empty-jump (look-ahead) before make-way so a far hole
            // can win once the finger covers it.
            val emptyJump = active.tryNearestEmptyAlongAxis(direction)
            if (emptyJump != null) {
                val jump = cellsJumped(active.committedAnchor, emptyJump.committedAnchor, direction)
                    .coerceAtLeast(1)
                val needed = (jump - PUSH_THRESHOLD) * cellSize
                if (signed > needed) {
                    if (restingOccupancyChanged(active, emptyJump)) restingImpacts++
                    recordCommit("empty-jump-$direction", active, emptyJump)
                    drag = emptyJump
                    pushCount++
                    continue
                }
            }

            // Peek first: multi-cell tunnels / insert-row need the finger near the
            // far landing (jump - 0.5 cells), not merely 0.5 into the next cell.
            val candidate = active.tryPush(direction) ?: break
            val jump = cellsJumped(active.committedAnchor, candidate.committedAnchor, direction)
                .coerceAtLeast(1)
            val needed = (jump - PUSH_THRESHOLD) * cellSize
            if (signed <= needed) break

            if (restingOccupancyChanged(active, candidate)) restingImpacts++
            recordCommit("push-$direction", active, candidate)
            drag = candidate
            pushCount++
        }
        return restingImpacts
    }

    private fun recordCommit(kind: String, before: DragSession, after: DragSession) {
        dragTrace.record(
            kind = "commit:$kind",
            note = "${before.committedAnchor} → ${after.committedAnchor}",
            gridBrief = BoardDebug.gridBrief(after.grid, manifest, after.targetSlots),
        )
    }

    /**
     * True when resting tiles moved or the playfield grew (insert-row).
     * Empty-hole slides keep the same occupancy and do not count.
     */
    private fun restingOccupancyChanged(before: DragSession, after: DragSession): Boolean {
        val a = before.grid
        val b = after.grid
        if (a.rows != b.rows || a.cols != b.cols) return true
        return !a.copyCells().contentEquals(b.copyCells())
    }

    /**
     * How many cells the anchor advances along [direction]. Insert-row can keep
     * the same (row,col) on a taller grid — treat that as at least one cell.
     */
    private fun cellsJumped(from: GridPos, to: GridPos, direction: AxisDirection): Int =
        when (direction) {
            AxisDirection.Up -> from.row - to.row
            AxisDirection.Down -> to.row - from.row
            AxisDirection.Left -> from.col - to.col
            AxisDirection.Right -> to.col - from.col
        }.let { delta -> if (delta == 0) 1 else abs(delta) }

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

    /** Mid-drag finger-aim swap when the projected landing is diagonal from the drag start. */
    private fun hasOffAxisFingerAim(
        active: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Boolean {
        if (!isValidCellSize(cellWidthPx) || !isValidCellSize(cellHeightPx)) return false
        val target = fingerTargetAnchor(active, fingerDeltaPx, cellWidthPx, cellHeightPx) ?: return false
        return target.col != active.startAnchor.col && target.row != active.startAnchor.row
    }

    private fun fingerTargetAnchor(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): GridPos? = PlacementService.fingerTargetAnchorForSwap(
        session,
        fingerDeltaPx,
        cellWidthPx,
        cellHeightPx,
    )

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
