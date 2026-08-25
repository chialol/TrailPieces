package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.LockGroupService
import com.trailpieces.puzzle.service.PlacementService
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User trace: release-before-settle had a locked strip N–O–P–R–T–U–V beside
 * the lift hole; finger was (0,0). Settle must not peel that CC by re-placing
 * from the pre-drag board.
 */
class SettlePreservesMidDragLocksTest {

    private val cell = 100f

    private val manifest = PuzzleManifest(
        id = "settle-preserve-locks",
        title = "Settle preserve locks",
        cols = 2,
        rows = 12,
        puzzleWidth = 200,
        puzzleHeight = 1200,
        tiles = (0..23).map { id ->
            PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
        },
    )

    /** Home letters N,O,P,R,T,U,V — the strip from the user dump. */
    private val stripIds = setOf(13, 14, 15, 17, 19, 20, 21)

    /**
     * Mid-drag resting layout from the dump (hole at M's cell). Original board
     * is a scrambled version so tryPlaceAt(fresh, …) would rearrange if used.
     */
    private fun midDragBoard(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 12,
        placements = mapOf(
            GridPos(0, 0) to 1, GridPos(0, 1) to 3, // B D
            GridPos(1, 0) to 2, GridPos(1, 1) to 7, // C H
            GridPos(2, 0) to 4, GridPos(2, 1) to 9, // E J
            GridPos(3, 0) to 10, GridPos(3, 1) to 11, // K L
            GridPos(4, 0) to 12, GridPos(4, 1) to 13, // M N
            GridPos(5, 0) to 14, GridPos(5, 1) to 15, // O P
            GridPos(6, 0) to 6, GridPos(6, 1) to 17, // G R
            GridPos(7, 0) to 8, GridPos(7, 1) to 19, // I T
            GridPos(8, 0) to 20, GridPos(8, 1) to 21, // U V
            GridPos(9, 0) to 16, GridPos(9, 1) to 0, // Q A
            GridPos(10, 0) to 18, GridPos(10, 1) to 5, // S F
            GridPos(11, 0) to 22, GridPos(11, 1) to 23, // W X
        ),
    )

    /** Different resting geometry — preferential re-place from this would move the strip. */
    private fun scrambledOriginal(): PuzzleBoard = PuzzleFixtures.playfield(
        manifest,
        rows = 12,
        placements = mapOf(
            GridPos(0, 0) to 1, GridPos(0, 1) to 3,
            GridPos(1, 0) to 2, GridPos(1, 1) to 7,
            GridPos(2, 0) to 4, GridPos(2, 1) to 9,
            GridPos(3, 0) to 10, GridPos(3, 1) to 11,
            GridPos(4, 0) to 12, GridPos(4, 1) to 0, // A where N was
            GridPos(5, 0) to 16, GridPos(5, 1) to 5, // Q F
            GridPos(6, 0) to 6, GridPos(6, 1) to 18,
            GridPos(7, 0) to 8, GridPos(7, 1) to 22,
            GridPos(8, 0) to 23, GridPos(8, 1) to 13, // N alone
            GridPos(9, 0) to 14, GridPos(9, 1) to 15, // O P
            GridPos(10, 0) to 17, GridPos(10, 1) to 19, // R T
            GridPos(11, 0) to 20, GridPos(11, 1) to 21, // U V
        ),
    )

    @Test
    fun midDragStripIsOneLockComponent() {
        val board = midDragBoard()
        val group = board.componentContaining(13)
        assertTrue(stripIds.all { it in group }, "expected strip locked, got $group")
    }

    @Test
    fun zeroFingerSettle_mustNotPeelMidDragStrip() {
        val mid = midDragBoard()
        // Dump shows a singleton hole at M — M was not lock-joined to N at pointer-down.
        val session = mid.beginDrag(GridPos(4, 0), liftOverride = setOf(12))!!
        assertEquals(setOf(12), session.liftedTileIds)

        val midLocks = LockGroupService.compute(session.grid, manifest)
        assertTrue(
            stripIds.all { it in midLocks.members(13, manifest.tiles.map { it.id }) },
            "strip must stay locked on mid-drag resting grid",
        )

        val settled = PlacementService.settlePreferred(
            session = session,
            fingerDeltaPx = Vec2.Zero, // matches UI clearFingerDelta-before-endDrag
            cellWidthPx = cell,
            cellHeightPx = cell,
            originalBoard = scrambledOriginal(),
        )

        val after = settled.componentContaining(13)
        assertTrue(
            stripIds.all { it in after },
            "settle peeled lock strip: expected $stripIds in one CC, got $after",
        )
        assertEquals(GridPos(4, 0), settled.grid.slotOfOrNull(12))
    }
}
