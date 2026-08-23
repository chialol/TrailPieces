package com.trailpieces.puzzle.demo

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.service.PuzzleBoard

/**
 * 2×6 letter cascade / pack demo (7-row playfield).
 * Drag {A,B,D} down; {F,H,J} stay rigid and pack into col1 when a pocket opens.
 *
 * Enable in the app via [CascadeDemo.ENABLED] (debug builds).
 */
object CascadeDemo {
    /** Set true to load the cascade pack demo board (debug builds only). */
    const val ENABLED = false

    val manifest: PuzzleManifest by lazy {
        val letters = listOf(
            "A" to GridPos(0, 0),
            "B" to GridPos(0, 1),
            "C" to GridPos(1, 0),
            "D" to GridPos(1, 1),
            "E" to GridPos(2, 0),
            "F" to GridPos(2, 1),
            "G" to GridPos(3, 0),
            "H" to GridPos(3, 1),
            "I" to GridPos(4, 0),
            "J" to GridPos(4, 1),
            "K" to GridPos(5, 0),
            "L" to GridPos(5, 1),
        )
        PuzzleManifest(
            id = "cascade-demo",
            title = "Cascade pack (demo)",
            cols = 2,
            rows = 6,
            puzzleWidth = 400,
            puzzleHeight = 1200,
            tiles = letters.mapIndexed { id, (_, home) ->
                PuzzleTile(id = id, home = home, assetPath = "tiles/tile_${home.row}_${home.col}.webp")
            },
        )
    }

    fun board(base: PuzzleManifest? = null): PuzzleBoard {
        val m = if (base != null) {
            manifest.copy(
                tiles = manifest.tiles.map { tile ->
                    tile.copy(assetPath = base.tiles[tile.id].assetPath)
                },
            )
        } else {
            manifest
        }
        val grid = com.trailpieces.puzzle.model.SlotGrid.empty(m.cols, 7).withCells { cells ->
            fun place(pos: GridPos, id: Int) {
                cells[pos.index(m.cols)] = id
            }
            place(GridPos(0, 0), 0)
            place(GridPos(0, 1), 1)
            place(GridPos(1, 0), 10)
            place(GridPos(1, 1), 3)
            place(GridPos(2, 1), 5)
            place(GridPos(3, 0), 8)
            place(GridPos(3, 1), 7)
            place(GridPos(4, 0), 2)
            place(GridPos(4, 1), 9)
            place(GridPos(5, 0), 6)
            place(GridPos(6, 0), 11)
            place(GridPos(6, 1), 4)
        }
        return PuzzleBoard(
            grid,
            com.trailpieces.puzzle.service.LockGroupService.compute(grid, m),
            m,
        )
    }
}
