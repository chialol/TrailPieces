package com.trailpieces.puzzle.service



import com.trailpieces.puzzle.model.AxisDirection

import com.trailpieces.puzzle.model.EMPTY

import com.trailpieces.puzzle.model.GridPos

import com.trailpieces.puzzle.model.SlotGrid

import kotlin.math.abs



/**

 * Release-only layout upgrades (cutline, swap, pack). Mid-drag must not call this.

 * Each upgrade entry point owns one path — no generic score loop.

 */

object ReleaseUpgrade {



    fun tryPlace(session: DragSession, anchor: GridPos): PuzzleBoard? {

        val landing = landingAt(session, anchor)

        if (landing.any { it.col !in 0 until session.grid.cols || it.row < 0 }) return null

        val inGridLanding = landing.all { session.grid.inBounds(it) }

        val locks = session.rigidLocks()



        if (inGridLanding &&

            landing.all { pos ->

                val t = session.grid.tileAt(pos)

                t == null || pos in session.targetSlots

            }

        ) {

            return finalizePlace(session.grid, session, landing)

        }



        if (inGridLanding &&

            PlacementService.isSameSizeLanding(session, landing) &&

            PlacementService.sameSizeSwapCompletesHomes(session, landing)

        ) {

            val swapped = trySwapOnto(

                grid = session.grid,

                holes = session.targetSlots,

                landing = landing,

                liftedTileIds = session.liftedTileIds,

                locks = locks,

            )

            if (swapped != null) return finalizePlace(swapped, session, landing)

        }



        if (inGridLanding) {

            tryPackMakeWay(session, landing, locks)?.let { packed ->

                return finalizePlace(packed, session, landing)

            }

        }



        if (inGridLanding && PlacementService.lockCompletingJoinsKeptAbove(session, landing)) {

            val grown = tryCutlineMakeWay(session, landing) ?: return null

            return finalizePlace(grown, session, landing)

        }



        if (inGridLanding &&

            PlacementService.isHomeLanding(session, landing) &&

            PlacementService.shouldCutlineHomeInsteadOfSwap(session, landing)

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

            )

            if (swapped != null) return finalizePlace(swapped, session, landing)

        }



        if (inGridLanding && PlacementService.isHomeLanding(session, landing)) {

            val grown = tryCutlineMakeWay(session, landing) ?: return null

            return finalizePlace(grown, session, landing)

        }



        return null

    }



    internal fun tryCompletingSwap(session: DragSession, anchor: GridPos): PuzzleBoard? {

        val landing = landingAt(session, anchor)

        if (!landing.all { session.grid.inBounds(it) }) return null

        if (!PlacementService.isSameSizeLanding(session, landing)) return null

        if (!PlacementService.sameSizeSwapCompletesHomes(session, landing)) return null

        val swapped = trySwapOnto(

            grid = session.grid,

            holes = session.targetSlots,

            landing = landing,

            liftedTileIds = session.liftedTileIds,

            locks = session.rigidLocks(),

        ) ?: return null

        return finalizePlace(swapped, session, landing)

    }



    internal fun tryCutlineHome(session: DragSession, anchor: GridPos): PuzzleBoard? {

        val landing = landingAt(session, anchor)

        if (!landing.all { session.grid.inBounds(it) }) return null

        val grown = tryCutlineMakeWay(session, landing) ?: return null

        return finalizePlace(grown, session, landing)

    }



    internal fun tryLockConnectCutline(session: DragSession, anchor: GridPos): PuzzleBoard? {

        val landing = landingAt(session, anchor)

        if (!landing.all { session.grid.inBounds(it) }) return null

        if (!PlacementService.lockCompletingJoinsKeptAbove(session, landing)) return null

        val grown = tryCutlineMakeWay(session, landing) ?: return null

        return finalizePlace(grown, session, landing)

    }



    internal fun tryHomeLand(session: DragSession, anchor: GridPos): PuzzleBoard? {

        val landing = landingAt(session, anchor)

        if (!landing.all { session.grid.inBounds(it) }) return null

        if (!PlacementService.isHomeLanding(session, landing)) return null

        val locks = session.rigidLocks()



        if (landing.all { pos ->

                val t = session.grid.tileAt(pos)

                t == null || pos in session.targetSlots

            }

        ) {

            return finalizePlace(session.grid, session, landing)

        }



        tryPackMakeWay(session, landing, locks)?.let { packed ->

            return finalizePlace(packed, session, landing)

        }



        val swapped = trySwapOnto(

            grid = session.grid,

            holes = session.targetSlots,

            landing = landing,

            liftedTileIds = session.liftedTileIds,

            locks = locks,

        )

        if (swapped != null) return finalizePlace(swapped, session, landing)



        val grown = tryCutlineMakeWay(session, landing) ?: return null

        return finalizePlace(grown, session, landing)

    }



    internal fun tryEmptyFit(session: DragSession, anchor: GridPos): PuzzleBoard? {

        val landing = landingAt(session, anchor)

        if (!landing.all { session.grid.inBounds(it) }) return null

        if (!landing.all { pos ->

                val t = session.grid.tileAt(pos)

                t == null || pos in session.targetSlots

            }

        ) {

            return null

        }

        return finalizePlace(session.grid, session, landing)

    }



    internal fun trySameSizeSwap(session: DragSession, anchor: GridPos): PuzzleBoard? {

        val landing = landingAt(session, anchor)

        if (!landing.all { session.grid.inBounds(it) }) return null

        if (!PlacementService.isSameSizeLanding(session, landing)) return null

        val swapped = trySwapOnto(

            grid = session.grid,

            holes = session.targetSlots,

            landing = landing,

            liftedTileIds = session.liftedTileIds,

            locks = session.rigidLocks(),

        ) ?: return null

        return finalizePlace(swapped, session, landing)

    }



    internal fun isCutlineLandingRowKeeper(

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



    private fun landingAt(session: DragSession, anchor: GridPos): Set<GridPos> =

        session.shapeOffsets.values.map { anchor.offset(it.row, it.col) }.toSet()



    private fun trySwapOnto(

        grid: SlotGrid,

        holes: Set<GridPos>,

        landing: Set<GridPos>,

        liftedTileIds: Set<Int>,

        locks: FrozenLockGraph,

    ): SlotGrid? {

        val contactIds = landing.mapNotNull { pos ->

            grid.tileAt(pos)?.takeIf { it !in liftedTileIds }

        }.toSet()

        if (contactIds.isEmpty()) return null



        val seed = contactIds.first()

        val group = locks.members(seed)

            .filter { it !in liftedTileIds && grid.slotOfOrNull(it) != null }

            .toSet()

        if (group.size != landing.size) return null

        if (!contactIds.all { it in group }) return null

        val groupSlots = group.map { grid.slotOf(it) }.toSet()

        if (groupSlots != landing) return null

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



    private fun tryPackMakeWay(

        session: DragSession,

        landing: Set<GridPos>,

        locks: FrozenLockGraph,

    ): SlotGrid? {

        val shift = GridGeometry.translation(session.targetSlots, landing) ?: return null

        val axis = when {

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

                direction = axis,

                locks = locks,

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



    private fun tryCutlineMakeWay(session: DragSession, landing: Set<GridPos>): SlotGrid? {

        val grid = session.grid

        val landRow = landing.minOf { it.row }

        val cutAfter = landing.maxOf { it.row }

        val startHoles = session.shapeOffsets.values.map { offset ->

            session.startAnchor.offset(offset.row, offset.col)

        }.toSet()

        val holes = session.targetSlots + startHoles



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

                if (pos in startHoles) continue

                val keep = when {

                    r < landRow -> true

                    r in landRow..cutAfter && pos !in landing ->

                        isCutlineLandingRowKeeper(session, landing, landRow, pos, work)

                    else -> false

                }

                if (keep) keptTiles[pos] = id

            }

        }



        val sepRow = cutAfter + 1

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



    private fun finalizePlace(

        grid: SlotGrid,

        session: DragSession,

        landing: Set<GridPos>,

    ): PuzzleBoard {

        val anchor = GridGeometry.landingAnchor(session.shapeOffsets, landing)

        require(landing.all { grid.inBounds(it) && grid.tileAt(it) == null })

        val settled = grid.withCells { cells ->

            for ((tileId, offset) in session.shapeOffsets) {

                val pos = anchor.offset(offset.row, offset.col)

                cells[pos.index(grid.cols)] = tileId

            }

        }

        val collapsed = settled.collapseEmptyRows()

        return PuzzleBoard(

            collapsed,

            LockGroupService.compute(collapsed, session.manifest),

            session.manifest,

        )

    }

}


