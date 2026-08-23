package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.service.PuzzleBoard

/**
 * 2×6 letter board for cascade / pack scenarios:
 * ```
 * A B
 * C D
 * E F
 * G H
 * I J
 * K L
 * ```
 * Tile ids: A=0 … L=11 at solved homes.
 */
object TwelveLetterGrid {
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
            id = "test-twelve",
            title = "Twelve",
            cols = 2,
            rows = 6,
            puzzleWidth = 200,
            puzzleHeight = 600,
            tiles = letters.mapIndexed { id, (_, home) ->
                PuzzleTile(id = id, home = home, assetPath = "t$id")
            },
        )
    }

    val A get() = GridPos(0, 0)
    val B get() = GridPos(0, 1)
    val C get() = GridPos(1, 0)
    val D get() = GridPos(1, 1)
    val E get() = GridPos(2, 0)
    val F get() = GridPos(2, 1)
    val G get() = GridPos(3, 0)
    val H get() = GridPos(3, 1)
    val I get() = GridPos(4, 0)
    val J get() = GridPos(4, 1)
    val K get() = GridPos(5, 0)
    val L get() = GridPos(5, 1)

    fun solvedBoard(): PuzzleBoard = PuzzleBoard.solved(manifest)

    /** Solved layout on a 7-row playfield (extra empty row at bottom). */
    fun solvedPlayfield7(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 7,
        placements = manifest.tiles.associate { it.home to it.id },
    )

    /** Scrambled layout: {A,B,D} separate from vertical {F,H,J}; L parked at bottom-left. */
    fun cascadePackBoard7(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 7,
        placements = mapOf(
            GridPos(0, 0) to 0, GridPos(0, 1) to 1,
            GridPos(1, 0) to 10, GridPos(1, 1) to 3,
            GridPos(2, 1) to 5,
            GridPos(3, 0) to 8, GridPos(3, 1) to 7,
            GridPos(4, 0) to 2, GridPos(4, 1) to 9,
            GridPos(5, 0) to 6,
            GridPos(6, 0) to 11, GridPos(6, 1) to 4,
        ),
    )
}
