package com.trailpieces.app.puzzle

/**
 * In-progress drag: lifted tiles follow the finger; [committedAnchor] tracks
 * which slots they will occupy after each axis push.
 */
data class ComponentDrag(
    /** Grid with lifted cells empty. */
    val grid: SlotGrid,
    val liftedTileIds: Set<Int>,
    /** Each lifted tile's offset from [committedAnchor]. */
    val shapeOffsets: Map<Int, GridPos>,
    val startAnchor: GridPos,
    val committedAnchor: GridPos,
) {
    /** Slots the component will occupy when dropped. */
    val targetSlots: Set<GridPos>
        get() = shapeOffsets.values.map { offset ->
            committedAnchor.offset(offset.row, offset.col)
        }.toSet()

    fun tryPush(direction: AxisDirection): ComponentDrag? {
        val holes = grid.emptySlots()
        val nextGrid = PushEngine.tryPush(
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
        return PuzzleBoard(settled, LockGroups.compute(settled, manifest), manifest)
    }
}
