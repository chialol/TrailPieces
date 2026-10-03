package com.trailpieces.app.layers.v3

/**
 * Response to a correct release. The session calls this after a piece locks
 * and again when the last piece locks.
 *
 * Visual and haptic playback belongs in an implementation of this interface.
 * [SilentRevealFeedback] is the stand-in until that playback exists.
 * The color fade that wakes the whole photo is separate: it is the completion
 * state of the scene, not a flourish on this hook.
 */
interface RevealFeedback {
    fun onCorrectRelease(pieceId: Int) {}

    fun onSceneAlive() {}
}

object SilentRevealFeedback : RevealFeedback
