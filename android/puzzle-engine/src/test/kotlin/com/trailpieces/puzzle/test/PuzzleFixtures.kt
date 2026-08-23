package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.service.LockGroupService
import com.trailpieces.puzzle.service.PuzzleBoard

/**
 * Small boards for unit tests. Prefer full placements (every slot filled)
 * unless the scenario intentionally leaves empties.
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
     * Playfield that may be taller than [manifest.rows] (extra empty rows / growth).
     * [placements] maps slot → tile id; unlisted slots stay empty.
     */
    fun playfield(
        manifest: PuzzleManifest,
        rows: Int,
        placements: Map<GridPos, Int>,
    ): PuzzleBoard {
        require(rows >= manifest.rows) { "Playfield rows $rows < manifest ${manifest.rows}" }
        val grid = SlotGrid.empty(manifest.cols, rows).withCells { cells ->
            placements.forEach { (pos, tileId) ->
                require(pos.row in 0 until rows && pos.col in 0 until manifest.cols)
                cells[pos.index(manifest.cols)] = tileId
            }
        }
        return PuzzleBoard(grid, LockGroupService.compute(grid, manifest), manifest)
    }

    /**
     * Place tiles explicitly by id at grid positions on the manifest-sized grid.
     * [placements] maps slot → tile id. Unlisted slots stay empty.
     */
    fun boardWithPlacements(
        manifest: PuzzleManifest,
        placements: Map<GridPos, Int>,
    ): PuzzleBoard = playfield(manifest, manifest.rows, placements)

    /** Full board from a row-major list of tile ids (length == cols * rows). */
    fun boardFromRowMajor(
        manifest: PuzzleManifest,
        tileIds: List<Int>,
    ): PuzzleBoard {
        require(tileIds.size == manifest.slotCount) {
            "Expected ${manifest.slotCount} tile ids, got ${tileIds.size}"
        }
        val placements = tileIds.mapIndexed { index, id ->
            GridPos.fromIndex(index, manifest.cols) to id
        }.toMap()
        return boardWithPlacements(manifest, placements)
    }

    /**
     * Col 0 is the correct vertical strip (tiles 0,2,4 locked).
     * Col 1 has horizontal neighbors that lock with each other ({1,3}) but not into col 0.
     *
     * ```
     * 0 5
     * 2 1
     * 4 3
     * ```
     */
    fun lockedLeftColumnBoard(manifest: PuzzleManifest = miniManifest()): PuzzleBoard =
        boardFromRowMajor(manifest, listOf(0, 5, 2, 1, 4, 3))

    /**
     * Locked vertical pair {3,5} in col 1; other tiles do not join that lock.
     *
     * ```
     * 0 2
     * 4 3
     * 1 5
     * ```
     */
    fun lockedVerticalPairInCol1(manifest: PuzzleManifest = miniManifest()): PuzzleBoard =
        boardFromRowMajor(manifest, listOf(0, 2, 4, 3, 1, 5))

    /**
     * Locked horizontal pair {0,1} on row 0 only.
     *
     * ```
     * 0 1
     * 3 2
     * 5 4
     * ```
     */
    fun lockedHorizontalPairOnRow0(manifest: PuzzleManifest = miniManifest()): PuzzleBoard =
        boardFromRowMajor(manifest, listOf(0, 1, 3, 2, 5, 4))

    /**
     * Horizontal pair {0,1} on row 0; row 1 is unlocked from row 2 so a Down
     * push of the pair still gets classic size-1 fillers.
     *
     * ```
     * 0 1
     * 3 2
     * 4 5
     * ```
     */
    fun horizontalPairWithLooseRowBelow(manifest: PuzzleManifest = miniManifest()): PuzzleBoard =
        boardFromRowMajor(manifest, listOf(0, 1, 3, 2, 4, 5))

    /**
     * L-triomino {0,2,3} at correct relative homes; remaining tiles do not join the lock.
     *
     * ```
     * 0 5
     * 2 3
     * 1 4
     * ```
     */
    fun lockedLTriominoBoard(manifest: PuzzleManifest = miniManifest()): PuzzleBoard =
        boardFromRowMajor(manifest, listOf(0, 5, 2, 3, 1, 4))

    private fun tile(id: Int, row: Int, col: Int, file: String) =
        PuzzleTile(id = id, home = GridPos(row, col), assetPath = file)
}
