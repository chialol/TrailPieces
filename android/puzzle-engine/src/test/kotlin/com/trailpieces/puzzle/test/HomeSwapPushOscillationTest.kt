package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dump regression: completing-home swap must not snipe sideways onto home while
 * the finger is still mostly down, then fight push-Left (haptic jitter loop).
 *
 * ```
 * V 1     V home is (2,1); U sits on that home
 * 2 3
 * 0 U
 * ```
 *
 * Drag V down col0 with tiny left — must not oscillate U↔V at the bottom.
 */
class HomeSwapPushOscillationTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "home-swap-osc",
        title = "Home swap oscillation",
        cols = 2,
        rows = 3,
        puzzleWidth = 200,
        puzzleHeight = 300,
        tiles = (0..5).map { id ->
            PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
        },
    )

    private fun board() = PuzzleFixtures.playfield(
        manifest,
        rows = 3,
        placements = mapOf(
            GridPos(0, 0) to 5, GridPos(0, 1) to 1, // V …
            GridPos(1, 0) to 2, GridPos(1, 1) to 3,
            GridPos(2, 0) to 0, GridPos(2, 1) to 4, // … U on V's home
        ),
    )

    @Test
    fun downPastHomeNeighbor_doesNotOscillateHomeSwapAndPushLeft() {
        val engine = DragEngine(manifest, board())
        assertTrue(engine.startDrag(GridPos(0, 0))) // V
        // Mostly down (~2.5 cells) with tiny left — dump proportions.
        engine.moveFinger(Vec2(-5f, 250f), cell, cell)

        val kinds = engine.dragTrace.snapshot().map { it.kind }
        val homeSwaps = kinds.count { it == "commit:home-swap" }
        val leftPushes = kinds.count { it == "commit:push-Left" }
        assertTrue(
            homeSwaps == 0 || leftPushes == 0,
            "home-swap and push-Left must not fight, got $kinds",
        )
        assertTrue(
            homeSwaps <= 1 && leftPushes <= 1,
            "no ping-pong commits, got $kinds",
        )
        // Sideways home without covering home col must not fire.
        assertEquals(
            0,
            homeSwaps,
            "sideways home-swap without finger covering home col: $kinds",
        )
    }

    @Test
    fun fingerCoversHome_stillCompletingSwapsMidDrag() {
        // Adjacent swap: V at U's home, U at V's home.
        val adjacent = PuzzleFixtures.playfield(
            manifest,
            rows = 3,
            placements = mapOf(
                GridPos(0, 0) to 0, GridPos(0, 1) to 1,
                GridPos(1, 0) to 2, GridPos(1, 1) to 3,
                GridPos(2, 0) to 5, GridPos(2, 1) to 4, // V U swapped
            ),
        )
        val engine = DragEngine(manifest, adjacent)
        assertTrue(engine.startDrag(GridPos(2, 0))) // V at U
        engine.moveFinger(Vec2(60f, 10f), cell, cell) // covers home (2,1)

        val mid = engine.drag!!
        assertEquals(GridPos(2, 1), mid.committedAnchor, "completing home swap still works")
        BoardAssert.assertTileAt(mid.grid, GridPos(2, 0), 4) // U back home
        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 1), 5)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 0), 4)
    }
}
