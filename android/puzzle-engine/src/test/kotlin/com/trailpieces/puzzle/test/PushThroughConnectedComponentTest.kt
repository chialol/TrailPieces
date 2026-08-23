package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.DragSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Expected behavior for pushing *through* resting connected components
 * (TDD — written first; implement after review).
 *
 * Slot map on the mini 2×3 board (before any growth):
 * ```
 * A B
 * C D
 * E F
 * ```
 *
 * ## General rule
 *
 * Prefer keeping the playfield size fixed:
 * - When a dragged piece pushes into a resting locked group, translate that
 *   group rigidly out of the way when landing cells can make way.
 *
 * When that is impossible (landing cells are locked with nowhere to go),
 * **grow the playfield by inserting a new empty row** (not a new column —
 * phones are tall). The dragged piece moves into the new row; existing
 * content shifts down; persistent empties may remain on the board.
 *
 * Whenever a playfield row is **entirely empty**, it is **collapsed** (removed)
 * so the board shrinks again. Collapse runs after a settle / endDrag.
 *
 * - Lock groups keep their relative shape.
 * - Still never peel a single member off a lock group.
 * - [PuzzleManifest.rows] is the solved puzzle height; [SlotGrid.rows] is the
 *   playfield height and may be larger.
 */
class PushThroughConnectedComponentTest {

    private val manifest = PuzzleFixtures.miniManifest()
    private val cell = 100f

    // =========================================================================
    // Case 1 — vertical (A, C); E pushes up
    // =========================================================================

    /**
     * Setup (tile id at each slot):
     * ```
     * A=0 B=5
     * C=2 D=1
     * E=3 F=4
     * ```
     * Locks: (A,C) = {0,2}. E is lone tile 3.
     *
     * Drag E up through the column, release.
     *
     * Expected:
     * ```
     * A=3 B=5     E is now top
     * C=0 D=1
     * E=2 F=4     (A,C) shifted down one; still locked
     * ```
     */
    @Test
    fun verticalPair_EPushesUp_shiftsPairDown_EBecomesTop() {
        val board = LetterSlots.board("052134")
        assertEquals(setOf(0, 2), board.componentContaining(0), "(A,C) locked")
        assertEquals(1, board.componentContaining(3).size, "E lone")

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(LetterSlots.E))
        engine.moveFinger(Vec2(0f, -220f), cell, cell)
        val settled = engine.endDrag()

        BoardAssert.assertOccupancy(
            settled.grid,
            mapOf(
                LetterSlots.A to 3,
                LetterSlots.B to 5,
                LetterSlots.C to 0,
                LetterSlots.D to 1,
                LetterSlots.E to 2,
                LetterSlots.F to 4,
            ),
        )
        assertEquals(
            setOf(0, 2),
            settled.componentContaining(0).intersect(setOf(0, 2)),
            "(A,C) tiles 0 and 2 must remain in the same lock group",
        )
        assertTrue(settled.componentContaining(0).contains(2))
    }

    /**
     * Tunnel through (A,C) lands E at A (2 cells up). Commit only when the finger
     * is within 0.5 cell of that landing — i.e. residual > 1.5 cells — not when
     * first crossing into C.
     */
    @Test
    fun verticalPair_EDoesNotShiftPairUntilFingerNearLandingAbove() {
        val board = LetterSlots.board("052134")
        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(LetterSlots.E))

        // Just past a single-cell threshold — must NOT shift (A,C) yet.
        engine.moveFinger(Vec2(0f, -60f), cell, cell)
        assertEquals(LetterSlots.E, engine.drag!!.committedAnchor)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.A, 0)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.C, 2)
        assertEquals(Vec2(0f, -60f), engine.fingerDeltaPx)

        // Still short of (2 - 0.5) cells toward A.
        engine.moveFinger(Vec2(0f, -90f), cell, cell) // total -150
        assertEquals(LetterSlots.E, engine.drag!!.committedAnchor)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.A, 0)

        // Past 1.5 cells → commit tunnel; (A,C) shifts down, E parks at A.
        engine.moveFinger(Vec2(0f, -1f), cell, cell) // total -151
        assertEquals(LetterSlots.A, engine.drag!!.committedAnchor)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.C, 0)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.E, 2)
        BoardAssert.assertEmpty(engine.drag!!.grid, LetterSlots.A)
        assertEquals(Vec2(0f, -151f), engine.fingerDeltaPx)
    }

    /**
     * After the tunnel commits, dragging back down without lifting must undo
     * the shift (same look-ahead: reverse jump is also 2 cells → need > 1.5).
     */
    @Test
    fun verticalPair_canReverseTunnelShiftWithoutLiftingFinger() {
        val board = LetterSlots.board("052134")
        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(LetterSlots.E))

        engine.moveFinger(Vec2(0f, -160f), cell, cell)
        assertEquals(LetterSlots.A, engine.drag!!.committedAnchor)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.C, 0)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.E, 2)

        // Finger at -160, committed at -200. Down residual = 40 — not enough for
        // a 2-cell reverse (needs > 150). Pair stays shifted.
        engine.moveFinger(Vec2(0f, 40f), cell, cell) // total -120
        assertEquals(LetterSlots.A, engine.drag!!.committedAnchor)

        // Down residual from committed: -10 - (-200) = 190 > 150 → undo tunnel.
        engine.moveFinger(Vec2(0f, 110f), cell, cell) // total -10
        assertEquals(LetterSlots.E, engine.drag!!.committedAnchor)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.A, 0)
        BoardAssert.assertTileAt(engine.drag!!.grid, LetterSlots.C, 2)
        BoardAssert.assertEmpty(engine.drag!!.grid, LetterSlots.E)
        assertEquals(Vec2(0f, -10f), engine.fingerDeltaPx)
    }

    /**
     * Same geometry via repeated [DragSession.tryPush]: after tunneling, E's
     * committed anchor is A and the resting pair sits on C+E.
     */
    @Test
    fun verticalPair_repeatedPushUp_tunnelsEToTop() {
        val board = LetterSlots.board("052134")
        var session = board.beginDrag(LetterSlots.E, grouped = true)!!
        assertEquals(setOf(3), session.liftedTileIds)

        session = pushUntilBlocked(session, AxisDirection.Up)

        assertEquals(LetterSlots.A, session.committedAnchor, "E commits at A")
        BoardAssert.assertTileAt(session.grid, LetterSlots.C, 0)
        BoardAssert.assertTileAt(session.grid, LetterSlots.E, 2)
        BoardAssert.assertEmpty(session.grid, LetterSlots.A)

        val settled = session.settle()
        BoardAssert.assertTileAt(settled.grid, LetterSlots.A, 3)
        assertTrue(settled.componentContaining(0).contains(2))
    }

    // =========================================================================
    // Case 2 — horizontal (A, B); C pushes up; D is a lone tile
    // =========================================================================

    /**
     * Setup:
     * ```
     * A=0 B=1     locked horizontal pair
     * C=5 D=4     both lone
     * E=2 F=3
     * ```
     * Board string: `015423`
     *
     * Drag C up into (A,B), release.
     *
     * Expected:
     * ```
     * A=5 B=4     C and D are the new top row
     * C=0 D=1     connected (A,B) on the second row
     * E=2 F=3
     * ```
     * D is not dragged — it is displaced up into B when (A,B) shifts down.
     */
    @Test
    fun horizontalPair_CPushesUp_shiftsPairDown_CDBecomeTopRow() {
        val board = LetterSlots.board("015423")
        assertEquals(setOf(0, 1), board.componentContaining(0), "(A,B) locked")
        assertEquals(1, board.componentContaining(5).size, "C lone")
        assertEquals(1, board.componentContaining(4).size, "D lone")

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(LetterSlots.C))
        engine.moveFinger(Vec2(0f, -120f), cell, cell)
        val settled = engine.endDrag()

        BoardAssert.assertOccupancy(
            settled.grid,
            mapOf(
                LetterSlots.A to 5,
                LetterSlots.B to 4,
                LetterSlots.C to 0,
                LetterSlots.D to 1,
                LetterSlots.E to 2,
                LetterSlots.F to 3,
            ),
        )
        assertTrue(settled.componentContaining(0).contains(1), "(A,B) still locked on row 1")
        assertFalse(settled.componentContaining(5).contains(4))
    }

    /**
     * One Up push from C: (A,B) → row 1, D → B, hole for C at A.
     */
    @Test
    fun horizontalPair_onePushUp_movesABDownAndDUp() {
        val board = LetterSlots.board("015423")
        val session = board.beginDrag(LetterSlots.C, grouped = true)!!

        val pushed = session.tryPush(AxisDirection.Up)
        assertNotNull(
            pushed,
            "Push Up from C into (A,B) must succeed when D is a lone tile that can move up",
        )
        assertEquals(LetterSlots.A, pushed.committedAnchor)

        BoardAssert.assertTileAt(pushed.grid, LetterSlots.C, 0)
        BoardAssert.assertTileAt(pushed.grid, LetterSlots.D, 1)
        BoardAssert.assertTileAt(pushed.grid, LetterSlots.B, 4)
        BoardAssert.assertEmpty(pushed.grid, LetterSlots.A)

        val settled = pushed.settle()
        BoardAssert.assertTileAt(settled.grid, LetterSlots.A, 5)
        BoardAssert.assertTileAt(settled.grid, LetterSlots.B, 4)
        assertTrue(settled.componentContaining(0).contains(1))
    }

    // =========================================================================
    // Case 3 — cannot make way in-place → insert a new row above
    // =========================================================================

    /**
     * (A,B) locked horizontally; (D,F) locked vertically so D cannot move up:
     * ```
     * A=0 B=1
     * C=5 D=2
     * E=3 F=4
     * ```
     * Board string: `015234`
     *
     * Action: drag C up. In-place shift of (A,B) down is impossible without
     * peeling (D,F). Instead the playfield grows:
     *
     * 1. Insert a new empty row above A,B (everything shifts down one).
     * 2. Move C into the new top-left cell; top-right stays empty.
     * 3. C's old cell becomes empty.
     * 4. (A,B) and (D,F) keep their locks, just one row lower.
     *
     * Expected playfield (2×4 — one extra row, still 2 columns):
     * ```
     * row0:  C=5   EMPTY
     * row1:  A=0   B=1      ← former top
     * row2:  EMPTY D=2      ← hole where C was
     * row3:  E=3   F=4      ← (D,F) still locked
     * ```
     * Two empties sit on different rows/columns — the inserted empty row was
     * “split” by parking C in one of its cells.
     */
    @Test
    fun horizontalPair_insertRowAbove_whenDCannotMakeWay() {
        val board = LetterSlots.board("015234")
        assertEquals(3, board.rows)
        assertEquals(setOf(0, 1), board.componentContaining(0), "(A,B) locked")
        assertEquals(setOf(2, 4), board.componentContaining(2), "(D,F) locked")
        assertEquals(1, board.componentContaining(5).size, "C lone")

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(LetterSlots.C))
        engine.moveFinger(Vec2(0f, -120f), cell, cell)
        val settled = engine.endDrag()

        assertEquals(4, settled.rows, "Playfield grows by one row; cols stay 2")
        assertEquals(2, settled.cols)
        assertEquals(3, manifest.rows, "Manifest/solved height is unchanged")

        BoardAssert.assertOccupancy(
            settled.grid,
            mapOf(
                GridPos(0, 0) to 5, // C
                // (0,1) empty
                GridPos(1, 0) to 0, // A
                GridPos(1, 1) to 1, // B
                // (2,0) empty — former C
                GridPos(2, 1) to 2, // D
                GridPos(3, 0) to 3, // E
                GridPos(3, 1) to 4, // F
            ),
        )
        assertEquals(
            setOf(GridPos(0, 1), GridPos(2, 0)),
            settled.grid.emptySlots(),
            "Two empties on different rows/cols after splitting the new row",
        )
        assertEquals(setOf(0, 1), settled.componentContaining(0), "(A,B) still locked")
        assertEquals(setOf(2, 4), settled.componentContaining(2), "(D,F) still locked")
    }

    /**
     * Session-level: the Up push that cannot resolve in-place must grow the
     * grid and leave C committed on the new top row.
     */
    @Test
    fun horizontalPair_tryPushUp_insertsRowAndParksCOnNewTop() {
        val board = LetterSlots.board("015234")
        val session = board.beginDrag(LetterSlots.C, grouped = true)!!
        assertEquals(3, session.grid.rows)

        val pushed = session.tryPush(AxisDirection.Up)
        assertNotNull(pushed, "Must grow+push instead of blocking")
        assertEquals(4, pushed.grid.rows)
        assertEquals(GridPos(0, 0), pushed.committedAnchor)

        BoardAssert.assertTileAt(pushed.grid, GridPos(1, 0), 0)
        BoardAssert.assertTileAt(pushed.grid, GridPos(1, 1), 1)
        BoardAssert.assertTileAt(pushed.grid, GridPos(2, 1), 2)
        BoardAssert.assertTileAt(pushed.grid, GridPos(3, 1), 4)
        BoardAssert.assertEmpty(pushed.grid, GridPos(0, 1))

        val settled = pushed.settle()
        BoardAssert.assertTileAt(settled.grid, GridPos(0, 0), 5)
        BoardAssert.assertEmpty(settled.grid, GridPos(0, 1))
        BoardAssert.assertEmpty(settled.grid, GridPos(2, 0))
    }

    // =========================================================================
    // Case 4 — collapse a fully empty row
    // =========================================================================

    /**
     * Same grown layout as case 3, but D and F are **not** connected, so D can
     * move alone:
     * ```
     * row0:  C=5   EMPTY
     * row1:  A=0   B=1
     * row2:  EMPTY D=4      ← D lone (not locked to B above or F below)
     * row3:  E=3   F=2
     * ```
     *
     * Drag D up into the empty at (0,1). After that move:
     * ```
     * row0:  C=5   D=4
     * row1:  A=0   B=1
     * row2:  EMPTY EMPTY    ← entire row empty
     * row3:  E=3   F=2
     * ```
     *
     * That empty row must **collapse**, leaving a 2×3 playfield:
     * ```
     * row0:  C=5   D=4
     * row1:  A=0   B=1
     * row2:  E=3   F=2
     * ```
     */
    @Test
    fun grownBoard_dragDIntoTopEmpty_collapsesFullyEmptyRow() {
        val board = PuzzleFixtures.playfield(
            manifest,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 5,
                GridPos(1, 0) to 0,
                GridPos(1, 1) to 1,
                GridPos(2, 1) to 4, // D
                GridPos(3, 0) to 3,
                GridPos(3, 1) to 2, // F — not locked to D
            ),
        )
        assertEquals(4, board.rows)
        assertEquals(setOf(0, 1), board.componentContaining(0), "(A,B) locked")
        assertEquals(1, board.componentContaining(4).size, "D lone")
        assertEquals(1, board.componentContaining(2).size, "F lone")
        assertEquals(
            setOf(GridPos(0, 1), GridPos(2, 0)),
            board.grid.emptySlots(),
        )

        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(2, 1))) // D
        // Travel up to the top empty (0,1): two cells up from (2,1)
        engine.moveFinger(Vec2(0f, -220f), cell, cell)
        val settled = engine.endDrag()

        assertEquals(3, settled.rows, "Fully empty row collapses after settle")
        assertEquals(2, settled.cols)
        BoardAssert.assertOccupancy(
            settled.grid,
            mapOf(
                GridPos(0, 0) to 5,
                GridPos(0, 1) to 4,
                GridPos(1, 0) to 0,
                GridPos(1, 1) to 1,
                GridPos(2, 0) to 3,
                GridPos(2, 1) to 2,
            ),
        )
        assertTrue(settled.grid.emptySlots().isEmpty(), "No empties after collapse")
        assertEquals(setOf(0, 1), settled.componentContaining(0), "(A,B) still locked")
    }

    /**
     * Intermediate state after D has filled the top empty but before collapse
     * — documents the “two empties in row 2” trigger.
     *
     * ```
     * row0:  C=5   D=4
     * row1:  A=0   B=1
     * row2:  EMPTY EMPTY
     * row3:  E=3   F=2
     * ```
     *
     * Settle/endDrag must drop the empty row.
     */
    @Test
    fun fullyEmptyRow_collapsesOnSettle() {
        val board = PuzzleFixtures.playfield(
            manifest,
            rows = 4,
            placements = mapOf(
                GridPos(0, 0) to 5,
                GridPos(0, 1) to 4, // D already in the former top empty
                GridPos(1, 0) to 0,
                GridPos(1, 1) to 1,
                // row2 fully empty
                GridPos(3, 0) to 3,
                GridPos(3, 1) to 2,
            ),
        )
        assertEquals(4, board.rows)
        assertEquals(
            setOf(GridPos(2, 0), GridPos(2, 1)),
            board.grid.emptySlots(),
            "Row 2 is entirely empty — collapse trigger",
        )

        // Nudge a tile and release so settle/collapse runs (lift E and drop in place).
        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(3, 0)))
        engine.moveFinger(Vec2(0f, 10f), cell, cell) // below threshold — no push
        val settled = engine.endDrag()

        assertEquals(3, settled.rows)
        BoardAssert.assertOccupancy(
            settled.grid,
            mapOf(
                GridPos(0, 0) to 5,
                GridPos(0, 1) to 4,
                GridPos(1, 0) to 0,
                GridPos(1, 1) to 1,
                GridPos(2, 0) to 3,
                GridPos(2, 1) to 2,
            ),
        )
    }

    // =========================================================================
    // Case 5 — rigid group lands on a persistent empty (make-way into empty)
    // =========================================================================

    /**
     * Playfield (2×4), letters for discussion:
     * ```
     * ∅₁  B=5
     * C=2  D=3     ← {C,D,E} locked L (homes of tiles 2,3,4)
     * E=4  ∅₂
     * G=0  ∅
     * ```
     *
     * Drag G up into E. The L must shift down as a rigid body: E→G's hole,
     * C→E, **D→∅₂**. ∅₂ is empty, so that landing pushes nothing — the shift
     * must succeed (not block / not insert-row).
     *
     * After the push, G's hole tunnels to the nearest empty along Up (former C).
     */
    @Test
    fun rigidL_GPushesUp_shiftsGroupIntoEmpty2() {
        val board = PuzzleFixtures.playfield(
            manifest,
            rows = 4,
            placements = mapOf(
                GridPos(0, 1) to 5, // B
                GridPos(1, 0) to 2, // C
                GridPos(1, 1) to 3, // D
                GridPos(2, 0) to 4, // E
                // (2,1) empty2
                GridPos(3, 0) to 0, // G
            ),
        )
        assertEquals(setOf(2, 3, 4), board.componentContaining(2), "(C,D,E) locked")
        assertEquals(1, board.componentContaining(0).size, "G lone")
        assertEquals(1, board.componentContaining(5).size, "B lone")

        val session = board.beginDrag(GridPos(3, 0), grouped = true)!!
        assertEquals(setOf(0), session.liftedTileIds)

        val pushed = session.tryPush(AxisDirection.Up)
        assertNotNull(
            pushed,
            "D lands on empty2 — rigid shift into empty must succeed",
        )

        // L translated down one; D occupies former empty2
        BoardAssert.assertTileAt(pushed.grid, GridPos(2, 0), 2) // C
        BoardAssert.assertTileAt(pushed.grid, GridPos(2, 1), 3) // D in empty2
        BoardAssert.assertTileAt(pushed.grid, GridPos(3, 0), 4) // E
        BoardAssert.assertTileAt(pushed.grid, GridPos(0, 1), 5) // B untouched
        BoardAssert.assertEmpty(pushed.grid, GridPos(1, 0)) // former C
        BoardAssert.assertEmpty(pushed.grid, GridPos(1, 1)) // former D

        // G parks at nearest empty along Up (former C), not stuck under the L
        assertEquals(GridPos(1, 0), pushed.committedAnchor)
        BoardAssert.assertEmpty(pushed.grid, GridPos(1, 0))

        val settled = pushed.settle()
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 0) // G
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 1), 3) // D stayed in empty2
        // G may join the L by geometry after settle — require the original L stays intact.
        assertTrue(
            settled.componentContaining(2).containsAll(setOf(2, 3, 4)),
            "L members must remain in one lock group",
        )
        BoardAssert.assertSameRelativeShape(
            board.grid,
            settled.grid,
            setOf(2, 3, 4),
        )
    }

    /**
     * Same geometry through [DragEngine]: look-ahead jump is 2 cells (G at row3
     * → commit at row1), so finger must pass 1.5 cells up before the shift.
     */
    @Test
    fun rigidL_GDragUp_commitsShiftOnceFingerNearLanding() {
        val board = PuzzleFixtures.playfield(
            manifest,
            rows = 4,
            placements = mapOf(
                GridPos(0, 1) to 5,
                GridPos(1, 0) to 2,
                GridPos(1, 1) to 3,
                GridPos(2, 0) to 4,
                GridPos(3, 0) to 0,
            ),
        )
        val engine = DragEngine(manifest, board)
        assertTrue(engine.startDrag(GridPos(3, 0)))

        engine.moveFinger(Vec2(0f, -60f), cell, cell)
        assertEquals(GridPos(3, 0), engine.drag!!.committedAnchor, "Too early for 2-cell jump")

        engine.moveFinger(Vec2(0f, -100f), cell, cell) // total -160 > 1.5 cells
        assertEquals(GridPos(1, 0), engine.drag!!.committedAnchor)
        BoardAssert.assertTileAt(engine.drag!!.grid, GridPos(2, 1), 3)

        val settled = engine.endDrag()
        BoardAssert.assertTileAt(settled.grid, GridPos(1, 0), 0)
        BoardAssert.assertTileAt(settled.grid, GridPos(2, 1), 3)
    }

    // -------------------------------------------------------------------------

    private fun pushUntilBlocked(start: DragSession, direction: AxisDirection): DragSession {
        var session = start
        repeat(manifest.rows + manifest.cols + 4) {
            session = session.tryPush(direction) ?: return session
        }
        return session
    }
}
