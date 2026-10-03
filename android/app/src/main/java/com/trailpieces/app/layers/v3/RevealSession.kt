package com.trailpieces.app.layers.v3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.trailpieces.app.layers.LayerBBox
import kotlin.math.hypot

/**
 * Sequential snap-place. Only [current] can be dragged. A release near that
 * piece's bbox origin locks it and offers the next piece. Pan is owned by the
 * screen; pass the current scroll in so the home stays attached to the photo.
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

    fun startDrag(pieceId: Int) {
        val piece = current ?: return
        if (pieceId != piece.id) return
        draggingId = pieceId
    }

    fun cancelDrag() {
        draggingId = null
    }

    fun pieceSizePx(
        piece: RevealPiece,
        imageWidthPx: Float,
        imageHeightPx: Float,
    ): Pair<Float, Float> {
        val scaleX = imageWidthPx / scene.width.toFloat()
        val scaleY = imageHeightPx / scene.height.toFloat()
        return piece.bbox.width * scaleX to piece.bbox.height * scaleY
    }

    /**
     * Top-left of [piece]'s bbox in the same root space as the viewport.
     * Landscape photos that are wider than the viewport shift by [scrollPx].
     * A photo narrower than the viewport is centered, and scroll is ignored.
     */
    fun homeTopLeftInRoot(
        piece: RevealPiece,
        viewportLeft: Float,
        viewportTop: Float,
        viewportWidthPx: Float,
        imageWidthPx: Float,
        imageHeightPx: Float,
        scrollPx: Float,
    ): Offset {
        val scaleX = imageWidthPx / scene.width.toFloat()
        val scaleY = imageHeightPx / scene.height.toFloat()
        val overflow = imageWidthPx - viewportWidthPx
        val imageLeft = if (overflow <= 1f) {
            viewportLeft + (viewportWidthPx - imageWidthPx) / 2f
        } else {
            viewportLeft - scrollPx.coerceIn(0f, overflow)
        }
        return Offset(
            imageLeft + piece.bbox.left * scaleX,
            viewportTop + piece.bbox.top * scaleY,
        )
    }

    fun tryPlace(
        pieceTopLeftInRoot: Offset,
        viewportTopLeftInRoot: Offset,
        viewportWidthPx: Float,
        imageWidthPx: Float,
        imageHeightPx: Float,
        scrollPx: Float,
        snapThresholdPx: Float,
    ): PlaceOutcome {
        val dragId = draggingId ?: return PlaceOutcome.Ignored
        val piece = current ?: return PlaceOutcome.Ignored
        if (dragId != piece.id) {
            draggingId = null
            return PlaceOutcome.Ignored
        }
        val home = homeTopLeftInRoot(
            piece = piece,
            viewportLeft = viewportTopLeftInRoot.x,
            viewportTop = viewportTopLeftInRoot.y,
            viewportWidthPx = viewportWidthPx,
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx,
            scrollPx = scrollPx,
        )
        val dist = hypot(
            pieceTopLeftInRoot.x - home.x,
            pieceTopLeftInRoot.y - home.y,
        )
        draggingId = null
        if (dist > snapThresholdPx) return PlaceOutcome.Missed
        _placed.add(piece.id)
        feedback.onCorrectRelease(piece.id)
        if (isComplete) feedback.onSceneAlive()
        return PlaceOutcome.Locked
    }
}

private val LayerBBox.width: Float get() = (right - left).toFloat()
private val LayerBBox.height: Float get() = (bottom - top).toFloat()

/**
 * Top-left so the point at [grabFraction] (0–1 across the piece) stays on [fingerInRoot].
 * Growing the piece keeps that same point under the finger; the rest may extend off screen.
 */
fun anchoredTopLeft(
    fingerInRoot: Offset,
    grabFraction: Offset,
    pieceWidthPx: Float,
    pieceHeightPx: Float,
): Offset {
    val across = grabFraction.x.coerceIn(0f, 1f)
    val down = grabFraction.y.coerceIn(0f, 1f)
    return Offset(
        fingerInRoot.x - across * pieceWidthPx,
        fingerInRoot.y - down * pieceHeightPx,
    )
}
