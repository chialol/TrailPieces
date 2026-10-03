package com.trailpieces.app.layers.v3

import androidx.compose.ui.geometry.Offset
import com.trailpieces.app.layers.LayerBBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealSessionTest {
    @Test
    fun offersPiecesInOrder() {
        val session = RevealSession(scene())
        assertEquals(1, session.current?.id)
        session.startDrag(2)
        assertNull(session.draggingId)
    }

    @Test
    fun lockAdvancesAndMissStays() {
        val feedback = RecordingFeedback()
        val session = RevealSession(scene(), feedback)
        session.startDrag(1)
        assertEquals(
            PlaceOutcome.Locked,
            session.tryPlace(
                pieceTopLeftInRoot = Offset(100f, 200f),
                viewportTopLeftInRoot = Offset.Zero,
                viewportWidthPx = 1000f,
                imageWidthPx = 1000f,
                imageHeightPx = 500f,
                scrollPx = 0f,
                snapThresholdPx = 72f,
            ),
        )
        assertEquals(listOf(1), feedback.locked)
        assertEquals(0, feedback.aliveCount)
        assertEquals(2, session.current?.id)

        session.startDrag(2)
        assertEquals(
            PlaceOutcome.Missed,
            session.tryPlace(
                pieceTopLeftInRoot = Offset(0f, 0f),
                viewportTopLeftInRoot = Offset.Zero,
                viewportWidthPx = 1000f,
                imageWidthPx = 1000f,
                imageHeightPx = 500f,
                scrollPx = 0f,
                snapThresholdPx = 72f,
            ),
        )
        assertEquals(2, session.current?.id)
        assertEquals(0, feedback.aliveCount)

        session.startDrag(2)
        assertEquals(
            PlaceOutcome.Locked,
            session.tryPlace(
                pieceTopLeftInRoot = Offset(400f, 100f),
                viewportTopLeftInRoot = Offset.Zero,
                viewportWidthPx = 1000f,
                imageWidthPx = 1000f,
                imageHeightPx = 500f,
                scrollPx = 0f,
                snapThresholdPx = 72f,
            ),
        )
        assertTrue(session.isComplete)
        assertEquals(listOf(1, 2), feedback.locked)
        assertEquals(1, feedback.aliveCount)
        assertNull(session.current)
    }

    @Test
    fun grabPointStaysUnderTheFingerWhenThePieceGrows() {
        val finger = Offset(400f, 900f)
        val fraction = Offset(0.25f, 0.8f)
        val icon = anchoredTopLeft(finger, fraction, pieceWidthPx = 80f, pieceHeightPx = 48f)
        val full = anchoredTopLeft(finger, fraction, pieceWidthPx = 1800f, pieceHeightPx = 640f)
        assertEquals(finger.x, icon.x + 0.25f * 80f, 0.01f)
        assertEquals(finger.y, icon.y + 0.8f * 48f, 0.01f)
        assertEquals(finger.x, full.x + 0.25f * 1800f, 0.01f)
        assertEquals(finger.y, full.y + 0.8f * 640f, 0.01f)
    }

    @Test
    fun homeFollowsHorizontalScrollAndCentersNarrowPhotos() {
        val session = RevealSession(scene())
        val meadow = session.pieces.first()
        val scrolled = session.homeTopLeftInRoot(
            piece = meadow,
            viewportLeft = 10f,
            viewportTop = 20f,
            viewportWidthPx = 400f,
            imageWidthPx = 1000f,
            imageHeightPx = 500f,
            scrollPx = 80f,
        )
        assertEquals(10f - 80f + 100f, scrolled.x, 0.01f)
        assertEquals(20f + 200f, scrolled.y, 0.01f)

        val centered = session.homeTopLeftInRoot(
            piece = meadow,
            viewportLeft = 0f,
            viewportTop = 0f,
            viewportWidthPx = 400f,
            imageWidthPx = 200f,
            imageHeightPx = 100f,
            scrollPx = 50f,
        )
        // Scene is 1000x500, image 200x100, so scale is 0.2. Bbox left 100 → 20px.
        // Narrower than the viewport, so it is centered and scroll is ignored.
        assertEquals((400f - 200f) / 2f + 20f, centered.x, 0.01f)
        assertEquals(40f, centered.y, 0.01f)
    }

    private fun scene(): RevealScene = RevealScene(
        id = "mountain",
        title = "Mountain",
        width = 1000,
        height = 500,
        plateFile = "plate.webp",
        aliveFile = "alive.webp",
        pieces = listOf(
            RevealPiece(
                id = 2,
                order = 2,
                label = "river",
                file = "pieces/piece_02.webp",
                trayFile = "tray/piece_02.webp",
                bbox = LayerBBox(400, 100, 600, 250),
            ),
            RevealPiece(
                id = 1,
                order = 1,
                label = "meadow",
                file = "pieces/piece_01.webp",
                trayFile = "tray/piece_01.webp",
                bbox = LayerBBox(100, 200, 300, 400),
            ),
        ),
    )

    private class RecordingFeedback : RevealFeedback {
        val locked = mutableListOf<Int>()
        var aliveCount = 0
        override fun onCorrectRelease(pieceId: Int) {
            locked += pieceId
        }

        override fun onSceneAlive() {
            aliveCount++
        }
    }
}
