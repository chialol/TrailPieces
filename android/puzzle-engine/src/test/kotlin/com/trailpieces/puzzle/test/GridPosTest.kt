package com.trailpieces.puzzle.test

import com.trailpieces.puzzle.model.AxisDirection
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.Vec2
import com.trailpieces.puzzle.model.step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GridPosTest {

    @Test
    fun indexRoundTrip() {
        val cols = 2
        for (row in 0 until 3) {
            for (col in 0 until cols) {
                val pos = GridPos(row, col)
                assertEquals(pos, GridPos.fromIndex(pos.index(cols), cols))
            }
        }
    }

    @Test
    fun offsetAndStep() {
        val origin = GridPos(1, 1)
        assertEquals(GridPos(2, 1), origin.step(AxisDirection.Down))
        assertEquals(GridPos(0, 1), origin.step(AxisDirection.Up))
        assertEquals(GridPos(1, 2), origin.step(AxisDirection.Right))
        assertEquals(GridPos(1, 0), origin.step(AxisDirection.Left))
        assertEquals(GridPos(3, 0), origin.offset(2, -1))
    }

    @Test
    fun dominantPrefersVerticalOnTie() {
        assertEquals(AxisDirection.Down, AxisDirection.dominant(10f, 10f))
        assertEquals(AxisDirection.Up, AxisDirection.dominant(-10f, 10f))
        assertEquals(AxisDirection.Right, AxisDirection.dominant(5f, 10f))
        assertEquals(AxisDirection.Left, AxisDirection.dominant(5f, -10f))
        assertNull(AxisDirection.dominant(0f, 0f))
    }

    @Test
    fun oppositeDirections() {
        assertEquals(AxisDirection.Up, AxisDirection.opposite(AxisDirection.Down))
        assertEquals(AxisDirection.Down, AxisDirection.opposite(AxisDirection.Up))
        assertEquals(AxisDirection.Left, AxisDirection.opposite(AxisDirection.Right))
        assertEquals(AxisDirection.Right, AxisDirection.opposite(AxisDirection.Left))
    }

    @Test
    fun vec2Arithmetic() {
        val a = Vec2(3f, 4f)
        val b = Vec2(1f, 2f)
        assertEquals(Vec2(4f, 6f), a + b)
        assertEquals(Vec2(2f, 2f), a - b)
        assertEquals(Vec2.Zero, Vec2(0f, 0f))
    }
}
