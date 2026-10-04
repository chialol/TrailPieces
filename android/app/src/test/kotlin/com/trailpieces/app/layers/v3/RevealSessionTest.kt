package com.trailpieces.app.layers.v3

import androidx.compose.ui.geometry.Offset
import com.trailpieces.app.layers.LayerBBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val imageTopLeft = Offset(0f, 100f)

        session.startDrag(1)
        assertEquals(
            PlaceOutcome.Locked,
            session.tryPlace(Offset(100f, 300f), imageTopLeft, imageWidthPx = 1000f, snapThresholdPx = 72f),
        )
        assertEquals(listOf(1), feedback.locked)
        assertEquals(0, feedback.aliveCount)
        assertEquals(2, session.current?.id)

        session.startDrag(2)
        assertEquals(
            PlaceOutcome.Missed,
            session.tryPlace(Offset(0f, 0f), imageTopLeft, imageWidthPx = 1000f, snapThresholdPx = 72f),
        )
        assertEquals(2, session.current?.id)
        assertNull(session.draggingId)

        session.startDrag(2)
        assertEquals(
            PlaceOutcome.Locked,
            session.tryPlace(Offset(420f, 220f), imageTopLeft, imageWidthPx = 1000f, snapThresholdPx = 72f),
        )
        assertTrue(session.isComplete)
        assertEquals(listOf(1, 2), feedback.locked)
        assertEquals(1, feedback.aliveCount)
        assertNull(session.current)
    }

    @Test
    fun homeFollowsPanAndDisplayScale() {
        val session = RevealSession(scene())
        val meadow = session.pieces.first()
        val panned = session.homeTopLeftInRoot(meadow, Offset(-300f, 50f), imageWidthPx = 2000f)
        assertEquals(-300f + 100f * 2f, panned.x, 0.01f)
        assertEquals(50f + 200f * 2f, panned.y, 0.01f)
    }

    @Test
    fun grabPointStaysUnderTheFingerWhenThePieceGrows() {
        val finger = Offset(400f, 900f)
        val fraction = Offset(0.25f, 0.8f)
        val icon = anchoredTopLeft(finger, fraction, widthPx = 80f, heightPx = 48f)
        val full = anchoredTopLeft(finger, fraction, widthPx = 1800f, heightPx = 640f)
        assertEquals(finger.x, icon.x + 0.25f * 80f, 0.01f)
        assertEquals(finger.y, icon.y + 0.8f * 48f, 0.01f)
        assertEquals(finger.x, full.x + 0.25f * 1800f, 0.01f)
        assertEquals(finger.y, full.y + 0.8f * 640f, 0.01f)
    }

    @Test
    fun landscapeFillsHeightAndPans() {
        val fit = PhotoFit.of(scene(width = 3000, height = 2000), viewportWidthPx = 1080f, viewportHeightPx = 2000f)
        assertEquals(2000f, fit.heightPx, 0.01f)
        assertEquals(3000f, fit.widthPx, 0.01f)
        assertTrue(fit.pans(1080f))
    }

    @Test
    fun portraitFitsWithoutPanning() {
        val wide = PhotoFit.of(scene(width = 1606, height = 1920), viewportWidthPx = 1080f, viewportHeightPx = 2000f)
        assertEquals(1080f, wide.widthPx, 0.01f)
        assertEquals(1080f * 1920f / 1606f, wide.heightPx, 0.5f)
        assertFalse(wide.pans(1080f))

        val tall = PhotoFit.of(scene(width = 1000, height = 3000), viewportWidthPx = 1080f, viewportHeightPx = 2000f)
        assertEquals(2000f, tall.heightPx, 0.5f)
        assertFalse(tall.pans(1080f))
    }

    private fun scene(width: Int = 1000, height: Int = 500): RevealScene = RevealScene(
        id = "mountain",
        title = "Mountain",
        width = width,
        height = height,
        plateFile = "plate.webp",
        aliveFile = "alive.webp",
        coverFile = "cover.webp",
        liftPad = 40,
        pieces = listOf(
            piece(id = 2, label = "river", bbox = LayerBBox(400, 100, 600, 250)),
            piece(id = 1, label = "meadow", bbox = LayerBBox(100, 200, 300, 400)),
        ),
    )

    private fun piece(id: Int, label: String, bbox: LayerBBox) = RevealPiece(
        id = id,
        order = id,
        label = label,
        cropFile = "crop/piece_0$id.webp",
        liftFile = "lift/piece_0$id.webp",
        iconFile = "icon/piece_0$id.webp",
        bbox = bbox,
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
