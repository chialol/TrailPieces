package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.model.Vec2

/**
 * In-progress drag: lifted tiles follow the finger; [committedAnchor] tracks
 * which slots they will occupy after each axis push.
 *
 * @param enforceRigidLocks when true (normal play), resting lock groups move as
 *   rigid bodies. Shuffle sets this false so single-tile scramble can peel.
 */
data class DragSession(
    val grid: SlotGrid,
    val liftedTileIds: Set<Int>,
    val shapeOffsets: Map<Int, GridPos>,
    val startAnchor: GridPos,
    val committedAnchor: GridPos,
    val manifest: PuzzleManifest,
    val enforceRigidLocks: Boolean = true,
) {
    val targetSlots: Set<GridPos>
        get() = shapeOffsets.values.map { offset ->
            committedAnchor.offset(offset.row, offset.col)
        }.toSet()

    fun tryPush(direction: AxisDirection): DragSession? {
        val holes = targetSlots
        val locks = if (enforceRigidLocks) {
            LockGroupService.compute(grid, manifest)
        } else {
            LockGroupService.isolated(manifest)
        }
        val allIds = manifest.tiles.map { it.id }

        val pushed = PushService.tryPush(
            grid = grid,
            holes = holes,
            liftedTileIds = liftedTileIds,
            direction = direction,
            locks = locks,
            allTileIds = allIds,
        )
        if (pushed != null) {
            return applyPushResult(pushed)
        }

        // Grow only when pushing Up into an in-bounds blocker that could not make way.
        // Do not grow when the source is simply off the top edge.
        if (direction == AxisDirection.Up && enforceRigidLocks && hasInBoundsUpBlocker(holes)) {
            return tryInsertRowAboveAndPark()
        }
        return null
    }

    private fun hasInBoundsUpBlocker(holes: Set<GridPos>): Boolean =
        holes.any { hole ->
            val source = hole.offset(-1, 0) // tile above the hole for an Up push
            grid.inBounds(source) && grid.tileAt(source) != null &&
                grid.tileAt(source) !in liftedTileIds
        }

    /**
     * Insert an empty row at the top (shift everything down), park the lifted
     * footprint on the new top row aligned to the previous column(s).
     */
    private fun tryInsertRowAboveAndPark(): DragSession? {
        val grown = grid.insertRow(0)
        val shiftedAnchor = GridPos(committedAnchor.row + 1, committedAnchor.col)
        // New top row is empty; park footprint at row 0, same columns as before.
        val newAnchor = GridPos(0, committedAnchor.col)
        val parked = copy(
            grid = grown,
            committedAnchor = shiftedAnchor,
        )
        // Ensure target slots on the new row are empty (they are — fresh row).
        val targets = shapeOffsets.values.map { newAnchor.offset(it.row, it.col) }.toSet()
        if (targets.any { !grown.inBounds(it) || grown.tileAt(it) != null }) return null
        return parked.copy(committedAnchor = newAnchor)
    }

    fun tryFingerAimSameSizeSwap(
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? {
        val result = PlacementService.tryFingerAimSameSizeSwap(
            session = this,
            fingerDeltaPx = fingerDeltaPx,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
        ) ?: return null
        return applyPushResult(result)
    }

    fun tryFingerAimEmptyLand(
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? {
        val result = PlacementService.tryFingerAimEmptyLand(
            session = this,
            fingerDeltaPx = fingerDeltaPx,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
        ) ?: return null
        return applyPushResult(result)
    }

    fun tryCompletingHomeSameSizeSwap(
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? {
        val result = PlacementService.tryCompletingHomeSameSizeSwap(
            session = this,
            fingerDeltaPx = fingerDeltaPx,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
        ) ?: return null
        return applyPushResult(result)
    }

    fun tryNearestEmptyAlongAxis(direction: AxisDirection): DragSession? {
        val result = PlacementService.tryNearestEmptyAlongAxis(
            grid = grid,
            holes = targetSlots,
            liftedTileIds = liftedTileIds,
            direction = direction,
        ) ?: return null
        return applyPushResult(result)
    }

    private fun applyPushResult(result: PushResult): DragSession? {
        val newAnchor = anchorForHoles(result.newHoles) ?: return null
        return copy(grid = result.grid, committedAnchor = newAnchor)
    }

    private fun anchorForHoles(holes: Set<GridPos>): GridPos? {
        if (holes.isEmpty()) return null
        // Invert shapeOffsets: find anchor such that anchor+offset = hole for each tile.
        // Use lexicographic min hole as anchor if shape has (0,0) member, else derive.
        val zeroOffsetTiles = shapeOffsets.filter { it.value == GridPos(0, 0) }.keys
        if (zeroOffsetTiles.isNotEmpty()) {
            // Anchor is the hole corresponding to the (0,0) offset tile — min hole
            // matching the shape's bounding min.
            return holes.minWith(compareBy({ it.row }, { it.col }))
        }
        return holes.minWith(compareBy({ it.row }, { it.col }))
    }

    fun settle(manifest: PuzzleManifest = this.manifest): PuzzleBoard {
        // Preferential home / lock-completing land is applied by DragEngine.endDrag
        // via PlacementService.settlePreferred. Session-level settle keeps the
        // committed footprint (tests that push then settle without finger).
        return settlePlain().let { board ->
            if (manifest === this.manifest) board
            else PuzzleBoard(
                board.grid,
                LockGroupService.compute(board.grid, manifest),
                manifest,
            )
        }
    }
}

/** @see DragSession — kept for callers migrating from the old name. */
typealias ComponentDrag = DragSession
