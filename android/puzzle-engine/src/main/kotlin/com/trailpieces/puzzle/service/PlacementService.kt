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

    /**
     * If resting tiles on the +1 step form one CC congruent to [holes], exchange:
     * that CC moves onto [holes]; footprint advances onto the CC's former slots.
     */
    fun trySameSizeSwap(
        grid: SlotGrid,
        holes: Set<GridPos>,
        stepped: Set<GridPos>,
        liftedTileIds: Set<Int>,
        locks: LockGroupService,
        allTileIds: Iterable<Int>,
    ): PushResult? {
        if (holes.isEmpty() || stepped.size != holes.size) return null
        if (stepped.any { !grid.inBounds(it) }) return null

        val contactIds = stepped.mapNotNull { pos ->
            grid.tileAt(pos)?.takeIf { it !in liftedTileIds }
        }.toSet()
        if (contactIds.isEmpty()) return null

        // All contact tiles must belong to one lock group (not a union of singles).
        val seed = contactIds.first()
        val group = locks.members(seed, allTileIds)
            .filter { it !in liftedTileIds && grid.slotOfOrNull(it) != null }
            .toSet()
        if (group.size != holes.size) return null
        if (!contactIds.all { it in group }) return null
        // Group must not spill past the stepped footprint.
        val groupSlots = group.map { grid.slotOf(it) }.toSet()
        if (groupSlots != stepped) return null
        val shift = translation(holes, groupSlots) ?: return null
        val destinations = group.map { id ->
            val from = grid.slotOf(id)
            val to = from.offset(-shift.first, -shift.second)
            if (!grid.inBounds(to) || to !in holes) return null
            id to to
        }
        val next = grid.withCells { cells ->
            for (id in group) {
                cells[grid.slotOf(id).index(grid.cols)] = EMPTY
            }
            for ((id, to) in destinations) {
                cells[to.index(grid.cols)] = id
            }
        }
        return PushResult(next, groupSlots)
    }

    /**
     * Walk along [direction] from [holes] and swap with the **nearest** congruent
     * resting CC. Used when adjacent make-way / tunnel cannot clear a larger
     * rigid mass that sits between the lift and a same-size target.
     */
    fun tryNearestSameSizeSwapAlongAxis(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        locks: LockGroupService,
        allTileIds: Iterable<Int>,
        direction: AxisDirection,
    ): PushResult? {
        if (holes.isEmpty()) return null
        var landing = holes.map { it.step(direction) }.toSet()
        repeat(grid.rows + grid.cols) {
            if (landing.any { !grid.inBounds(it) }) return null
            trySameSizeSwap(
                grid = grid,
                holes = holes,
                stepped = landing,
                liftedTileIds = liftedTileIds,
                locks = locks,
                allTileIds = allTileIds,
            )?.let { return it }
            landing = landing.map { it.step(direction) }.toSet()
        }
        return null
    }

    /**
     * Singleton empty-fit along [direction]: jump the hole to the nearest empty
     * cell (other tiles stay put). Analog of nearest same-size swap.
     */
    fun tryNearestEmptyAlongAxis(
        grid: SlotGrid,
        holes: Set<GridPos>,
        liftedTileIds: Set<Int>,
        direction: AxisDirection,
    ): PushResult? {
        if (liftedTileIds.size != 1 || holes.size != 1) return null
        var landing = holes.map { it.step(direction) }.toSet()
        repeat(grid.rows + grid.cols) {
            if (landing.any { !grid.inBounds(it) }) return null
            if (landing.all { grid.tileAt(it) == null || it in holes }) {
                return PushResult(grid, landing)
            }
            landing = landing.map { it.step(direction) }.toSet()
        }
        return null
    }

    /**
     * Singleton lands on the finger-projected cell when that cell is empty.
     */
    fun tryFingerAimEmptyLand(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? {
        if (session.liftedTileIds.size != 1) return null
        val anchor = fingerTargetAnchor(session, fingerDeltaPx, cellWidthPx, cellHeightPx) ?: return null
        if (anchor == session.startAnchor) return null
        val landing = session.shapeOffsets.values
            .map { anchor.offset(it.row, it.col) }
            .toSet()
        if (landing.size != 1) return null
        if (landing.any { !session.grid.inBounds(it) }) return null
        if (landing.any { pos ->
            session.grid.tileAt(pos) != null && pos !in session.targetSlots
        }) return null
        return PushResult(session.grid, landing)
    }

    /**
     * Same-size swap onto the finger-projected landing cell (any direction).
     * Used mid-drag before axis push so off-axis aim beats on-axis nearest swap.
     */
    fun tryFingerAimSameSizeSwap(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? {
        val anchor = fingerTargetAnchor(session, fingerDeltaPx, cellWidthPx, cellHeightPx) ?: return null
        if (anchor == session.startAnchor) return null
        val landing = session.shapeOffsets.values
            .map { anchor.offset(it.row, it.col) }
            .toSet()
        if (landing.any { !session.grid.inBounds(it) }) return null
        if (!isSameSizeLanding(session, landing)) return null
        // Home landing still swaps when it completes both CCs (C↔J, BDF↔KMO).
        // Skip only when cutline is required and swap would not finish homes.
        if (isHomeLanding(session, landing) &&
            shouldCutlineHomeInsteadOfSwap(session, landing) &&
            !sameSizeSwapCompletesHomes(session, landing)
        ) {
            return null
        }

        val locks = if (session.enforceRigidLocks) {
            LockGroupService.compute(session.grid, session.manifest)
        } else {
            LockGroupService.isolated(session.manifest)
        }
        val allIds = session.manifest.tiles.map { it.id }
        val swapped = trySwapOnto(
            grid = session.grid,
            holes = session.targetSlots,
            landing = landing,
            liftedTileIds = session.liftedTileIds,
            locks = locks,
            allTileIds = allIds,
        ) ?: return null
        return PushResult(swapped, landing)
    }

    /**
     * Same-size swap onto the lift's home when that landing is occupied by a
     * congruent CC and exchanging finishes both groups' homes.
     */
    fun tryCompletingHomeSameSizeSwap(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? {
        val home = homeAnchor(session) ?: return null
        if (home == session.startAnchor) return null
        if (!fingerCoversHomeDominant(session, home, fingerDeltaPx, cellWidthPx, cellHeightPx)) {
            return null
        }
        val landing = session.shapeOffsets.values
            .map { home.offset(it.row, it.col) }
            .toSet()
        if (landing.any { !session.grid.inBounds(it) }) return null
        if (!sameSizeSwapCompletesHomes(session, landing)) return null
        val locks = if (session.enforceRigidLocks) {
            LockGroupService.compute(session.grid, session.manifest)
        } else {
            LockGroupService.isolated(session.manifest)
        }
        val swapped = trySwapOnto(
            grid = session.grid,
            holes = session.targetSlots,
            landing = landing,
            liftedTileIds = session.liftedTileIds,
            locks = locks,
            allTileIds = session.manifest.tiles.map { it.id },
        ) ?: return null
        return PushResult(swapped, landing)
    }

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
        /** Pre-drag board — home/cutline/finger-swap placement runs against this
         *  so mid-drag insert-row / tunnel noise does not corrupt the intended land. */
        originalBoard: PuzzleBoard? = null,
    ): PuzzleBoard {
        val fresh = originalBoard?.let { origin ->
            val originPos = session.liftedTileIds.firstNotNullOfOrNull { origin.grid.slotOfOrNull(it) }
                ?: return@let null
            origin.beginDrag(
                originPos,
                grouped = true,
                enforceRigidLocks = session.enforceRigidLocks,
                liftOverride = session.liftedTileIds,
            )
        } ?: session

        // Principle 1 + home cutline: scored below (no early return — avoids wrong swap target).

        val committed = tryPlaceAt(fresh, session.committedAnchor) ?: session.settlePlain()
        var best = committed
        var bestScore = scoreBoard(committed, session)

        fun consider(
            anchor: GridPos?,
            requireAlongPath: Boolean,
            /** Finger-aim may only claim same-size swaps; home may cutline too. */
            fingerAimOnly: Boolean,
        ) {
            if (anchor == null) return
            if (anchor == session.startAnchor) return
            if (requireAlongPath &&
                !targetAlongFingerPath(session, anchor, fingerDeltaPx, cellWidthPx, cellHeightPx)
            ) {
                val landingPeek = fresh.shapeOffsets.values
                    .map { anchor.offset(it.row, it.col) }
                    .toSet()
                val completingHome = isHomeLanding(fresh, landingPeek) &&
                    sameSizeSwapCompletesHomes(fresh, landingPeek) &&
                    fingerTowardHome(session, anchor, fingerDeltaPx, cellWidthPx, cellHeightPx)
                if (!completingHome) return
            }

            val landing = fresh.shapeOffsets.values
                .map { anchor.offset(it.row, it.col) }
                .toSet()
            val landingBlocked = landing.any { pos ->
                fresh.grid.inBounds(pos) &&
                    fresh.grid.tileAt(pos) != null &&
                    pos !in fresh.targetSlots
            }
            val emptyFit = session.liftedTileIds.size == 1 &&
                landing.all { pos ->
                    fresh.grid.inBounds(pos) &&
                        (fresh.grid.tileAt(pos) == null || pos in fresh.targetSlots)
                }
            // Occupied land → swap/cutline. Empty land only via finger-aim
            // (do not snap a singleton to a far empty home on release).
            if (!landingBlocked) {
                if (!(fingerAimOnly && emptyFit)) return
            }

            val sameSizeTarget = isSameSizeLanding(fresh, landing)
            val lockComplete = wouldCompleteLocks(fresh, landing) || isHomeLanding(fresh, landing)
            if (fingerAimOnly) {
                if (!sameSizeTarget && !emptyFit) return
            } else if (!sameSizeTarget && !lockComplete) {
                return
            }

            val placed = tryPlaceAt(fresh, anchor) ?: return
            val cutlineHome = isHomeLanding(fresh, landing) &&
                shouldCutlineHomeInsteadOfSwap(fresh, landing)
            val score = scoreBoard(placed, session) +
                when {
                    sameSizeTarget && sameSizeSwapCompletesHomes(fresh, landing) -> 80
                    cutlineHome -> 60
                    sameSizeTarget -> 50
                    emptyFit -> 40
                    else -> 0
                }
            if (score > bestScore) {
                bestScore = score
                best = placed
            }
        }

        // Finger projection + home: same-size swap onto the aimed tile; home cutline when needed.
        consider(
            fingerAnchor(fresh, fingerDeltaPx, cellWidthPx, cellHeightPx),
            requireAlongPath = false,
            fingerAimOnly = true,
        )
        consider(
            fingerTargetAnchor(fresh, fingerDeltaPx, cellWidthPx, cellHeightPx),
            requireAlongPath = false,
            fingerAimOnly = true,
        )

        // Home: lock-completing / home swap / cutline.
        consider(homeAnchor(session), requireAlongPath = true, fingerAimOnly = false)

        return best
    }

    /** True when landing cells are exactly one resting CC congruent to the lift. */
    private fun isSameSizeLanding(session: DragSession, landing: Set<GridPos>): Boolean {
        val locks = if (session.enforceRigidLocks) {
            LockGroupService.compute(session.grid, session.manifest)
        } else {
            LockGroupService.isolated(session.manifest)
        }
        val allIds = session.manifest.tiles.map { it.id }
        val contactIds = landing.mapNotNull { pos ->
            session.grid.tileAt(pos)?.takeIf { it !in session.liftedTileIds }
        }.toSet()
        if (contactIds.isEmpty()) return false
        val seed = contactIds.first()
        val group = locks.members(seed, allIds)
            .filter { it !in session.liftedTileIds && session.grid.slotOfOrNull(it) != null }
            .toSet()
        if (group.size != session.liftedTileIds.size) return false
        if (!contactIds.all { it in group }) return false
        val groupSlots = group.mapNotNull { session.grid.slotOfOrNull(it) }.toSet()
        return groupSlots == landing && translation(groupSlots, session.targetSlots) != null
    }

    private fun targetAlongFingerPath(
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

    private fun tryPlaceAt(session: DragSession, anchor: GridPos): PuzzleBoard? {
        val landing = session.shapeOffsets.values
            .map { anchor.offset(it.row, it.col) }
            .toSet()
        if (landing.any { it.col !in 0 until session.grid.cols || it.row < 0 }) return null
        // Landing may be below current grid — cutline can grow.
        val inGridLanding = landing.all { session.grid.inBounds(it) }

        val locks = if (session.enforceRigidLocks) {
            LockGroupService.compute(session.grid, session.manifest)
        } else {
            LockGroupService.isolated(session.manifest)
        }
        val allIds = session.manifest.tiles.map { it.id }

        if (inGridLanding &&
            landing.all { pos ->
                val t = session.grid.tileAt(pos)
                t == null || pos in session.targetSlots
            }
        ) {
            return finalizePlace(session.grid, session, landing)
        }

        if (inGridLanding &&
            isSameSizeLanding(session, landing) &&
            sameSizeSwapCompletesHomes(session, landing)
        ) {
            val swapped = trySwapOnto(
                grid = session.grid,
                holes = session.targetSlots,
                landing = landing,
                liftedTileIds = session.liftedTileIds,
                locks = locks,
                allTileIds = allIds,
            )
            if (swapped != null) return finalizePlace(swapped, session, landing)
        }

        // Occupants of the landing pack into vacated footprint / empties.
        // Cutline insert-row is only when this packing cannot clear the land.
        if (inGridLanding) {
            tryPackMakeWay(session, landing, locks, allIds)?.let { packed ->
                return finalizePlace(packed, session, landing)
            }
        }

        if (inGridLanding && lockCompletingJoinsKeptAbove(session, landing)) {
            val grown = tryCutlineMakeWay(session, landing) ?: return null
            return finalizePlace(grown, session, landing)
        }

        if (inGridLanding &&
            isHomeLanding(session, landing) &&
            shouldCutlineHomeInsteadOfSwap(session, landing)
        ) {
            val grown = tryCutlineMakeWay(session, landing) ?: return null
            return finalizePlace(grown, session, landing)
        }

        if (inGridLanding) {
            val swapped = trySwapOnto(
                grid = session.grid,
                holes = session.targetSlots,
                landing = landing,
                liftedTileIds = session.liftedTileIds,
                locks = locks,
                allTileIds = allIds,
            )
            if (swapped != null) return finalizePlace(swapped, session, landing)
        }

        if (inGridLanding && isHomeLanding(session, landing)) {
            val grown = tryCutlineMakeWay(session, landing) ?: return null
            return finalizePlace(grown, session, landing)
        }

        return null
    }

    /**
     * Lock-completing land that joins a tile above the landing row in the same
     * column (e.g. H under A). Skips same-row-only lock joins that a swap can satisfy.
     */
    private fun lockCompletingJoinsKeptAbove(
        session: DragSession,
        landing: Set<GridPos>,
    ): Boolean {
        if (!wouldCompleteLocks(session, landing)) return false
        val landRow = landing.minOf { it.row }
        val anchor = landingAnchor(session.shapeOffsets, landing)
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
    private fun sameSizeSwapCompletesHomes(
        session: DragSession,
        landing: Set<GridPos>,
    ): Boolean {
        if (!isSameSizeLanding(session, landing)) return false
        val holes = session.targetSlots
        val contactIds = landing.mapNotNull { pos ->
            session.grid.tileAt(pos)?.takeIf { it !in session.liftedTileIds }
        }
        if (contactIds.size != session.liftedTileIds.size) return false
        val shift = translation(
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
    private fun shouldCutlineHomeInsteadOfSwap(
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
                if (!isCutlineLandingRowKeeper(session, landing, landRow, pos, session.grid)) {
                    return true
                }
            }
        }
        return false
    }

    private fun trySwapOnto(
        grid: SlotGrid,
        holes: Set<GridPos>,
        landing: Set<GridPos>,
        liftedTileIds: Set<Int>,
        locks: LockGroupService,
        allTileIds: Iterable<Int>,
    ): SlotGrid? {
        val contactIds = landing.mapNotNull { pos ->
            grid.tileAt(pos)?.takeIf { it !in liftedTileIds }
        }.toSet()
        if (contactIds.isEmpty()) return null

        val seed = contactIds.first()
        val group = locks.members(seed, allTileIds)
            .filter { it !in liftedTileIds && grid.slotOfOrNull(it) != null }
            .toSet()
        if (group.size != landing.size) return null
        if (!contactIds.all { it in group }) return null
        val groupSlots = group.map { grid.slotOf(it) }.toSet()
        if (groupSlots != landing) return null
        val shift = translation(groupSlots, holes) ?: return null
        val destinations = group.map { id ->
            val from = grid.slotOf(id)
            val to = from.offset(shift.first, shift.second)
            if (!grid.inBounds(to)) return null
            id to to
        }
        return grid.withCells { cells ->
            for (id in group) {
                cells[grid.slotOf(id).index(grid.cols)] = EMPTY
            }
            for ((id, to) in destinations) {
                cells[to.index(grid.cols)] = id
            }
        }
    }

    /**
     * Axis-translate the lifted footprint onto [landing] by PushService make-way:
     * displaced tiles gravitate into vacated holes and persistent empties.
     * Does not grow the playfield — callers may cutline if this returns null.
     */
    private fun tryPackMakeWay(
        session: DragSession,
        landing: Set<GridPos>,
        locks: LockGroupService,
        allIds: Iterable<Int>,
    ): SlotGrid? {
        val shift = translation(session.targetSlots, landing) ?: return null
        val direction = when {
            shift.first > 0 && shift.second == 0 -> AxisDirection.Down
            shift.first < 0 && shift.second == 0 -> AxisDirection.Up
            shift.first == 0 && shift.second > 0 -> AxisDirection.Right
            shift.first == 0 && shift.second < 0 -> AxisDirection.Left
            else -> return null
        }
        val stepsNeeded = abs(shift.first) + abs(shift.second)
        if (stepsNeeded <= 0) return null

        var grid = session.grid
        var holes = session.targetSlots
        repeat(stepsNeeded) {
            if (holes == landing) return grid
            val pushed = PushService.tryPush(
                grid = grid,
                holes = holes,
                liftedTileIds = session.liftedTileIds,
                direction = direction,
                locks = locks,
                allTileIds = allIds,
            ) ?: return null
            if (pushed.grid.rows != session.grid.rows || pushed.grid.cols != session.grid.cols) {
                return null
            }
            if (pushed.newHoles == holes) return null
            grid = pushed.grid
            holes = pushed.newHoles
        }
        return if (holes == landing) grid else null
    }

    /**
     * Keep rows above the landing and non-landing tiles on landing rows;
     * insert one empty separator; repack the rest column-major (tiles +
     * persistent empties; drop the lift's current holes).
     */
    fun tryCutlineMakeWay(session: DragSession, landing: Set<GridPos>): SlotGrid? {
        val grid = session.grid
        val landRow = landing.minOf { it.row }
        val cutAfter = landing.maxOf { it.row }
        val holes = session.targetSlots

        // Grow vertically if landing extends past the grid.
        var work = grid
        val needRows = landing.maxOf { it.row } + 1
        while (work.rows < needRows) {
            work = work.insertRow(work.rows)
        }

        val keptTiles = mutableMapOf<GridPos, Int>()
        for (r in 0 until work.rows) {
            for (c in 0 until work.cols) {
                val pos = GridPos(r, c)
                val id = work.tileAt(pos) ?: continue
                if (id in session.liftedTileIds) continue
                val keep = when {
                    r < landRow -> true
                    r in landRow..cutAfter && pos !in landing ->
                        isCutlineLandingRowKeeper(session, landing, landRow, pos, work)
                    else -> false
                }
                if (keep) keptTiles[pos] = id
            }
        }

        val sequence = mutableListOf<Int?>()
        for (c in 0 until work.cols) {
            for (r in landRow until work.rows) {
                val pos = GridPos(r, c)
                if (pos in keptTiles) continue
                if (pos in holes) continue
                if (pos in landing) {
                    val occ = work.tileAt(pos)
                    if (occ != null && occ !in session.liftedTileIds) sequence += occ
                    continue
                }
                sequence += work.tileAt(pos)
            }
        }
        while (sequence.isNotEmpty() && sequence.last() == null) {
            sequence.removeAt(sequence.lastIndex)
        }

        val sepRow = cutAfter + 1
        val packStart = sepRow + 1
        val packedRows =
            if (sequence.isEmpty()) 0 else (sequence.size + work.cols - 1) / work.cols
        val newRows = (packStart + packedRows).coerceAtLeast(work.rows)

        val next = SlotGrid.empty(work.cols, newRows).withCells { cells ->
            for ((pos, id) in keptTiles) {
                cells[pos.index(work.cols)] = id
            }
            sequence.forEachIndexed { i, id ->
                if (id == null) return@forEachIndexed
                val r = packStart + i / work.cols
                val c = i % work.cols
                cells[GridPos(r, c).index(work.cols)] = id
            }
        }
        if (landing.any { !next.inBounds(it) || next.tileAt(it) != null }) return null
        return next
    }

    /**
     * Landing-row neighbor stays only when it lock-connects to the kept rows above
     * (e.g. D beside H under A). Otherwise it repacks below the separator.
     */
    private fun isCutlineLandingRowKeeper(
        session: DragSession,
        landing: Set<GridPos>,
        landRow: Int,
        pos: GridPos,
        grid: SlotGrid,
    ): Boolean {
        val id = grid.tileAt(pos) ?: return false
        val locks = if (session.enforceRigidLocks) {
            LockGroupService.compute(grid, session.manifest)
        } else {
            LockGroupService.isolated(session.manifest)
        }
        val allIds = session.manifest.tiles.map { it.id }
        val group = locks.members(id, allIds)
        return group.any { memberId ->
            if (memberId == id) return@any false
            val slot = grid.slotOfOrNull(memberId) ?: return@any false
            slot.row < landRow
        }
    }

    private fun finalizePlace(
        grid: SlotGrid,
        session: DragSession,
        landing: Set<GridPos>,
    ): PuzzleBoard {
        val anchor = landingAnchor(session.shapeOffsets, landing)
        require(landing.all { grid.inBounds(it) && grid.tileAt(it) == null })
        val settled = grid.withCells { cells ->
            for ((tileId, offset) in session.shapeOffsets) {
                val pos = anchor.offset(offset.row, offset.col)
                cells[pos.index(grid.cols)] = tileId
            }
        }
        val collapsed = settled.collapseEmptyRowsPreservingLocks(session.manifest)
        return PuzzleBoard(
            collapsed,
            LockGroupService.compute(collapsed, session.manifest),
            session.manifest,
        )
    }

    private fun landingAnchor(shapeOffsets: Map<Int, GridPos>, landing: Set<GridPos>): GridPos {
        val minOff = shapeOffsets.values.minWith(compareBy({ it.row }, { it.col }))
        val minLanding = landing.minWith(compareBy({ it.row }, { it.col }))
        return minLanding.offset(-minOff.row, -minOff.col)
    }

    private fun isHomeLanding(session: DragSession, landing: Set<GridPos>): Boolean {
        val anchor = landingAnchor(session.shapeOffsets, landing)
        return session.liftedTileIds.all { id ->
            val home = session.manifest.tileOrNull(id)?.home ?: return@all false
            anchor.offset(
                session.shapeOffsets.getValue(id).row,
                session.shapeOffsets.getValue(id).col,
            ) == home
        }
    }

    private fun wouldCompleteLocks(session: DragSession, landing: Set<GridPos>): Boolean {
        if (landing.any { it.col !in 0 until session.grid.cols || it.row < 0 }) return false
        val anchor = landingAnchor(session.shapeOffsets, landing)
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

    private fun scoreBoard(board: PuzzleBoard, session: DragSession): Int {
        var score = 0
        for (id in session.liftedTileIds) {
            val home = session.manifest.tileOrNull(id)?.home
            if (home != null && board.grid.slotOfOrNull(id) == home) score += 10
            score += board.componentContaining(id).size
        }
        return score
    }

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

    private fun translation(from: Set<GridPos>, to: Set<GridPos>): Pair<Int, Int>? {
        if (from.size != to.size || from.isEmpty()) return null
        val fa = from.minWith(compareBy({ it.row }, { it.col }))
        val ta = to.minWith(compareBy({ it.row }, { it.col }))
        val dRow = ta.row - fa.row
        val dCol = ta.col - fa.col
        val mapped = from.map { it.offset(dRow, dCol) }.toSet()
        return if (mapped == to) dRow to dCol else null
    }
}

internal fun DragSession.settlePlain(): PuzzleBoard {
    for ((tileId, offset) in shapeOffsets) {
        val slot = committedAnchor.offset(offset.row, offset.col)
        require(grid.inBounds(slot)) { "Tile $tileId would settle out of bounds at $slot" }
        require(grid.tileAt(slot) == null) {
            "Tile $tileId cannot settle onto occupied slot $slot"
        }
    }
    val settled = grid.withCells { cells ->
        shapeOffsets.forEach { (tileId, offset) ->
            val slot = committedAnchor.offset(offset.row, offset.col)
            cells[slot.index(grid.cols)] = tileId
        }
    }
    val collapsed = settled.collapseEmptyRowsPreservingLocks(manifest)
    return PuzzleBoard(collapsed, LockGroupService.compute(collapsed, manifest), manifest)
}

/**
 * Remove fully empty rows unless collapsing one would newly vertically lock
 * two tiles (home offset matches). Preserves cutline separators.
 */
fun SlotGrid.collapseEmptyRowsPreservingLocks(manifest: PuzzleManifest): SlotGrid {
    val emptyRows = (0 until rows).filter { row ->
        (0 until cols).all { col -> tileAt(GridPos(row, col)) == null }
    }.toSet()
    if (emptyRows.isEmpty()) return this

    val remove = mutableSetOf<Int>()
    for (row in emptyRows) {
        var createsLock = false
        for (c in 0 until cols) {
            val aboveId = nearestTileAbove(row, c, emptyRows) ?: continue
            val belowId = nearestTileBelow(row, c, emptyRows) ?: continue
            val homeA = manifest.tileOrNull(aboveId)?.home ?: continue
            val homeB = manifest.tileOrNull(belowId)?.home ?: continue
            // After collapse they become vertical neighbors: below is +1 row from above.
            if (homeB.row - homeA.row == 1 && homeB.col - homeA.col == 0) {
                createsLock = true
                break
            }
        }
        if (!createsLock) remove += row
    }
    if (remove.isEmpty()) return this

    val kept = (0 until rows).filter { it !in remove }
    if (kept.size == rows) return this
    if (kept.isEmpty()) return SlotGrid.empty(cols, 1)
    val cells = copyCells()
    val next = IntArray(cols * kept.size) { EMPTY }
    kept.forEachIndexed { destRow, srcRow ->
        for (c in 0 until cols) {
            next[destRow * cols + c] = cells[srcRow * cols + c]
        }
    }
    return SlotGrid(cols, kept.size, next)
}

private fun SlotGrid.nearestTileAbove(row: Int, col: Int, emptyRows: Set<Int>): Int? {
    var r = row - 1
    while (r >= 0 && r in emptyRows) r--
    if (r < 0) return null
    return tileAt(GridPos(r, col))
}

private fun SlotGrid.nearestTileBelow(row: Int, col: Int, emptyRows: Set<Int>): Int? {
    var r = row + 1
    while (r < rows && r in emptyRows) r++
    if (r >= rows) return null
    return tileAt(GridPos(r, col))
}
