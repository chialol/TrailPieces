package com.trailpieces.app.layers.v3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/**
 * Sequential snap-place. Only [current] can be dragged. A release near that
 * piece's bbox origin locks it and offers the next piece.
 *
 * Positions are in root pixels. The screen passes where the photo's top-left
 * currently sits (after any pan) and how wide it is drawn.
 */
class RevealSession(
    val scene: RevealScene,
    var feedback: RevealFeedback = SilentRevealFeedback,
) {
    val pieces: List<RevealPiece> =
        scene.pieces.sortedWith(compareBy(RevealPiece::order, RevealPiece::id))

    private val _placed = mutableStateListOf<Int>()
    val placedIds: List<Int> get() = _placed

    var draggingId by mutableStateOf<Int?>(null)
        private set

    val placedCount: Int get() = _placed.size

    val isComplete: Boolean get() = pieces.isNotEmpty() && _placed.size == pieces.size

    /** The single piece currently offered, in [pieces] order. */
    val current: RevealPiece? get() = pieces.firstOrNull { it.id !in _placed }

    fun piece(id: Int): RevealPiece = pieces.first { it.id == id }

    fun startDrag(pieceId: Int) {
        val piece = current ?: return
        if (pieceId != piece.id) return
        draggingId = pieceId
    }

    fun cancelDrag() {
        draggingId = null
    }

    /** Screen pixels per scene pixel. The photo is always drawn with a uniform scale. */
    fun displayScale(imageWidthPx: Float): Float = imageWidthPx / scene.width.toFloat()

    fun homeTopLeftInRoot(
        piece: RevealPiece,
        imageTopLeftInRoot: Offset,
        imageWidthPx: Float,
    ): Offset {
        val scale = displayScale(imageWidthPx)
        return imageTopLeftInRoot + Offset(piece.bbox.left * scale, piece.bbox.top * scale)
    }

    fun tryPlace(
        pieceTopLeftInRoot: Offset,
        imageTopLeftInRoot: Offset,
        imageWidthPx: Float,
        snapThresholdPx: Float,
    ): PlaceOutcome {
        val dragId = draggingId ?: return PlaceOutcome.Ignored
        val piece = current ?: return PlaceOutcome.Ignored
        draggingId = null
        if (dragId != piece.id) return PlaceOutcome.Ignored
        val home = homeTopLeftInRoot(piece, imageTopLeftInRoot, imageWidthPx)
        val dist = hypot(
            pieceTopLeftInRoot.x - home.x,
            pieceTopLeftInRoot.y - home.y,
        )
        if (dist > snapThresholdPx) return PlaceOutcome.Missed
        _placed.add(piece.id)
        feedback.onCorrectRelease(piece.id)
        if (isComplete) feedback.onSceneAlive()
        return PlaceOutcome.Locked
    }
}

/**
 * Top-left so the point at [grabFraction] (0–1 across the image) stays on [fingerInRoot].
 * Growing the image keeps that same point under the finger; the rest may extend off screen.
 */
fun anchoredTopLeft(
    fingerInRoot: Offset,
    grabFraction: Offset,
    widthPx: Float,
    heightPx: Float,
): Offset {
    val across = grabFraction.x.coerceIn(0f, 1f)
    val down = grabFraction.y.coerceIn(0f, 1f)
    return Offset(
        fingerInRoot.x - across * widthPx,
        fingerInRoot.y - down * heightPx,
    )
}

/**
 * How the photo sits in the play area. Landscape fills the height and pans
 * sideways. Portrait fits inside the area so it never pans.
 */
data class PhotoFit(val widthPx: Float, val heightPx: Float) {
    fun pans(viewportWidthPx: Float): Boolean = widthPx > viewportWidthPx + 1f

    companion object {
        fun of(scene: RevealScene, viewportWidthPx: Float, viewportHeightPx: Float): PhotoFit {
            val aspect = scene.aspectRatio
            if (scene.isLandscape) {
                return PhotoFit(viewportHeightPx * aspect, viewportHeightPx)
            }
            val width = minOf(viewportWidthPx, viewportHeightPx * aspect)
            return PhotoFit(width, width / aspect)
        }
    }
}
