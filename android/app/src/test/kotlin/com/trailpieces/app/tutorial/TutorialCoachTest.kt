package com.trailpieces.app.tutorial

import com.trailpieces.app.layers.LayerBBox
import com.trailpieces.app.layers.v3.RevealPiece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialCoachTest {
    @Test
    fun panAdvancesOnlyAfterBothDirections() {
        val coach = TutorialCoach()
        coach.notePan(TutorialPanTravelPx)
        assertEquals(TutorialStep.Pan, coach.step)
        coach.notePan(-TutorialPanTravelPx)
        assertEquals(TutorialStep.Look, coach.step)
    }

    @Test
    fun aShortPanDoesNotAdvance() {
        val coach = TutorialCoach()
        coach.notePan(TutorialPanTravelPx - 1f)
        coach.notePan(-(TutorialPanTravelPx - 1f))
        assertEquals(TutorialStep.Pan, coach.step)
    }

    @Test
    fun aPhotoThatFitsSkipsThePan() {
        val coach = TutorialCoach()
        coach.skipPanWhenThePhotoFits()
        assertEquals(TutorialStep.Look, coach.step)
    }

    @Test
    fun puttingThePieceBackOpensTheDragStep() {
        val coach = TutorialCoach()
        coach.skipPanWhenThePhotoFits()
        coach.closePiece()
        assertEquals(TutorialStep.Look, coach.step)
        coach.openPiece()
        assertTrue(coach.looking)
        coach.closePiece()
        assertFalse(coach.looking)
        assertEquals(TutorialStep.Drag, coach.step)
    }

    @Test
    fun thePieceCanBePlacedOnlyOnTheDragStep() {
        val coach = TutorialCoach()
        coach.placePiece()
        assertEquals(TutorialStep.Pan, coach.step)
        coach.skipPanWhenThePhotoFits()
        coach.openPiece()
        coach.closePiece()
        coach.placePiece()
        assertEquals(TutorialStep.Done, coach.step)
    }

    @Test
    fun aDropLandsWhenTheCenterIsNearTheDestination() {
        val dest = TutorialRegion(0.2f, 0.1f, 0.6f, 0.4f)
        assertTrue(tutorialDropHits(dest.centerX, dest.centerY, dest))
        assertTrue(tutorialDropHits(dest.centerX + dest.width * 0.4f, dest.centerY, dest))
        assertFalse(tutorialDropHits(dest.centerX + dest.width, dest.centerY + dest.height, dest))
    }

    @Test
    fun theLessonUsesTheHighestCutAndItsHome() {
        val tall = piece(id = 14, top = 0, bottom = 1014, left = 0, right = 687)
        val cap = piece(id = 9, top = 0, bottom = 441, left = 1009, right = 1767)
        val lower = piece(id = 4, top = 992, bottom = 1639, left = 1684, right = 2499)
        val chosen = topmostTutorialPiece(listOf(tall, lower, cap))
        assertEquals(9, chosen.id)
        val home = tutorialHome(chosen, sceneWidth = 2880, sceneHeight = 1920)
        assertEquals(1009f / 2880f, home.left, 0.001f)
        assertEquals(0f, home.top, 0.001f)
        assertEquals(1767f / 2880f, home.right, 0.001f)
        assertEquals(441f / 1920f, home.bottom, 0.001f)
    }

    private fun piece(id: Int, top: Int, bottom: Int, left: Int, right: Int) = RevealPiece(
        id = id,
        order = id,
        label = null,
        cropFile = "crop/piece_$id.webp",
        liftFile = "lift/piece_$id.webp",
        iconFile = "icon/piece_$id.webp",
        bbox = LayerBBox(left, top, right, bottom),
    )
}
