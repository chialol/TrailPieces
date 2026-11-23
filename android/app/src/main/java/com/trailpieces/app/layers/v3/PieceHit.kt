package com.trailpieces.app.layers.v3

import kotlin.math.floor

/**
 * Alpha of one piece's tight crop, row-major. Opaque samples are the cut
 * shape. The rectangular crop and the lift's outline and shadow are not.
 */
class PieceMask(
    val width: Int,
    val height: Int,
    val alpha: ByteArray,
) {
    init {
        require(width > 0 && height > 0 && alpha.size == width * height)
    }
}

/** Alpha at or above this counts as the piece. Softer fringe does not. */
const val PieceHitAlpha = 32

fun hitsOpaque(mask: PieceMask, x: Int, y: Int, threshold: Int = PieceHitAlpha): Boolean {
    if (x < 0 || y < 0 || x >= mask.width || y >= mask.height) return false
    return (mask.alpha[y * mask.width + x].toInt() and 0xFF) >= threshold
}

/**
 * A tap on the displayed lift. [liftPad] is the transparent margin, in lift
 * pixels, around the crop. Points in that margin, or on transparent crop
 * pixels, are outside the piece.
 */
fun tapLandsOnPiece(
    localX: Float,
    localY: Float,
    viewWidth: Float,
    viewHeight: Float,
    liftWidth: Int,
    liftHeight: Int,
    liftPad: Int,
    mask: PieceMask,
): Boolean {
    if (viewWidth <= 0f || viewHeight <= 0f || liftWidth <= 0 || liftHeight <= 0) return false
    if (localX < 0f || localY < 0f || localX >= viewWidth || localY >= viewHeight) return false
    val liftX = localX / viewWidth * liftWidth
    val liftY = localY / viewHeight * liftHeight
    val cropSpanX = (liftWidth - 2 * liftPad).coerceAtLeast(1)
    val cropSpanY = (liftHeight - 2 * liftPad).coerceAtLeast(1)
    val cropX = (liftX - liftPad) * mask.width / cropSpanX.toFloat()
    val cropY = (liftY - liftPad) * mask.height / cropSpanY.toFloat()
    if (cropX < 0f || cropY < 0f) return false
    return hitsOpaque(mask, floor(cropX).toInt(), floor(cropY).toInt())
}
