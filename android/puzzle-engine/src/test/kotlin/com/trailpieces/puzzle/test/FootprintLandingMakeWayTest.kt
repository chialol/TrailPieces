package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * ```
 * A B
 * C D
 * E F
 * G H
 * ```
 * {A,C,D} lifted; {E,G} rigid; B,F,H loose.
 * Down tunnels through EG; H must make way into the vacated crook so ACD can park.
 */
class FootprintLandingMakeWayTest {

    /** Homes chosen so only {A,C,D} and {E,G} lock at the visual layout. */
    private val manifest = PuzzleManifest(
        id = "acd-eg",
        title = "ACD/EG",
        cols = 2,
        rows = 4,
        puzzleWidth = 200,
        puzzleHeight = 400,
        tiles = listOf(
            PuzzleTile(0, GridPos(0, 0), "a"),
            PuzzleTile(1, GridPos(2, 0), "b"),
            PuzzleTile(2, GridPos(1, 0), "c"),
            PuzzleTile(3, GridPos(1, 1), "d"),
            PuzzleTile(4, GridPos(2, 1), "e"),
            PuzzleTile(5, GridPos(0, 1), "f"),
            PuzzleTile(6, GridPos(3, 1), "g"),
            PuzzleTile(7, GridPos(0, 0), "h"),
        ),
    )

    private fun board() = PuzzleFixtures.playfield(
        manifest,
        rows = 4,
        placements = mapOf(
            GridPos(0, 0) to 0, GridPos(0, 1) to 1,
            GridPos(1, 0) to 2, GridPos(1, 1) to 3,
            GridPos(2, 0) to 4, GridPos(2, 1) to 5,
            GridPos(3, 0) to 6, GridPos(3, 1) to 7,
        ),
    )

    @Test
    fun acdDown_tunnelsEg_HMakesWayIntoVacatedCrook() {
        val board = board()
        assertEquals(setOf(0, 2, 3), board.componentContaining(0))
        assertEquals(setOf(4, 6), board.componentContaining(4))

        var session = board.beginDrag(GridPos(0, 0), liftOverride = setOf(0, 2, 3))!!
        val pushed = session.tryPush(AxisDirection.Down)
        assertNotNull(pushed, "Down push should tunnel and clear H")
        session = pushed

        // EG packed to top of col0; F into former D hole; H into crook (2,1).
        BoardAssert.assertTileAt(session.grid, GridPos(0, 0), 4) // E
        BoardAssert.assertTileAt(session.grid, GridPos(1, 0), 6) // G
        BoardAssert.assertTileAt(session.grid, GridPos(1, 1), 5) // F
        BoardAssert.assertTileAt(session.grid, GridPos(0, 1), 1) // B
        BoardAssert.assertTileAt(session.grid, GridPos(2, 1), 7) // H made way
        BoardAssert.assertEmpty(session.grid, GridPos(2, 0))
        BoardAssert.assertEmpty(session.grid, GridPos(3, 0))
        BoardAssert.assertEmpty(session.grid, GridPos(3, 1))

        // ACD footprint parks on the far-side L.
        assertEquals(
            setOf(GridPos(2, 0), GridPos(3, 0), GridPos(3, 1)),
            session.targetSlots,
        )
    }
}
