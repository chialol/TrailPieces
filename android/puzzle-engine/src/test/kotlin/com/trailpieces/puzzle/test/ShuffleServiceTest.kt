package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.service.ShuffleService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShuffleServiceTest {

    private val manifest = PuzzleFixtures.miniManifest()

    @Test
    fun shuffledBoardIsUnsolved() {
        repeat(10) {
            val board = ShuffleService.shuffled(manifest, moves = 40)
            assertFalse(board.isSolved, "iteration $it")
        }
    }

    @Test
    fun shuffledBoardContainsEveryTileOnce() {
        val board = ShuffleService.shuffled(manifest, moves = 40)
        val ids = BoardAssert.occupancy(board.grid).values.toList()
        assertEquals(manifest.tiles.map { it.id }.sorted(), ids.sorted())
        assertTrue(board.grid.emptySlots().isEmpty())
    }

    @Test
    fun shuffledRecomputesLocksFromResult() {
        val board = ShuffleService.shuffled(manifest, moves = 80)
        // Locks must match a fresh compute — no stale isolated parents
        val recomputed = com.trailpieces.puzzle.service.LockGroupService.compute(board.grid, manifest)
        for (tile in manifest.tiles) {
            assertEquals(
                recomputed.members(tile.id, manifest.tiles.map { it.id }),
                board.componentContaining(tile.id),
                "tile ${tile.id}",
            )
        }
    }

    @Test
    fun zeroMovesStillEnsuresUnsolvedViaFallback() {
        // Even with 0 random pushes, the ensure-unsolved loop should scramble
        val board = ShuffleService.shuffled(manifest, moves = 0)
        assertFalse(board.isSolved)
    }
}
