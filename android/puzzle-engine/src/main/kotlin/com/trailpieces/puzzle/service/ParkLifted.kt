package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.GridPos

/**
 * Default release: park lifted tiles on [DragSession.committedAnchor] holes,
 * collapse fully empty rows, recompute locks. Resting tile cells are unchanged.
 */
object ParkLifted {

    fun apply(session: DragSession): PuzzleBoard {
        for ((tileId, offset) in session.shapeOffsets) {
            val slot = session.committedAnchor.offset(offset.row, offset.col)
            require(session.grid.inBounds(slot)) {
                "Tile $tileId would settle out of bounds at $slot"
            }
            require(session.grid.tileAt(slot) == null) {
                "Tile $tileId cannot settle onto occupied slot $slot"
            }
        }
        val settled = session.grid.withCells { cells ->
            session.shapeOffsets.forEach { (tileId, offset) ->
                val slot = session.committedAnchor.offset(offset.row, offset.col)
                cells[slot.index(session.grid.cols)] = tileId
            }
        }
        val collapsed = settled.collapseEmptyRows()
        return PuzzleBoard(
            collapsed,
            LockGroupService.compute(collapsed, session.manifest),
            session.manifest,
        )
    }
}

/** @see ParkLifted.apply */
internal fun DragSession.settlePlain(): PuzzleBoard = ParkLifted.apply(this)
