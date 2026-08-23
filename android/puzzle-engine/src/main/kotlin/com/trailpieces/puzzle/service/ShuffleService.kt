package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos

/** Shuffles a solved board via single-tile axis pushes (no lock groups). */
object ShuffleService {

    fun shuffled(manifest: com.trailpieces.puzzle.model.PuzzleManifest, moves: Int = 150): PuzzleBoard {
        var board = PuzzleBoard.solved(manifest).copy(locks = LockGroupService.isolated(manifest))

        repeat(moves) {
            board = randomPush(board, singleTile = true) ?: board
        }

        var attempts = 0
        while (board.isSolved && attempts < 20) {
            board = randomPush(board, singleTile = true) ?: swapRandom(board)
            attempts++
        }

        return board.copy(locks = LockGroupService.compute(board.grid, manifest))
    }

    private fun randomPush(board: PuzzleBoard, singleTile: Boolean): PuzzleBoard? {
        val filled = allFilledSlots(board)
        if (filled.isEmpty()) return null

        var drag = board.beginDrag(filled.random(), grouped = !singleTile) ?: return null
        repeat((1..4).random()) {
            val next = AxisDirection.entries.shuffled().firstNotNullOfOrNull { dir ->
                drag.tryPush(dir)
            } ?: return@repeat
            drag = next
        }
        return drag.settle(board.manifest)
    }

    private fun swapRandom(board: PuzzleBoard): PuzzleBoard {
        val filled = allFilledSlots(board)
        if (filled.size < 2) return board
        val a = filled.random()
        var b = filled.random()
        while (b == a) b = filled.random()
        return swap(board, a, b)
    }

    private fun swap(board: PuzzleBoard, a: GridPos, b: GridPos): PuzzleBoard {
        val tileA = board.tileAt(a) ?: return board
        val tileB = board.tileAt(b) ?: return board
        val grid = board.grid.withCells { cells ->
            cells[a.index(board.cols)] = tileB
            cells[b.index(board.cols)] = tileA
        }
        return board.copy(grid = grid)
    }

    private fun allFilledSlots(board: PuzzleBoard): List<GridPos> =
        (0 until board.manifest.slotCount).map { GridPos.fromIndex(it, board.cols) }
            .filter { board.tileAt(it) != null }
}

/** @see ShuffleService */
typealias PuzzleShuffle = ShuffleService
