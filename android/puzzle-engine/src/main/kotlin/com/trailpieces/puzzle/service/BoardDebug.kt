package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest

/**
 * Human-readable board dump for logcat / bug reports.
 * Letters are home-order (solved row-major A, B, C…); `.` is empty.
 */
object BoardDebug {

    fun dump(
        board: PuzzleBoard,
        drag: DragSession? = null,
        extra: Map<String, Any?> = emptyMap(),
        trace: DragTrace? = null,
    ): String = buildString {
        val man = board.manifest
        val letterById = homeOrderLetters(man)

        appendLine("=== Trail Pieces debug ===")
        appendLine("puzzle=${man.id} manifest=${man.cols}x${man.rows} playfield=${board.cols}x${board.rows}")
        if (extra.isNotEmpty()) {
            appendLine(extra.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
        appendLine()

        appendLine("grid (home-letter of tile in each slot; . = empty):")
        append(formatGrid(board.grid.cols, board.grid.rows) { pos ->
            board.grid.tileAt(pos)?.let { letterById[it] ?: "?$it" } ?: "."
        })
        appendLine()

        appendLine("slots → tile (id, home):")
        for (r in 0 until board.rows) {
            for (c in 0 until board.cols) {
                val pos = GridPos(r, c)
                val id = board.grid.tileAt(pos)
                if (id == null) {
                    appendLine("  ${slotLabel(pos, board.cols)} ($r,$c) = .")
                } else {
                    val home = man.tileOrNull(id)?.home
                    val letter = letterById[id] ?: "?$id"
                    appendLine(
                        "  ${slotLabel(pos, board.cols)} ($r,$c) = $letter id=$id home=$home",
                    )
                }
            }
        }
        appendLine()

        appendLine("lock groups (connected components):")
        val seen = mutableSetOf<Int>()
        for (id in man.tiles.map { it.id }.sorted()) {
            if (id in seen) continue
            if (board.grid.slotOfOrNull(id) == null) continue
            val group = board.componentContaining(id).sorted()
            seen += group
            val letters = group.joinToString("") { letterById[it] ?: "?$it" }
            val slots = group.joinToString(",") { tid ->
                val s = board.grid.slotOfOrNull(tid)
                s?.let { "${letterById[tid]}@${slotLabel(it, board.cols)}" } ?: "?"
            }
            appendLine("  {$letters} ids=$group slots=[$slots]")
        }

        if (drag != null) {
            appendLine()
            appendLine("active drag:")
            appendLine(
                "  lifted=${drag.liftedTileIds.sorted().joinToString("") { letterById[it] ?: "?$it" }}" +
                    " ids=${drag.liftedTileIds.sorted()}",
            )
            appendLine("  start=${drag.startAnchor} committed=${drag.committedAnchor}")
            appendLine("  holes=${drag.targetSlots.sortedWith(compareBy({ it.row }, { it.col }))}")
            appendLine("  dragGrid ${drag.grid.cols}x${drag.grid.rows}:")
            append(
                formatGrid(drag.grid.cols, drag.grid.rows) { pos ->
                    when {
                        pos in drag.targetSlots -> "_"
                        else -> drag.grid.tileAt(pos)?.let { letterById[it] ?: "?$it" } ?: "."
                    }
                },
            )
        } else {
            appendLine()
            appendLine("active drag: none")
        }

        if (trace != null && !trace.isEmpty()) {
            appendLine()
            appendLine("drag trace (oldest → newest):")
            append(trace.format())
        }

        append("=== end debug ===")
    }

    /** Compact home-letter grid for [DragTrace] snapshots. */
    fun gridBrief(
        grid: com.trailpieces.puzzle.model.SlotGrid,
        manifest: PuzzleManifest,
        holes: Set<GridPos> = emptySet(),
    ): String {
        val letterById = homeOrderLetters(manifest)
        return formatGrid(grid.cols, grid.rows) { pos ->
            when {
                pos in holes -> "_"
                else -> grid.tileAt(pos)?.let { letterById[it] ?: "?$it" } ?: "."
            }
        }.trimEnd()
    }

    /** A, B, C… by solved home row-major order. */
    fun homeOrderLetters(manifest: PuzzleManifest): Map<Int, String> {
        val ordered = manifest.tiles.sortedWith(
            compareBy({ it.home.row }, { it.home.col }, { it.id }),
        )
        return ordered.mapIndexed { index, tile ->
            tile.id to indexToLetter(index)
        }.toMap()
    }

    private fun indexToLetter(index: Int): String {
        if (index < 26) return ('A' + index).toString()
        return "T$index"
    }

    /** Playfield slot letter for small boards (A=0,0 …); falls back to r,c. */
    fun slotLabel(pos: GridPos, cols: Int): String {
        val index = pos.row * cols + pos.col
        return if (index in 0 until 26) ('A' + index).toString() else "($pos)"
    }

    private fun formatGrid(cols: Int, rows: Int, cell: (GridPos) -> String): String =
        buildString {
            for (r in 0 until rows) {
                append("  ")
                for (c in 0 until cols) {
                    if (c > 0) append(' ')
                    append(cell(GridPos(r, c)))
                }
                appendLine()
            }
        }
}
