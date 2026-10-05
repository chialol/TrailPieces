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

    /** Opens the finished photo with every piece already home. */
    fun restoreComplete() {
        draggingId = null
        _placed.clear()
        pieces.forEach { _placed.add(it.id) }
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

        /** Whole photo inside the viewport. Landscape no longer fills the height. */
        fun contained(scene: RevealScene, viewportWidthPx: Float, viewportHeightPx: Float): PhotoFit {
            val aspect = scene.aspectRatio.coerceAtLeast(0.01f)
            val width = minOf(viewportWidthPx, viewportHeightPx * aspect)
            return PhotoFit(width, width / aspect)
        }
    }
}

/**
 * One frame of the lens pull-back. [t] is 0 at the play framing and 1 when the
 * whole photo fits. The point at the center of the viewport stays put until
 * the image is narrow enough to center.
 */
data class LensFrame(val fit: PhotoFit, val scrollPx: Float)

fun lensFrame(
    play: PhotoFit,
    contain: PhotoFit,
    viewportWidthPx: Float,
    startScrollPx: Float,
    t: Float,
): LensFrame {
    val u = t.coerceIn(0f, 1f)
    val width = play.widthPx + (contain.widthPx - play.widthPx) * u
    val height = if (play.widthPx <= 0f) play.heightPx else width * play.heightPx / play.widthPx
    val center = startScrollPx + viewportWidthPx / 2f
    val fraction = if (play.widthPx <= 0f) 0.5f else center / play.widthPx
    val desired = fraction * width - viewportWidthPx / 2f
    val maxScroll = (width - viewportWidthPx).coerceAtLeast(0f)
    return LensFrame(PhotoFit(width, height), desired.coerceIn(0f, maxScroll))
}

fun maxPinch(play: PhotoFit, contain: PhotoFit): Float {
    val back = if (contain.widthPx <= 0f) 1f else play.widthPx / contain.widthPx
    return maxOf(back, 3f)
}

fun pinchScale(current: Float, zoom: Float, maxScale: Float): Float {
    return (current * zoom).coerceIn(1f, maxScale.coerceAtLeast(1f))
}

/** Keeps a zoomed photo from sliding so far that an empty gap opens inside the viewport. */
fun pinchPan(
    pan: Offset,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
): Offset {
    val maxX = ((contentWidthPx - viewportWidthPx) / 2f).coerceAtLeast(0f)
    val maxY = ((contentHeightPx - viewportHeightPx) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

enum class BandMode { Overlay, Shrink }

/**
 * Where the reading band sits once the photo fits.
 * A tall letterbox holds the band without moving the photo.
 * A photo that already fills the height shrinks to open one.
 */
data class SettledBand(val mode: BandMode, val bandPx: Float)

fun settledBand(
    containHeightPx: Float,
    viewportHeightPx: Float,
    minBandPx: Float,
    maxBandPx: Float,
): SettledBand {
    val letterbox = ((viewportHeightPx - containHeightPx) / 2f).coerceAtLeast(0f)
    if (letterbox >= minBandPx) {
        return SettledBand(BandMode.Overlay, letterbox.coerceAtMost(maxBandPx))
    }
    val band = minBandPx.coerceAtMost(viewportHeightPx * 0.45f).coerceAtLeast(0f)
    return SettledBand(BandMode.Shrink, band)
}
