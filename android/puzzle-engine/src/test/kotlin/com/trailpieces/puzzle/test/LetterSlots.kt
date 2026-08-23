package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.service.PuzzleBoard

/**
 * Slot letters on the mini 2×3 board (row-major):
 *
 * ```
 * A B
 * C D
 * E F
 * ```
 */
object LetterSlots {
    val A = GridPos(0, 0)
    val B = GridPos(0, 1)
    val C = GridPos(1, 0)
    val D = GridPos(1, 1)
    val E = GridPos(2, 0)
    val F = GridPos(2, 1)

    /** Build a board from a 6-char string like `"05 21 34"` or `"052134"` (tile ids). */
    fun board(tileIdsRowMajor: String, manifest: com.trailpieces.puzzle.model.PuzzleManifest = PuzzleFixtures.miniManifest()): PuzzleBoard {
        val ids = tileIdsRowMajor.filter { it.isDigit() }.map { it.digitToInt() }
        require(ids.size == 6) { "Need 6 tile ids, got ${ids.size} from '$tileIdsRowMajor'" }
        return PuzzleFixtures.boardFromRowMajor(manifest, ids)
    }

    fun label(pos: GridPos): String = when (pos) {
        A -> "A"
        B -> "B"
        C -> "C"
        D -> "D"
        E -> "E"
        F -> "F"
        else -> pos.toString()
    }
}
