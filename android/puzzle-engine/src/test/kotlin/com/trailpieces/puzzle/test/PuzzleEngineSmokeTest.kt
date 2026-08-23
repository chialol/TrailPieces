package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.LockGroupService
import com.trailpieces.puzzle.service.ShuffleService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sanity checks — puzzle-engine test infra is wired up. */
class PuzzleEngineSmokeTest {

    @Test
    fun shuffleProducesUnsolvedBoard() {
        val manifest = PuzzleFixtures.miniManifest()
        val board = ShuffleService.shuffled(manifest, moves = 40)
        assertFalse(board.isSolved, "Shuffled mini puzzle should not be solved")
    }

    @Test
    fun correctlyAdjacentTilesFormLockGroup() {
        val manifest = PuzzleFixtures.miniManifest()
        val board = PuzzleFixtures.solvedBoard(manifest)
        val group = board.componentContaining(tileId = 0)
        assertTrue(group.contains(1), "Top row neighbors at home should lock")
        assertTrue(group.size >= 2)
    }

    @Test
    fun dragAndPushMovesRestingTile() {
        val manifest = PuzzleFixtures.miniManifest()
        // Swap tiles 0 and 2 so the board is not solved
        val board = PuzzleFixtures.boardWithPlacements(
            manifest,
            mapOf(
                GridPos(0, 0) to 2,
                GridPos(0, 1) to 1,
                GridPos(1, 0) to 0,
                GridPos(1, 1) to 3,
                GridPos(2, 0) to 4,
                GridPos(2, 1) to 5,
            ),
        )
        assertFalse(board.isSolved)
        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(0, 0)))

        // One cell down = 100px; past half-cell threshold
        engine.moveFinger(Vec2(0f, 60f), cellWidthPx = 100f, cellHeightPx = 100f)

        val drag = engine.drag
        assertNotNull(drag)
        assertTrue(
            drag.committedAnchor.row > 0 || drag.grid.tileAt(GridPos(0, 0)) != null,
            "Push down should fill the hole or advance committed anchor",
        )
    }

    @Test
    fun isolatedLocksDoNotMergeTiles() {
        val manifest = PuzzleFixtures.miniManifest()
        val scrambled = PuzzleFixtures.boardWithPlacements(
            manifest,
            mapOf(
                GridPos(0, 0) to 5,
                GridPos(2, 1) to 0,
            ),
        )
        val locks = LockGroupService.compute(scrambled.grid, manifest)
        assertTrue(locks.members(0, manifest.tiles.map { it.id }).size == 1)
        assertTrue(locks.members(5, manifest.tiles.map { it.id }).size == 1)
    }
}
