package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.model.Vec2

/**
 * In-progress drag snapshot. Layout commits go through [MidDragMotion] only.
 *
 * @param frozenLocks lock partitions frozen at pointer-down ([FrozenLockGraph]).
 */
data class DragSession(
    val grid: SlotGrid,
    val liftedTileIds: Set<Int>,
    val shapeOffsets: Map<Int, GridPos>,
    val startAnchor: GridPos,
    val committedAnchor: GridPos,
    val manifest: PuzzleManifest,
    val enforceRigidLocks: Boolean = true,
    val frozenLocks: FrozenLockGraph = FrozenLockGraph.isolated(manifest),
) {
    val targetSlots: Set<GridPos>
        get() = shapeOffsets.values.map { offset ->
            committedAnchor.offset(offset.row, offset.col)
        }.toSet()

    /** Frozen pointer-down rigidity for mid-drag motion. */
    fun rigidLocks(): FrozenLockGraph =
        if (enforceRigidLocks) frozenLocks else FrozenLockGraph.isolated(manifest)

    fun tryPush(direction: AxisDirection): DragSession? =
        MidDragMotion.tryPush(this, direction)

    fun tryFingerAimSameSizeSwap(
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? = MidDragMotion.tryFingerAimSameSizeSwap(this, fingerDeltaPx, cellWidthPx, cellHeightPx)

    fun tryFingerAimEmptyLand(
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? = MidDragMotion.tryFingerAimEmptyLand(this, fingerDeltaPx, cellWidthPx, cellHeightPx)

    fun tryCompletingHomeSameSizeSwap(
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? = MidDragMotion.tryCompletingHomeSameSizeSwap(this, fingerDeltaPx, cellWidthPx, cellHeightPx)

    fun tryNearestEmptyAlongAxis(direction: AxisDirection): DragSession? =
        MidDragMotion.tryNearestEmptyAlongAxis(this, direction)

    internal fun withPushResult(result: PushResult): DragSession? {
        val newAnchor = GridGeometry.anchorForHoles(result.newHoles) ?: return null
        return copy(grid = result.grid, committedAnchor = newAnchor)
    }

    fun settle(manifest: PuzzleManifest = this.manifest): PuzzleBoard =
        ParkLifted.apply(this).let { board ->
            if (manifest === this.manifest) board
            else PuzzleBoard(
                board.grid,
                LockGroupService.compute(board.grid, manifest),
                manifest,
            )
        }
}

/** @see DragSession */
typealias ComponentDrag = DragSession
