package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Same-size CCs on each other's homes swap mid-drag when the finger covers home.
 * Release only parks — no further layout change.
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

    @Test
    fun slotsCAndJ_swappedSingletons_swapMidDrag_releaseParks() {
        val board = almostSolvedWithSwap(GridPos(1, 0), GridPos(4, 1))
        assertEquals(setOf(9), board.componentContaining(9), "J at C must be loose")
        assertEquals(setOf(2), board.componentContaining(2), "C at J must be loose")

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(1, 0))) // tile J at slot C
        engine.moveFinger(Vec2(60f, 260f), cell, cell) // toward slot J

        val mid = engine.drag!!
        assertEquals(GridPos(4, 1), mid.committedAnchor, "mid-drag: J footprint at home J")
        assertEquals(GridPos(1, 0), mid.grid.slotOfOrNull(2), "mid-drag: C already on home C")

        val settled = engine.endDrag()
        assertTrue(settled.isSolved, "release parks J; board already swapped mid-drag")
    }

    /** Mid-drag completing-home swap for multi-tile BDF↔KMO not wired yet. */
    @Ignore("Needs mid-drag completing swap for multi-tile column — not a release upgrade")
    @Test
    fun bdfKmo_columnsSwapped_swapMidDrag_releaseParks() {
        val placements = mutableMapOf<GridPos, Int>()
        for (r in 0 until 12) {
            for (c in 0..1) {
                placements[GridPos(r, c)] = r * 2 + c
            }
        }
        placements[GridPos(0, 1)] = 10
        placements[GridPos(1, 1)] = 12
        placements[GridPos(2, 1)] = 14
        placements[GridPos(5, 0)] = 1
        placements[GridPos(6, 0)] = 3
        placements[GridPos(7, 0)] = 5

        val board = PuzzleFixtures.playfield(manifest, rows = 12, placements = placements)
        assertEquals(setOf(1, 3, 5), board.componentContaining(1))
        assertEquals(setOf(10, 12, 14), board.componentContaining(10))

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(5, 0))) // B in {BDF}
        engine.moveFinger(Vec2(20f, -160f), cell, cell) // toward KMO / home

        val mid = engine.drag!!
        assertEquals(
            setOf(GridPos(0, 1), GridPos(1, 1), GridPos(2, 1)),
            mid.targetSlots,
            "mid-drag: BDF footprint already on KMO homes",
        )

        val settled = engine.endDrag()
        assertTrue(settled.isSolved, "release parks; swap already done mid-drag")
    }
}
