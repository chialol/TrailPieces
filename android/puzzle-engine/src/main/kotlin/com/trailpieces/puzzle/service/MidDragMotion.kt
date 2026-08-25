package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.EMPTY
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.SlotGrid
import com.trailpieces.puzzle.model.Vec2

/**
 * All mid-drag layout commits. Uses [FrozenLockGraph] only — never recomputes locks
 * from live geometry during push / swap.
 */
object MidDragMotion {

    fun tryPush(session: DragSession, direction: AxisDirection): DragSession? {
        val holes = session.targetSlots
        val pushed = PushService.tryPush(
            grid = session.grid,
            holes = holes,
            liftedTileIds = session.liftedTileIds,
            direction = direction,
            locks = session.rigidLocks(),
        )
        if (pushed != null) {
            return applyPushResult(session, pushed)
        }
        if (direction == AxisDirection.Up &&
            session.enforceRigidLocks &&
            hasInBoundsUpBlocker(session, holes)
        ) {
            return tryInsertRowAboveAndPark(session)
        }
        return null
    }

    fun tryFingerAimSameSizeSwap(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? {
        val result = tryFingerAimSameSizeSwapResult(session, fingerDeltaPx, cellWidthPx, cellHeightPx)
            ?: return null
        return applyPushResult(session, result)
    }

    fun tryFingerAimEmptyLand(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? {
        val result = tryFingerAimEmptyLandResult(session, fingerDeltaPx, cellWidthPx, cellHeightPx)
            ?: return null
        return applyPushResult(session, result)
    }

    fun tryCompletingHomeSameSizeSwap(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): DragSession? {
        val result = tryCompletingHomeSameSizeSwapResult(session, fingerDeltaPx, cellWidthPx, cellHeightPx)
            ?: return null
        return applyPushResult(session, result)
    }

    fun tryNearestEmptyAlongAxis(session: DragSession, direction: AxisDirection): DragSession? {
        val result = AxisMotion.tryNearestEmptyAlongAxis(
            grid = session.grid,
            holes = session.targetSlots,
            liftedTileIds = session.liftedTileIds,
            direction = direction,
        ) ?: return null
        return applyPushResult(session, result)
    }

    internal fun tryFingerAimSameSizeSwapResult(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? {
        val anchor = PlacementService.fingerTargetAnchorForSwap(
            session, fingerDeltaPx, cellWidthPx, cellHeightPx,
        ) ?: return null
        if (anchor == session.startAnchor) return null
        val landing = session.shapeOffsets.values
            .map { anchor.offset(it.row, it.col) }
            .toSet()
        if (landing.any { !session.grid.inBounds(it) }) return null
        if (!PlacementService.isSameSizeLanding(session, landing)) return null
        // Home landings are allowed mid-drag: park-only release means the swap
        // must already be visible. Never veto with settle-era cutline preference.

        val partnerHoles = swapPartnerHoles(session, anchor)
        var work = session.grid
        if (partnerHoles != session.targetSlots) {
            work = evacuatePartnerHoles(
                grid = work,
                partnerHoles = partnerHoles,
                evacuateTo = session.targetSlots,
                liftedTileIds = session.liftedTileIds,
            ) ?: return null
        }
        val swapped = trySwapOnto(
            grid = work,
            holes = partnerHoles,
            landing = landing,
            liftedTileIds = session.liftedTileIds,
            locks = session.rigidLocks(),
        ) ?: return null
        return PushResult(swapped, landing)
    }

    internal fun tryFingerAimEmptyLandResult(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? {
        if (session.liftedTileIds.size != 1) return null
        val anchor = PlacementService.fingerTargetAnchorForSwap(
            session, fingerDeltaPx, cellWidthPx, cellHeightPx,
        ) ?: return null
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

    internal fun tryCompletingHomeSameSizeSwapResult(
        session: DragSession,
        fingerDeltaPx: Vec2,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): PushResult? {
        val home = PlacementService.homeAnchorForSettle(session) ?: return null
        if (home == session.startAnchor) return null
        // Full anchor cover (all axes), not dominant-only — otherwise a mostly
        // vertical drag can snipe a sideways home swap, then residual push-Left
        // undoes it in a haptic jitter loop.
        if (!PlacementService.fingerCoversAnchor(
                session, home, fingerDeltaPx, cellWidthPx, cellHeightPx,
            )
        ) {
            return null
        }
        val landing = session.shapeOffsets.values
            .map { home.offset(it.row, it.col) }
            .toSet()
        if (landing.any { !session.grid.inBounds(it) }) return null
        if (!PlacementService.sameSizeSwapCompletesHomes(session, landing)) return null
        val swapped = trySwapOnto(
            grid = session.grid,
            holes = session.targetSlots,
            landing = landing,
            liftedTileIds = session.liftedTileIds,
            locks = session.rigidLocks(),
        ) ?: return null
        return PushResult(swapped, landing)
    }

    /**
     * Partner parking for aim-swap: pickup footprint when the aim is off-axis
     * from pickup (left-then-down) or the lift path used both axes / ≥2 cells;
     * else committed holes (S under Q, U onto X).
     */
    internal fun swapPartnerHoles(session: DragSession, aimAnchor: GridPos? = null): Set<GridPos> {
        val startHoles = session.shapeOffsets.values
            .map { session.startAnchor.offset(it.row, it.col) }
            .toSet()
        if (aimAnchor != null) {
            val stepsFromCommitted = kotlin.math.abs(aimAnchor.row - session.committedAnchor.row) +
                kotlin.math.abs(aimAnchor.col - session.committedAnchor.col)
            // Perpendicular swap beside committed hole (S/Q, U/X, down-then-left).
            // Reserve pickup when aim is not adjacent — indirect paths (left-then-down).
            if (stepsFromCommitted != 1) return startHoles
        }
        val dRow = kotlin.math.abs(session.committedAnchor.row - session.startAnchor.row)
        val dCol = kotlin.math.abs(session.committedAnchor.col - session.startAnchor.col)
        if (dRow >= 1 && dCol >= 1) return startHoles
        if (dRow + dCol >= 2) return startHoles
        return session.targetSlots
    }

    private fun hasInBoundsUpBlocker(session: DragSession, holes: Set<GridPos>): Boolean =
        holes.any { hole ->
            val source = hole.offset(-1, 0)
            session.grid.inBounds(source) &&
                session.grid.tileAt(source) != null &&
                session.grid.tileAt(source) !in session.liftedTileIds
        }

    private fun tryInsertRowAboveAndPark(session: DragSession): DragSession? {
        val grown = session.grid.insertRow(0)
        val shiftedAnchor = GridPos(session.committedAnchor.row + 1, session.committedAnchor.col)
        val newAnchor = GridPos(0, session.committedAnchor.col)
        val parked = session.copy(grid = grown, committedAnchor = shiftedAnchor)
        val targets = session.shapeOffsets.values.map { newAnchor.offset(it.row, it.col) }.toSet()
        if (targets.any { !grown.inBounds(it) || grown.tileAt(it) != null }) return null
        return parked.copy(committedAnchor = newAnchor)
    }

    private fun applyPushResult(session: DragSession, result: PushResult): DragSession? {
        val newAnchor = GridGeometry.anchorForHoles(result.newHoles) ?: return null
        return session.copy(grid = result.grid, committedAnchor = newAnchor)
    }

    private fun evacuatePartnerHoles(
        grid: SlotGrid,
        partnerHoles: Set<GridPos>,
        evacuateTo: Set<GridPos>,
        liftedTileIds: Set<Int>,
    ): SlotGrid? {
        if (partnerHoles.isEmpty()) return grid
        val shift = GridGeometry.translation(partnerHoles, evacuateTo) ?: return null
        val moves = buildList<Pair<Pair<GridPos, GridPos>, Int>> {
            for (from in partnerHoles) {
                val id = grid.tileAt(from) ?: continue
                if (id in liftedTileIds) return null
                val to = from.offset(shift.first, shift.second)
                if (!grid.inBounds(to)) return null
                val dest = grid.tileAt(to)
                if (dest != null && dest !in liftedTileIds) return null
                add((from to to) to id)
            }
        }
        if (moves.isEmpty()) return grid
        return grid.withCells { cells ->
            for ((pair, _) in moves) {
                cells[pair.first.index(grid.cols)] = EMPTY
            }
            for ((pair, id) in moves) {
                cells[pair.second.index(grid.cols)] = id
            }
        }
    }

    private fun trySwapOnto(
        grid: SlotGrid,
        holes: Set<GridPos>,
        landing: Set<GridPos>,
        liftedTileIds: Set<Int>,
        locks: FrozenLockGraph,
    ): SlotGrid? {
        val group = AxisMotion.congruentFootprintIds(grid, landing, liftedTileIds, locks) ?: return null
        if (group.size != landing.size) return null
        val groupSlots = group.map { grid.slotOf(it) }.toSet()
        val shift = GridGeometry.translation(groupSlots, holes) ?: return null
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
}
