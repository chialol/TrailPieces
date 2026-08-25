package com.trailpieces.puzzle.service

import com.trailpieces.puzzle.model.GridPos

/** Shared grid math for motion and settle layers. */
internal object GridGeometry {

    fun translation(from: Set<GridPos>, to: Set<GridPos>): Pair<Int, Int>? {
        if (from.size != to.size || from.isEmpty()) return null
        val fa = from.minWith(compareBy({ it.row }, { it.col }))
        val ta = to.minWith(compareBy({ it.row }, { it.col }))
        val dRow = ta.row - fa.row
        val dCol = ta.col - fa.col
        val mapped = from.map { it.offset(dRow, dCol) }.toSet()
        return if (mapped == to) dRow to dCol else null
    }

    fun landingAnchor(shapeOffsets: Map<Int, GridPos>, landing: Set<GridPos>): GridPos {
        val minOff = shapeOffsets.values.minWith(compareBy({ it.row }, { it.col }))
        val minLanding = landing.minWith(compareBy({ it.row }, { it.col }))
        return minLanding.offset(-minOff.row, -minOff.col)
    }

    fun anchorForHoles(holes: Set<GridPos>): GridPos? {
        if (holes.isEmpty()) return null
        return holes.minWith(compareBy({ it.row }, { it.col }))
    }
}
