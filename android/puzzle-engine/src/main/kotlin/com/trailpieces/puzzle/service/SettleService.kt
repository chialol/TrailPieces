package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.Vec2

/**
 * Release = [ParkLifted] only. All layout motion happens mid-drag; finger-up
 * never rewrites resting tiles.
 */
object SettleService {

    @Suppress("UNUSED_PARAMETER")
    fun settle(
        session: DragSession,
        fingerDeltaPx: Vec2 = Vec2.Zero,
        cellWidthPx: Float = 1f,
        cellHeightPx: Float = 1f,
        originalBoard: PuzzleBoard? = null,
    ): PuzzleBoard = ParkLifted.apply(session)
}
