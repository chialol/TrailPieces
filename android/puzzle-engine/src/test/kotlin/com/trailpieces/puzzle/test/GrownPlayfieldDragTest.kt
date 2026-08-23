package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.service.DragEngine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Playfield may grow taller than [com.trailpieces.puzzle.model.PuzzleManifest.rows]
 * (insert-row). Every occupied cell must remain draggable — including bottom
 * rows past the solved/manifest height.
 *
 * The reported "can't drag bottom rows" bug was UI hit-testing clamping to
 * manifest.rows; the engine already allows these drags.
 */
class GrownPlayfieldDragTest {

    private val manifest = PuzzleFixtures.miniManifest()

    @Test
    fun tileOnRowBeyondManifest_isDraggableViaBeginDragAndEngine() {
        val board = PuzzleFixtures.playfield(
            manifest,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 0,
                GridPos(0, 1) to 1,
                GridPos(1, 0) to 2,
                GridPos(1, 1) to 3,
                GridPos(3, 0) to 4,
                GridPos(3, 1) to 5,
            ),
        )
        assertTrue(board.rows > manifest.rows)

        assertNotNull(
            board.beginDrag(GridPos(3, 0), grouped = true),
            "beginDrag must accept tiles on grown playfield rows",
        )

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(3, 0)))
        engine.cancelDragSafely()

        val engine2 = DragEngine(manifest, board)
        assertTrue(engine2.startDrag(GridPos(3, 1)))
    }

    @Test
    fun tallerPlayfield_bottomRowTilesAreDraggable() {
        val grown = PuzzleFixtures.playfield(
            manifest,
            rows = 5,
            placements = mapOf(
                GridPos(0, 0) to 0,
                GridPos(0, 1) to 1,
                GridPos(1, 0) to 2,
                GridPos(1, 1) to 3,
                GridPos(4, 0) to 4,
                GridPos(4, 1) to 5,
            ),
        )
        assertTrue(grown.rows > manifest.rows)
        assertTrue(4 >= manifest.rows)
        assertNotNull(grown.beginDrag(GridPos(4, 0)))
        assertTrue(DragEngine(manifest, grown).startDrag(GridPos(4, 0)))
    }
}
