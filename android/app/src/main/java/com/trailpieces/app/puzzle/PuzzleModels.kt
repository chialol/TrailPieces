package com.trailpieces.app.puzzle

/** A single puzzle piece. [home] is its correct slot in the solved image. */
data class PuzzleTile(
    val id: Int,
    val home: GridPos,
    val assetPath: String,
)

/** Puzzle definition loaded from assets. */
data class PuzzleManifest(
    val id: String,
    val title: String,
    val cols: Int,
    val rows: Int,
    val puzzleWidth: Int,
    val puzzleHeight: Int,
    val tiles: List<PuzzleTile>,
    val previewFile: String? = null,
) {
    val slotCount: Int get() = rows * cols

    fun tile(id: Int): PuzzleTile = tiles.first { it.id == id }

    fun tileOrNull(id: Int): PuzzleTile? = tiles.firstOrNull { it.id == id }
}
