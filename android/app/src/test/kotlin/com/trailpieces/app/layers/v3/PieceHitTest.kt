package com.trailpieces.app.layers.v3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceHitTest {
    @Test
    fun opaquePixelHitsAndTransparentPixelMisses() {
        val mask = mask(4, 3, opaque = setOf(1 to 1))
        assertTrue(hitsOpaque(mask, 1, 1))
        assertFalse(hitsOpaque(mask, 0, 0))
        assertFalse(hitsOpaque(mask, -1, 1))
        assertFalse(hitsOpaque(mask, 4, 1))
    }

    @Test
    fun highAlphaStoredInASignedByteStillHits() {
        val alpha = ByteArray(1) { 200.toByte() }
        assertTrue(hitsOpaque(PieceMask(1, 1, alpha), 0, 0))
    }

    @Test
    fun softFringeDoesNotCount() {
        val alpha = ByteArray(1) { 20.toByte() }
        assertFalse(hitsOpaque(PieceMask(1, 1, alpha), 0, 0))
    }

    @Test
    fun tapOnThePieceHitsAndTapOnTheShadowMisses() {
        // Lift is 10×10 with a 2px pad. Crop is 6×6. One opaque pixel at (3, 3).
        val mask = mask(6, 6, opaque = setOf(3 to 3))
        assertTrue(tap(55f, 55f, mask))
        assertFalse(tap(5f, 5f, mask))
        assertFalse(tap(55f, 20f, mask))
    }

    @Test
    fun aHoleInsideTheBoundsMisses() {
        val opaque = buildSet {
            for (y in 0 until 5) for (x in 0 until 5) {
                if (!(x == 2 && y == 2)) add(x to y)
            }
        }
        val mask = mask(5, 5, opaque)
        assertTrue(tapOnCrop(mask, 0, 0))
        assertFalse(tapOnCrop(mask, 2, 2))
    }

    private fun tap(x: Float, y: Float, mask: PieceMask): Boolean =
        tapLandsOnPiece(x, y, 100f, 100f, 10, 10, 2, mask)

    /** Maps a crop pixel through a lift that is exactly the crop, with no pad. */
    private fun tapOnCrop(mask: PieceMask, cropX: Int, cropY: Int): Boolean {
        val x = (cropX + 0.5f) / mask.width * 100f
        val y = (cropY + 0.5f) / mask.height * 100f
        return tapLandsOnPiece(x, y, 100f, 100f, mask.width, mask.height, 0, mask)
    }

    private fun mask(width: Int, height: Int, opaque: Set<Pair<Int, Int>>): PieceMask {
        val alpha = ByteArray(width * height)
        opaque.forEach { (x, y) -> alpha[y * width + x] = 255.toByte() }
        return PieceMask(width, height, alpha)
    }
}
