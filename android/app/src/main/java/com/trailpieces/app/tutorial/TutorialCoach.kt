package com.trailpieces.app.tutorial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trailpieces.app.layers.v3.RevealPiece

/** How far the photo must travel each way, in pixels, before the pan step is done. */
const val TutorialPanTravelPx = 96f

enum class TutorialStep {
    Pan,
    Look,
    Drag,
    Solve,
    Done,
}

/**
 * First-run lesson: pan both ways, open the first piece and put it back, drag it
 * home, then place every remaining piece.
 */
class TutorialCoach {
    var step by mutableStateOf(TutorialStep.Pan)
        private set

    var looking by mutableStateOf(false)
        private set

    private var sawNegative = false
    private var sawPositive = false

    fun notePan(deltaFromStartPx: Float, thresholdPx: Float = TutorialPanTravelPx) {
        if (step != TutorialStep.Pan || thresholdPx <= 0f) return
        if (deltaFromStartPx <= -thresholdPx) sawNegative = true
        if (deltaFromStartPx >= thresholdPx) sawPositive = true
        if (sawNegative && sawPositive) step = TutorialStep.Look
    }

    /** A photo that already fits has nothing to pan. */
    fun skipPanWhenThePhotoFits() {
        if (step == TutorialStep.Pan) step = TutorialStep.Look
    }

    fun openPiece() {
        if (step == TutorialStep.Look) looking = true
    }

    fun closePiece() {
        if (step == TutorialStep.Look && looking) {
            looking = false
            step = TutorialStep.Drag
        }
    }

    /** The guided piece locked. The rest of the puzzle stays to be placed. */
    fun guidedPlaced(puzzleComplete: Boolean) {
        if (step != TutorialStep.Drag) return
        step = if (puzzleComplete) TutorialStep.Done else TutorialStep.Solve
    }

    fun puzzleComplete() {
        if (step == TutorialStep.Solve) step = TutorialStep.Done
    }
}

/** Rectangle in photo fractions, 0–1. */
data class TutorialRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Lowest strip order. That piece is the leftmost one, and the one the lesson points at. */
fun firstTutorialPiece(pieces: List<RevealPiece>): RevealPiece =
    pieces.minWith(compareBy(RevealPiece::order, RevealPiece::id))

/** That piece's home, in photo fractions. */
fun tutorialHome(piece: RevealPiece, sceneWidth: Int, sceneHeight: Int): TutorialRegion {
    val width = sceneWidth.toFloat().coerceAtLeast(1f)
    val height = sceneHeight.toFloat().coerceAtLeast(1f)
    return TutorialRegion(
        left = piece.bbox.left / width,
        top = piece.bbox.top / height,
        right = piece.bbox.right / width,
        bottom = piece.bbox.bottom / height,
    )
}
