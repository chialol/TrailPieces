package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Principle 1: same-size CCs whose cells are each other's homes must swap
 * even when the landing is "home" (previously skipped for cutline).
 */
class CompletingSameSizeSwapTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "complete-swap",
        title = "Complete swap",
        cols = 2,
        rows = 12,
        puzzleWidth = 200,
        puzzleHeight = 1200,
        tiles = (0..23).map { id ->
            PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
        },
    )

    private fun almostSolvedWithSwap(a: GridPos, b: GridPos): PuzzleBoard {
        val placements = (0 until 12).flatMap { r ->
            (0..1).map { c ->
                val pos = GridPos(r, c)
                val homeId = r * 2 + c
                pos to homeId
            }
        }.toMap().toMutableMap()
        val idA = placements.getValue(a)
        val idB = placements.getValue(b)
        placements[a] = idB
        placements[b] = idA
        return PuzzleFixtures.playfield(manifest, rows = 12, placements = placements)
    }

    /**
     * Slots C (1,0) and J (4,1) hold each other's tiles — two singletons.
     * Swapping them solves the board.
     */
    @Test
    fun slotsCAndJ_swappedSingletons_swapSolves() {
        val board = almostSolvedWithSwap(GridPos(1, 0), GridPos(4, 1))
        assertEquals(setOf(9), board.componentContaining(9), "J at C must be loose")
        assertEquals(setOf(2), board.componentContaining(2), "C at J must be loose")

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(1, 0))) // tile J at slot C
        engine.moveFinger(Vec2(60f, 260f), cell, cell) // toward slot J
        val settled = engine.endDrag()
        assertTrue(settled.isSolved, "C↔J same-size swap must complete the puzzle")
    }

    /**
     * User dump: {BDF} at KMO homes, {KMO} at BDF homes, rest locked at home.
     */
    @Test
    fun bdfKmo_columnsSwapped_swapSolves() {
        val placements = mutableMapOf<GridPos, Int>()
        for (r in 0 until 12) {
            for (c in 0..1) {
                placements[GridPos(r, c)] = r * 2 + c
            }
        }
        // KMO (10,12,14) sit at B,D,F (0,1)/(1,1)/(2,1)
        placements[GridPos(0, 1)] = 10
        placements[GridPos(1, 1)] = 12
        placements[GridPos(2, 1)] = 14
        // BDF (1,3,5) sit at K,M,O (5,0)/(6,0)/(7,0)
        placements[GridPos(5, 0)] = 1
        placements[GridPos(6, 0)] = 3
        placements[GridPos(7, 0)] = 5

        val board = PuzzleFixtures.playfield(manifest, rows = 12, placements = placements)
        assertEquals(setOf(1, 3, 5), board.componentContaining(1))
        assertEquals(setOf(10, 12, 14), board.componentContaining(10))

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(5, 0))) // B in {BDF}
        engine.moveFinger(Vec2(20f, -160f), cell, cell) // toward KMO / home
        val settled = engine.endDrag()
        assertTrue(settled.isSolved, "BDF↔KMO same-size swap must complete the puzzle")
    }
}
