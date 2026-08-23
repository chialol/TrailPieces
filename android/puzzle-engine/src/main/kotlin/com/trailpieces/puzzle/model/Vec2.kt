package com.trailpieces.puzzle.model

/** Screen-agnostic 2D vector for finger tracking in the game engine. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    companion object {
        val Zero = Vec2(0f, 0f)
    }
}
