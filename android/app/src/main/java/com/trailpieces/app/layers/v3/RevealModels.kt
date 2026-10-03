package com.trailpieces.app.layers.v3

import com.trailpieces.app.layers.LayerBBox

/**
 * A reveal scene: a dark plate the player pans, color pieces that lock onto it,
 * and a full-color photo that fades in once every piece is home.
 *
 * [width] and [height] are the downsized canvas, not the high-res source.
 */
data class RevealScene(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val plateFile: String,
    val aliveFile: String,
    val pieces: List<RevealPiece>,
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}

data class RevealPiece(
    val id: Int,
    /** Play order. Lower numbers are offered first. */
    val order: Int,
    /** Short name such as "meadow", or null when the file was only numbered. */
    val label: String?,
    /** Full-canvas RGBA, aligned with [RevealScene] so it stacks on the plate. */
    val file: String,
    /** Tight crop the player drags. */
    val trayFile: String,
    val bbox: LayerBBox,
)

enum class PlaceOutcome {
    Locked,
    Missed,
    Ignored,
}
