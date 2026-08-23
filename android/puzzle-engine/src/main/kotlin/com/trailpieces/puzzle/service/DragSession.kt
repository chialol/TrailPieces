package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.model.step

/**
 * In-progress drag: lifted tiles follow the finger; [committedAnchor] tracks
 * which slots they will occupy after each axis push.
 */
data class DragSession(
    val grid: SlotGrid,
    val liftedTileIds: Set<Int>,
    val shapeOffsets: Map<Int, GridPos>,
    val startAnchor: GridPos,
    val committedAnchor: GridPos,
) {
    val targetSlots: Set<GridPos>
        get() = shapeOffsets.values.map { offset ->
            committedAnchor.offset(offset.row, offset.col)
        }.toSet()

    fun tryPush(direction: AxisDirection): DragSession? {
        val holes = grid.emptySlots()
        val nextGrid = PushService.tryPush(
            grid = grid,
            holes = holes,
            liftedTileIds = liftedTileIds,
            direction = direction,
        ) ?: return null

        return copy(
            grid = nextGrid,
            committedAnchor = committedAnchor.step(direction),
        )
    }

    fun settle(manifest: PuzzleManifest): PuzzleBoard {
        for ((tileId, offset) in shapeOffsets) {
            val slot = committedAnchor.offset(offset.row, offset.col)
            require(grid.inBounds(slot)) { "Tile $tileId would settle out of bounds at $slot" }
        }
        val settled = grid.withCells { cells ->
            shapeOffsets.forEach { (tileId, offset) ->
                val slot = committedAnchor.offset(offset.row, offset.col)
                cells[slot.index(grid.cols)] = tileId
            }
        }
        return PuzzleBoard(settled, LockGroupService.compute(settled, manifest), manifest)
    }
}

/** @see DragSession — kept for callers migrating from the old name. */
typealias ComponentDrag = DragSession
