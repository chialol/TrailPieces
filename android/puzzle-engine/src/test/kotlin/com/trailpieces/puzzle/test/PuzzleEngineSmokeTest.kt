package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.service.ShuffleService
import kotlin.test.Test
import kotlin.test.assertFalse

/** Tiny wiring check — prefer the dedicated *Test classes for real coverage. */
class PuzzleEngineSmokeTest {

    @Test
    fun moduleWiresAndShuffleRuns() {
        val board = ShuffleService.shuffled(PuzzleFixtures.miniManifest(), moves = 20)
        assertFalse(board.isSolved)
    }
}
