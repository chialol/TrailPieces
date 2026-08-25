package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.model.step
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Placement policy layered on axis push:
 *
 * 1. **Same-size swap** — congruent CCs exchange places.
 * 2. **Empty-fit** — holes slide into empties ([PushService] early path).
 *    A **singleton** can also jump onto the nearest empty along the push axis
 *    or finger-aim onto an empty cell (the hole moves; other tiles stay).
 * 3. **In-place pack** — displaced occupants gravitate into vacated holes /
 *    persistent empties (same make-way as an axis push).
 * 4. **Lock-completing land** — cutline insert + column-major repack when a
 *    landing completes locks and in-place make-way cannot clear it.
 *
 * Preferential settle picks home / finger / committed so correct placements
 * win even when mid-drag pushes never reached them.
 */
object PlacementService {

    /** @see MidDragMotion.swapPartnerHoles */
    internal fun swapPartnerHoles(session: DragSession): Set<GridPos> =
        MidDragMotion.swapPartnerHoles(session)

    /**
     * Singleton lands on the finger-projected cell when that cell is empty.
     * @see MidDragMotion.tryFingerAimEmptyLandResult
     */
    fun tryFingerAimEmptyLand(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? = MidDragMotion.tryFingerAimEmptyLandResult(
        session, fingerDeltaPx, cellWidthPx, cellHeightPx,
    )

    fun tryFingerAimSameSizeSwap(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? = MidDragMotion.tryFingerAimSameSizeSwapResult(
        session, fingerDeltaPx, cellWidthPx, cellHeightPx,
    )

    fun tryCompletingHomeSameSizeSwap(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? = MidDragMotion.tryCompletingHomeSameSizeSwapResult(
        session, fingerDeltaPx, cellWidthPx, cellHeightPx,
    )

    fun tryNearestEmptyAlongAxis(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        direction: AxisDirection,
    ): PushResult? = AxisMotion.tryNearestEmptyAlongAxis(grid, holes, liftedTileIds, direction)

    /** Dominant-axis look-ahead: finger must cover (distance - 0.5) cells toward home. */
    internal fun fingerCoversHomeDominant(
        session: DragSession,
        home: GridPos,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Boolean {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return false
        if (!fingerDeltaPx.x.isFinite() || !fingerDeltaPx.y.isFinite()) return false
        val dRow = home.row - session.startAnchor.row
        val dCol = home.col - session.startAnchor.col
        if (dRow == 0 && dCol == 0) return false
        val fingerRow = fingerDeltaPx.y / cellHeightPx
        val fingerCol = fingerDeltaPx.x / cellWidthPx
        return if (abs(dRow) >= abs(dCol)) {
            if (dRow == 0) return false
            val signed = if (dRow > 0) fingerRow else -fingerRow
            signed > abs(dRow) - PUSH_THRESHOLD
        } else {
            if (dCol == 0) return false
            val signed = if (dCol > 0) fingerCol else -fingerCol
            signed > abs(dCol) - PUSH_THRESHOLD
        }
    }

    /** Nudge on the dominant axis toward [home] — enough to claim completing swap on settle. */
    internal fun fingerTowardHome(
        session: DragSession,
        home: GridPos,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Boolean {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return false
        if (!fingerDeltaPx.x.isFinite() || !fingerDeltaPx.y.isFinite()) return false
        val dRow = home.row - session.startAnchor.row
        val dCol = home.col - session.startAnchor.col
        if (dRow == 0 && dCol == 0) return false
        val fingerRow = fingerDeltaPx.y / cellHeightPx
        val fingerCol = fingerDeltaPx.x / cellWidthPx
        return if (abs(dRow) >= abs(dCol)) {
            if (dRow == 0) return false
            val signed = if (dRow > 0) fingerRow else -fingerRow
            signed > PUSH_THRESHOLD
        } else {
            if (dCol == 0) return false
            val signed = if (dCol > 0) fingerCol else -fingerCol
            signed > PUSH_THRESHOLD
        }
    }

    /** True when [fingerDeltaPx] has traveled far enough to commit landing at [targetAnchor]. */
    fun fingerCoversAnchor(
        session: DragSession,
        targetAnchor: GridPos,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Boolean {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return false
        if (!fingerDeltaPx.x.isFinite() || !fingerDeltaPx.y.isFinite()) return false
        val dRow = targetAnchor.row - session.startAnchor.row
        val dCol = targetAnchor.col - session.startAnchor.col
        if (dRow == 0 && dCol == 0) return false
        if (dRow > 0 && fingerDeltaPx.y <= (dRow - PUSH_THRESHOLD) * cellHeightPx) return false
        if (dRow < 0 && -fingerDeltaPx.y <= (-dRow - PUSH_THRESHOLD) * cellHeightPx) return false
        if (dCol > 0 && fingerDeltaPx.x <= (dCol - PUSH_THRESHOLD) * cellWidthPx) return false
        if (dCol < 0 && -fingerDeltaPx.x <= (-dCol - PUSH_THRESHOLD) * cellWidthPx) return false
        return true
    }

    private const val PUSH_THRESHOLD = 0.5f

    fun settlePreferred(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
        originalBoard: PuzzleBoard? = null,
    ): PuzzleBoard = SettleService.settle(session, fingerDeltaPx, cellWidthPx, cellHeightPx, originalBoard)

    /** True when landing cells are exactly one resting CC congruent to the lift. */
    internal fun isSameSizeLanding(session: DragSession, landing: Set<GridPos>): Boolean {
        val locks = session.rigidLocks()
        val allIds = session.manifest.tiles.map { it.id }
        val contactIds = landing.mapNotNull { pos ->
            session.grid.tileAt(pos)?.takeIf { it !in session.liftedTileIds }
        }.toSet()
        if (contactIds.isEmpty()) return false
        val seed = contactIds.first()
        val group = locks.members(seed)
            .filter { it !in session.liftedTileIds && session.grid.slotOfOrNull(it) != null }
            .toSet()
        if (group.size != session.liftedTileIds.size) return false
        if (!contactIds.all { it in group }) return false
        val groupSlots = group.mapNotNull { session.grid.slotOfOrNull(it) }.toSet()
        return groupSlots == landing && GridGeometry.translation(groupSlots, session.targetSlots) != null
    }

    internal fun targetAlongFingerPathForSettle(
        session: DragSession,
        target: GridPos,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Boolean {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return false
        if (!fingerDeltaPx.x.isFinite() || !fingerDeltaPx.y.isFinite()) return false
        val dCol = (fingerDeltaPx.x / cellWidthPx).roundToInt()
        val dRow = (fingerDeltaPx.y / cellHeightPx).roundToInt()
        val fingerRaw = session.startAnchor.offset(dRow, dCol)
        val toTargetRow = target.row - session.startAnchor.row
        val toTargetCol = target.col - session.startAnchor.col
        val toFingerRow = fingerRaw.row - session.startAnchor.row
        val toFingerCol = fingerRaw.col - session.startAnchor.col
        fun sameWay(a: Int, b: Int) =
            a == 0 || (b != 0 && (a > 0) == (b > 0))
        if (!sameWay(toTargetRow, toFingerRow) || !sameWay(toTargetCol, toFingerCol)) return false
        return kotlin.math.abs(toTargetRow) <= kotlin.math.abs(toFingerRow) + 1 &&
            kotlin.math.abs(toTargetCol) <= kotlin.math.abs(toFingerCol) + 1
    }

    /**
     * Anchor such that the lift's (0,0) offset sits on the finger-projected cell
     * (clamped to the playfield). Used to aim at another tile for same-size swap.
     */
    /** @see fingerTargetAnchor — exposed for mid-drag off-axis detection. */
    internal fun fingerTargetAnchorForSwap(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): GridPos? = fingerTargetAnchor(session, fingerDeltaPx, cellWidthPx, cellHeightPx)

    private fun fingerTargetAnchor(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): GridPos? {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return null
        if (!fingerDeltaPx.x.isFinite() || !fingerDeltaPx.y.isFinite()) return null
        val dCol = (fingerDeltaPx.x / cellWidthPx).roundToInt()
        val dRow = (fingerDeltaPx.y / cellHeightPx).roundToInt()
        val raw = session.startAnchor.offset(dRow, dCol)
        val maxRow = (session.grid.rows - 1).coerceAtLeast(0)
        val aim = GridPos(
            row = raw.row.coerceIn(0, maxRow),
            col = raw.col.coerceIn(0, session.grid.cols - 1),
        )
        // If shape has a non-zero min offset, convert aim cell → anchor.
        val minOff = session.shapeOffsets.values.minWith(compareBy({ it.row }, { it.col }))
        return aim.offset(-minOff.row, -minOff.col)
    }

    internal fun tryPlaceAtForSettle(session: DragSession, anchor: GridPos): PuzzleBoard? =
        ReleaseUpgrade.tryPlace(session, anchor)

    /**
     * Lock-completing land that joins a tile above the landing row in the same
     * column (e.g. H under A). Skips same-row-only lock joins that a swap can satisfy.
     */
    internal fun lockCompletingJoinsKeptAbove(
        session: DragSession,
        landing: Set<GridPos>,
    ): Boolean {
        if (!wouldCompleteLocks(session, landing)) return false
        val landRow = landing.minOf { it.row }
        val anchor = GridGeometry.landingAnchor(session.shapeOffsets, landing)
        var work = session.grid
        val needRows = landing.maxOf { it.row } + 1
        while (work.rows < needRows) work = work.insertRow(work.rows)

        val tall = SlotGrid.empty(work.cols, maxOf(work.rows, needRows)).withCells { cells ->
            for (r in 0 until work.rows) {
                for (c in 0 until work.cols) {
                    val pos = GridPos(r, c)
                    val id = work.tileAt(pos)
                    if (id != null && id !in session.liftedTileIds && pos !in landing) {
                        cells[r * work.cols + c] = id
                    }
                }
            }
            for ((tileId, offset) in session.shapeOffsets) {
                val pos = anchor.offset(offset.row, offset.col)
                cells[pos.index(work.cols)] = tileId
            }
        }
        val after = LockGroupService.compute(tall, session.manifest)
        val allIds = session.manifest.tiles.map { it.id }
        for (id in session.liftedTileIds) {
            val members = after.members(id, allIds)
            for (memberId in members) {
                if (memberId in session.liftedTileIds) continue
                val slot = tall.slotOfOrNull(memberId) ?: continue
                if (slot.row >= landRow) continue
                if (landing.any { it.col == slot.col && it.row > slot.row }) return true
            }
        }
        return false
    }

    /** True when swapping with the landing occupant sends every tile to its home. */
    internal fun sameSizeSwapCompletesHomes(
        session: DragSession,
        landing: Set<GridPos>,
    ): Boolean {
        if (!isSameSizeLanding(session, landing)) return false
        val holes = session.targetSlots
        val contactIds = landing.mapNotNull { pos ->
            session.grid.tileAt(pos)?.takeIf { it !in session.liftedTileIds }
        }
        if (contactIds.size != session.liftedTileIds.size) return false
        val shift = GridGeometry.translation(
            contactIds.map { session.grid.slotOf(it) }.toSet(),
            holes,
        ) ?: return false
        for (id in session.liftedTileIds) {
            if (session.manifest.tileOrNull(id)?.home !in landing) return false
        }
        for (id in contactIds) {
            val from = session.grid.slotOf(id)
            val to = from.offset(shift.first, shift.second)
            if (session.manifest.tileOrNull(id)?.home != to) return false
        }
        return true
    }

    /** Home cutline when a same-size swap would leave landing-row neighbors that must repack. */
    internal fun shouldCutlineHomeInsteadOfSwap(
        session: DragSession,
        landing: Set<GridPos>,
    ): Boolean {
        if (!isHomeLanding(session, landing)) return false
        val landRow = landing.minOf { it.row }
        val cutAfter = landing.maxOf { it.row }
        for (r in landRow..cutAfter) {
            for (c in 0 until session.grid.cols) {
                val pos = GridPos(r, c)
                if (pos in landing) continue
                if (session.grid.tileAt(pos) == null) continue
                if (!ReleaseUpgrade.isCutlineLandingRowKeeper(session, landing, landRow, pos, session.grid)) {
                    return true
                }
            }
        }
        return false
    }

    internal fun isHomeLanding(session: DragSession, landing: Set<GridPos>): Boolean {
        val anchor = GridGeometry.landingAnchor(session.shapeOffsets, landing)
        return session.liftedTileIds.all { id ->
            val home = session.manifest.tileOrNull(id)?.home ?: return@all false
            anchor.offset(
                session.shapeOffsets.getValue(id).row,
                session.shapeOffsets.getValue(id).col,
            ) == home
        }
    }

    internal fun wouldCompleteLocks(session: DragSession, landing: Set<GridPos>): Boolean {
        if (landing.any { it.col !in 0 until session.grid.cols || it.row < 0 }) return false
        val anchor = GridGeometry.landingAnchor(session.shapeOffsets, landing)
        var work = session.grid
        val needRows = landing.maxOf { it.row } + 1
        while (work.rows < needRows) work = work.insertRow(work.rows)

        val tall = SlotGrid.empty(work.cols, maxOf(work.rows, needRows)).withCells { cells ->
            for (r in 0 until work.rows) {
                for (c in 0 until work.cols) {
                    val pos = GridPos(r, c)
                    val id = work.tileAt(pos)
                    if (id != null && id !in session.liftedTileIds && pos !in landing) {
                        cells[r * work.cols + c] = id
                    }
                }
            }
            for ((tileId, offset) in session.shapeOffsets) {
                val pos = anchor.offset(offset.row, offset.col)
                cells[pos.index(work.cols)] = tileId
            }
        }
        val after = LockGroupService.compute(tall, session.manifest)
        for (id in session.liftedTileIds) {
            val members = after.members(id, session.manifest.tiles.map { it.id })
            if (members.any { it !in session.liftedTileIds }) return true
        }
        return false
    }

    internal fun scoreBoardForSettle(board: PuzzleBoard, session: DragSession): Int =
        scoreBoard(board, session)

    private fun scoreBoard(board: PuzzleBoard, session: DragSession): Int {
        var score = 0
        for (id in session.liftedTileIds) {
            val home = session.manifest.tileOrNull(id)?.home
            if (home != null && board.grid.slotOfOrNull(id) == home) score += 10
            score += board.componentContaining(id).size
        }
        return score
    }

    /**
     * Cost of a preferential placement that undoes mid-drag resting geometry:
     * kicking tiles off homes they already occupy, or peeling mid-drag CCs.
     * Skipped for intentional home/completing-swap lands (finger aims there).
     */
    internal fun midDragHomeKickPenaltyForSettle(placed: PuzzleBoard, session: DragSession): Int =
        midDragHomeKickPenalty(placed, session)

    private fun midDragHomeKickPenalty(placed: PuzzleBoard, session: DragSession): Int {
        var penalty = 0
        val holes = session.targetSlots
        for (id in session.manifest.tiles.map { it.id }) {
            if (id in session.liftedTileIds) continue
            val midSlot = session.grid.slotOfOrNull(id) ?: continue
            val home = session.manifest.tileOrNull(id)?.home ?: continue
            val afterSlot = placed.grid.slotOfOrNull(id)
            if (midSlot != home || afterSlot == home) continue
            // Only protect home-parks next to the lift footprint (empty-fit park).
            val nearLift = holes.any { hole ->
                kotlin.math.abs(hole.row - midSlot.row) + kotlin.math.abs(hole.col - midSlot.col) <= 1
            }
            if (nearLift) penalty += 55
        }
        return penalty
    }

    internal fun midDragPreservePenaltyForSettle(placed: PuzzleBoard, session: DragSession): Int =
        midDragPreservePenalty(placed, session)

    private fun midDragPreservePenalty(placed: PuzzleBoard, session: DragSession): Int {
        var penalty = midDragHomeKickPenalty(placed, session)
        val midLocks = LockGroupService.compute(session.grid, session.manifest)
        val allIds = session.manifest.tiles.map { it.id }
        for (id in allIds) {
            if (id in session.liftedTileIds) continue
            if (session.grid.slotOfOrNull(id) == null) continue
            val midSize = midLocks.members(id, allIds)
                .count { it !in session.liftedTileIds && session.grid.slotOfOrNull(it) != null }
            val afterSize = placed.componentContaining(id).size
            // Only count peel for multi-tile mid-drag CCs (same-size settle swaps
            // often reshuffle singletons harmlessly).
            if (midSize > 1 && afterSize < midSize) {
                penalty += (midSize - afterSize) * 15
            }
        }
        return penalty
    }

    internal fun homeAnchorForSettle(session: DragSession): GridPos? = homeAnchor(session)

    private fun homeAnchor(session: DragSession): GridPos? {
        val homes = session.liftedTileIds.associateWith { id ->
            session.manifest.tileOrNull(id)?.home ?: return null
        }
        val minHome = homes.values.minWith(compareBy({ it.row }, { it.col }))
        val minOff = session.shapeOffsets.values.minWith(compareBy({ it.row }, { it.col }))
        val anchor = minHome.offset(-minOff.row, -minOff.col)
        for ((id, offset) in session.shapeOffsets) {
            if (homes[id] != anchor.offset(offset.row, offset.col)) return null
        }
        if (anchor.row < 0 || anchor.col < 0) return null
        if (anchor.col >= session.manifest.cols) return null
        return anchor
    }

    internal fun fingerAnchorForSettle(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): GridPos? = fingerAnchor(session, fingerDeltaPx, cellWidthPx, cellHeightPx)

    private fun fingerAnchor(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): GridPos? {
        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return null
        if (!fingerDeltaPx.x.isFinite() || !fingerDeltaPx.y.isFinite()) return null
        val dCol = (fingerDeltaPx.x / cellWidthPx).roundToInt()
        val dRow = (fingerDeltaPx.y / cellHeightPx).roundToInt()
        val raw = session.startAnchor.offset(dRow, dCol)
        // Clamp onto the playfield (grown height) so overshoot still counts as intent.
        val maxRow = (session.grid.rows - 1).coerceAtLeast(0)
        val anchor = GridPos(
            row = raw.row.coerceIn(0, maxRow),
            col = raw.col.coerceIn(0, session.grid.cols - 1),
        )
        val landing = session.shapeOffsets.values.map { anchor.offset(it.row, it.col) }
        if (landing.any { it.col !in 0 until session.grid.cols || it.row < 0 }) return null
        // Allow landing rows to sit on the last playfield row even if shape is tall.
        if (landing.any { it.row > maxRow }) return null
        return anchor
    }
}

