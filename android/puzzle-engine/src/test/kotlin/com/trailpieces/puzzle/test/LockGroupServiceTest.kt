package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.service.LockGroupService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockGroupServiceTest {

    private val manifest = PuzzleFixtures.miniManifest()
    private val allIds get() = manifest.tiles.map { it.id }

    @Test
    fun solvedBoardFormsOneComponent() {
        val board = PuzzleFixtures.solvedBoard(manifest)
        val group = board.componentContaining(0)
        assertEquals(allIds.toSet(), group)
    }

    @Test
    fun correctlyAdjacentNeighborsLock() {
        val board = PuzzleFixtures.solvedBoard(manifest)
        assertTrue(board.componentContaining(0).contains(1))
        assertTrue(board.componentContaining(0).contains(2))
    }

    @Test
    fun scrambledNeighborsDoNotLock() {
        val board = PuzzleFixtures.boardFromRowMajor(
            manifest,
            listOf(5, 4, 3, 2, 1, 0),
        )
        for (id in allIds) {
            assertEquals(1, board.componentContaining(id).size, "tile $id")
        }
    }

    @Test
    fun partialCorrectStripLocksTransitively() {
        val board = PuzzleFixtures.lockedLeftColumnBoard(manifest)
        assertEquals(setOf(0, 2, 4), board.componentContaining(0))
        assertEquals(setOf(0, 2, 4), board.componentContaining(4))
        assertEquals(1, board.componentContaining(5).size)
        // 1 and 3 sit at correct relative offset in col 1
        assertEquals(setOf(1, 3), board.componentContaining(1))
    }

    @Test
    fun horizontalPairLocksWithoutPullingScrambledColumn() {
        val board = PuzzleFixtures.lockedHorizontalPairOnRow0(manifest)
        assertEquals(setOf(0, 1), board.componentContaining(0))
        assertEquals(setOf(2, 4), board.componentContaining(2))
        assertEquals(setOf(3, 5), board.componentContaining(3))
    }

    @Test
    fun lTriominoLocksAsOneComponent() {
        val board = PuzzleFixtures.lockedLTriominoBoard(manifest)
        assertEquals(setOf(0, 2, 3), board.componentContaining(0))
        assertEquals(setOf(0, 2, 3), board.componentContaining(3))
        assertEquals(1, board.componentContaining(5).size)
    }

    @Test
    fun isolatedNeverMerges() {
        val locks = LockGroupService.isolated(manifest)
        for (id in allIds) {
            assertEquals(setOf(id), locks.members(id, allIds))
        }
    }

    @Test
    fun touchingButWrongRelativeOffsetDoesNotLock() {
        // Tile 0 at home; tile 1 also in row 0 but tile 1 belongs at (0,1) —
        // place tile 5 next to 0 instead (wrong home offset).
        val board = PuzzleFixtures.boardFromRowMajor(
            manifest,
            listOf(0, 5, 1, 2, 3, 4),
        )
        assertEquals(1, board.componentContaining(0).size)
        assertEquals(1, board.componentContaining(5).size)
    }

    @Test
    fun outOfRangeTileIdDegradesSafely() {
        val locks = LockGroupService.compute(
            PuzzleFixtures.solvedBoard(manifest).grid,
            manifest,
        )
        assertEquals(setOf(99), locks.members(99, listOf(99)))
        locks.union(99, 0) // no-op, must not throw
        assertEquals(0, locks.find(0))
    }
}
