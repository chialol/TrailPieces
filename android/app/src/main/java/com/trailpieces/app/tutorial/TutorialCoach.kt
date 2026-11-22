package com.trailpieces.app.tutorial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trailpieces.app.layers.v3.RevealPiece
import kotlin.math.hypot

/** How far the photo must travel each way, in pixels, before the pan step is done. */
const val TutorialPanTravelPx = 96f

enum class TutorialStep {
    Pan,
    Look,
    Drag,
    Done,
}

/**
 * First-run lesson: pan both ways, open the piece and put it back, then drag it
 * onto its place on the photo.
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

    fun placePiece() {
        if (step == TutorialStep.Drag) step = TutorialStep.Done
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

/** The cut that sits highest on the photo. That is the piece the lesson uses. */
fun topmostTutorialPiece(pieces: List<RevealPiece>): RevealPiece =
    pieces.minWith(compareBy({ it.bbox.top }, { it.bbox.top + it.bbox.bottom }, { it.id }))

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

/** True when the dragged piece's center is close to the destination, in piece-sized units. */
fun tutorialDropHits(
    centerX: Float,
    centerY: Float,
    destination: TutorialRegion,
    slop: Float = 0.6f,
): Boolean {
    val dx = (centerX - destination.centerX) / destination.width.coerceAtLeast(0.01f)
    val dy = (centerY - destination.centerY) / destination.height.coerceAtLeast(0.01f)
    return hypot(dx, dy) <= slop
}
