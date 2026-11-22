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
    fun placingTheGuidedPieceLeavesTheRestOfThePuzzle() {
        val coach = TutorialCoach()
        coach.guidedPlaced(puzzleComplete = false)
        assertEquals(TutorialStep.Pan, coach.step)
        coach.skipPanWhenThePhotoFits()
        coach.openPiece()
        coach.closePiece()
        coach.guidedPlaced(puzzleComplete = false)
        assertEquals(TutorialStep.Solve, coach.step)
        coach.puzzleComplete()
        assertEquals(TutorialStep.Done, coach.step)
    }

    @Test
    fun theLastPieceEndsTheLesson() {
        val coach = TutorialCoach()
        coach.skipPanWhenThePhotoFits()
        coach.openPiece()
        coach.closePiece()
        coach.guidedPlaced(puzzleComplete = true)
        assertEquals(TutorialStep.Done, coach.step)
    }

    @Test
    fun theLessonUsesTheFirstPieceAndItsHome() {
        val later = piece(id = 9, order = 9, top = 0, bottom = 441, left = 1009, right = 1767)
        val first = piece(id = 1, order = 1, top = 0, bottom = 703, left = 2317, right = 2880)
        val chosen = firstTutorialPiece(listOf(later, first))
        assertEquals(1, chosen.id)
        val home = tutorialHome(chosen, sceneWidth = 2880, sceneHeight = 1920)
        assertEquals(2317f / 2880f, home.left, 0.001f)
        assertEquals(0f, home.top, 0.001f)
        assertEquals(1f, home.right, 0.001f)
        assertEquals(703f / 1920f, home.bottom, 0.001f)
    }

    private fun piece(id: Int, order: Int = id, top: Int, bottom: Int, left: Int, right: Int) = RevealPiece(
        id = id,
        order = order,
        label = null,
        cropFile = "crop/piece_$id.webp",
        liftFile = "lift/piece_$id.webp",
        iconFile = "icon/piece_$id.webp",
        bbox = LayerBBox(left, top, right, bottom),
    )
}
