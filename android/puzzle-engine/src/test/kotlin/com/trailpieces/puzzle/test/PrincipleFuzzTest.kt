package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.service.DragEngine
import com.trailpieces.puzzle.service.LockGroupService
import com.trailpieces.puzzle.service.PuzzleBoard
import com.trailpieces.puzzle.service.ShuffleService
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Temporary non-deterministic probe — not a regression suite.
 *
 * After the completing-home same-size fix, a 40-board harvest (random axis
 * moves + congruent CC pair swaps) found **0** true principle gaps.
 * Re-enable (@Ignore off) to resample after rule changes.
 *
 * `.\gradlew.bat :puzzle-engine:test --tests com.trailpieces.puzzle.test.PrincipleFuzzTest`
 */
@Ignore("Harvest-only fuzz; re-enable to sample principle gaps")
class PrincipleFuzzTest {

    private val cell = 100f
    private val rng = Random(20260824)

    private val manifest = PuzzleManifest(
        id = "fuzz-2x12",
        title = "Fuzz",
        cols = 2,
        rows = 12,
        puzzleWidth = 200,
        puzzleHeight = 1200,
        tiles = (0..23).map { id ->
            PuzzleTile(id, GridPos(id / 2, id % 2), ('a' + id).toString())
        },
    )

    @Test
    fun shuffleAndProbeMoves() {
        val failures = mutableListOf<String>()
        repeat(40) { boardIdx ->
            var board = ShuffleService.shuffled(manifest, moves = 80)
            probeSameSizePairs(board, boardIdx, failures)
            repeat(8) { moveIdx ->
                if (board.isSolved) return@repeat
                val origin = randomFilled(board) ?: return@repeat
                val dir = AxisDirection.entries.random(rng)
                val before = occupancyKey(board)
                val engine = DragEngine(manifest, board)
                if (!engine.startDrag(origin)) return@repeat
                val delta = when (dir) {
                    AxisDirection.Up -> Vec2(0f, -220f)
                    AxisDirection.Down -> Vec2(0f, 220f)
                    AxisDirection.Left -> Vec2(-120f, 0f)
                    AxisDirection.Right -> Vec2(120f, 0f)
                }
                engine.moveFinger(delta, cell, cell)
                val after = engine.endDrag()
                val changed = occupancyKey(after) != before
                if (!changed) {
                    val why = diagnoseStuck(board, origin, dir)
                    if (why != null) {
                        failures += "board=$boardIdx move=$moveIdx origin=$origin dir=$dir\n$why\n${dumpBrief(board)}"
                    }
                } else {
                    board = after
                }
            }
        }
        if (failures.isNotEmpty()) {
            error(
                "Principle gaps (${failures.size}):\n\n" +
                    failures.take(25).joinToString("\n----\n"),
            )
        }
    }

    /** Drag every congruent same-size pair onto each other. */
    private fun probeSameSizePairs(
        board: PuzzleBoard,
        boardIdx: Int,
        failures: MutableList<String>,
    ) {
        val groups = lockGroups(board).filter { it.size in 1..6 }
        for (i in groups.indices) {
            for (j in groups.indices) {
                if (i == j) continue
                val a = groups[i]
                val b = groups[j]
                if (a.size != b.size) continue
                if (relativeShape(board, a) != relativeShape(board, b)) continue
                val origin = board.grid.slotOfOrNull(a.min()) ?: continue
                val target = board.grid.slotOfOrNull(b.min()) ?: continue
                val dRow = target.row - origin.row
                val dCol = target.col - origin.col
                if (dRow == 0 && dCol == 0) continue
                val engine = DragEngine(manifest, board)
                if (!engine.startDrag(origin)) continue
                engine.moveFinger(Vec2(dCol * cell, dRow * cell), cell, cell)
                val after = engine.endDrag()
                if (occupancyKey(after) == occupancyKey(board)) {
                    failures += "board=$boardIdx pair-swap $a -> $b origin=$origin target=$target\n" +
                        "P1 congruent CCs did not exchange\n${dumpBrief(board)}"
                }
            }
        }
    }

    private fun lockGroups(board: PuzzleBoard): List<Set<Int>> {
        val seen = mutableSetOf<Int>()
        val out = mutableListOf<Set<Int>>()
        for (id in manifest.tiles.map { it.id }) {
            if (id in seen) continue
            val g = board.componentContaining(id)
            seen += g
            out += g
        }
        return out
    }

    private fun relativeShape(board: PuzzleBoard, ids: Set<Int>): Set<GridPos> {
        val slots = ids.mapNotNull { board.grid.slotOfOrNull(it) }
        val minR = slots.minOf { it.row }
        val minC = slots.minOf { it.col }
        return slots.map { GridPos(it.row - minR, it.col - minC) }.toSet()
    }

    private fun randomFilled(board: PuzzleBoard): GridPos? {
        val filled = buildList {
            for (r in 0 until board.rows) {
                for (c in 0 until board.cols) {
                    val p = GridPos(r, c)
                    if (board.tileAt(p) != null) add(p)
                }
            }
        }
        return filled.randomOrNull(rng)
    }

    private fun occupancyKey(board: PuzzleBoard): String = buildString {
        for (r in 0 until board.grid.rows) {
            for (c in 0 until board.grid.cols) {
                append(board.grid.tileAt(GridPos(r, c)) ?: -1)
                append(',')
            }
            append(';')
        }
    }

    /**
     * @return human reason if a principle appears to apply, else null (stuck is OK).
     */
    private fun diagnoseStuck(board: PuzzleBoard, origin: GridPos, dir: AxisDirection): String? {
        val session = board.beginDrag(origin, grouped = true) ?: return null
        val locks = LockGroupService.compute(session.grid, manifest)
        val allIds = manifest.tiles.map { it.id }
        val holes = session.targetSlots
        val stepped = holes.map {
            GridPos(it.row + dir.dRow, it.col + dir.dCol)
        }.toSet()

        val sameSizeAhead = sameSizeGroupAt(board, session.liftedTileIds.size, stepped, locks, allIds)
        if (sameSizeAhead != null) {
            return "P1 same-size swap along $dir: lift ${session.liftedTileIds} vs $sameSizeAhead at $stepped"
        }

        val home = session.liftedTileIds.mapNotNull { manifest.tileOrNull(it)?.home }.toSet()
        if (home.size == session.liftedTileIds.size && home != holes) {
            val homeGroup = sameSizeGroupAt(board, session.liftedTileIds.size, home, locks, allIds)
            if (homeGroup != null) {
                val theirHomes = homeGroup.mapNotNull { manifest.tileOrNull(it)?.home }.toSet()
                val mutual = theirHomes == holes
                val homeAnchor = home.minWith(compareBy({ it.row }, { it.col }))
                val dRow = homeAnchor.row - session.startAnchor.row
                val dCol = homeAnchor.col - session.startAnchor.col
                val dominantDown = kotlin.math.abs(dRow) >= kotlin.math.abs(dCol) && dRow > 0
                val dominantUp = kotlin.math.abs(dRow) >= kotlin.math.abs(dCol) && dRow < 0
                val dominantRight = kotlin.math.abs(dCol) > kotlin.math.abs(dRow) && dCol > 0
                val dominantLeft = kotlin.math.abs(dCol) > kotlin.math.abs(dRow) && dCol < 0
                val toward = when (dir) {
                    AxisDirection.Down -> dominantDown
                    AxisDirection.Up -> dominantUp
                    AxisDirection.Right -> dominantRight
                    AxisDirection.Left -> dominantLeft
                }
                if (mutual && toward) {
                    return "P1 mutual-home swap toward $dir: lift ${session.liftedTileIds} @ $holes vs $homeGroup @ $home"
                }
            }
        }

        if (stepped.all { session.grid.inBounds(it) && session.grid.tileAt(it) == null }) {
            return "P2 empty-fit: holes $holes should slide into $stepped"
        }

        return null
    }

    private fun sameSizeGroupAt(
        board: PuzzleBoard,
        liftSize: Int,
        cells: Set<GridPos>,
        locks: LockGroupService,
        allIds: List<Int>,
    ): Set<Int>? {
        if (cells.any { !board.grid.inBounds(it) }) return null
        val ids = cells.mapNotNull { board.grid.tileAt(it) }.toSet()
        if (ids.size != liftSize) return null
        val group = locks.members(ids.first(), allIds)
        if (group.size != liftSize) return null
        val slots = group.mapNotNull { board.grid.slotOfOrNull(it) }.toSet()
        return if (slots == cells) group else null
    }

    private fun dumpBrief(board: PuzzleBoard): String = buildString {
        appendLine("locks=${board.manifest.tiles.map { it.id }.distinct().map { board.componentContaining(it) }.toSet()}")
        for (r in 0 until board.rows) {
            val line = (0 until board.cols).joinToString(" ") { c ->
                val id = board.tileAt(GridPos(r, c))
                if (id == null) "." else ('A' + id).toString()
            }
            appendLine(line)
        }
    }
}
