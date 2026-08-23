package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.service.LockGroupService
import com.trailpieces.puzzle.service.PuzzleBoard

/**
 * Builds small boards for unit tests. Extend this as we add scenario tests
 * (connected components, push chains, rigid-group behavior).
 */
object PuzzleFixtures {

    /** 2×3 grid, six tiles — small enough to reason about by hand. */
    fun miniManifest(): PuzzleManifest {
        val tiles = listOf(
            tile(0, 0, 0, "t00"),
            tile(1, 0, 1, "t01"),
            tile(2, 1, 0, "t10"),
            tile(3, 1, 1, "t11"),
            tile(4, 2, 0, "t20"),
            tile(5, 2, 1, "t21"),
        )
        return PuzzleManifest(
            id = "test-mini",
            title = "Mini",
            cols = 2,
            rows = 3,
            puzzleWidth = 200,
            puzzleHeight = 300,
            tiles = tiles,
        )
    }

    fun solvedBoard(manifest: PuzzleManifest = miniManifest()): PuzzleBoard =
        PuzzleBoard.solved(manifest)

    /**
     * Place tiles explicitly by id at grid positions.
     * [placements] maps slot → tile id.
     */
    fun boardWithPlacements(
        manifest: PuzzleManifest,
        placements: Map<GridPos, Int>,
    ): PuzzleBoard {
        val grid = SlotGrid.empty(manifest.cols, manifest.rows).withCells { cells ->
            placements.forEach { (pos, tileId) ->
                cells[pos.index(manifest.cols)] = tileId
            }
        }
        return PuzzleBoard(grid, LockGroupService.compute(grid, manifest), manifest)
    }

    private fun tile(id: Int, row: Int, col: Int, file: String) =
        PuzzleTile(id = id, home = GridPos(row, col), assetPath = file)
}
