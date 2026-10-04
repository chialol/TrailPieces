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
    val coverFile: String,
    /** Transparent margin, in scene pixels, around each lift image for its outline and shadow. */
    val liftPad: Int,
    val pieces: List<RevealPiece>,
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
    val isLandscape: Boolean get() = width >= height
}

data class RevealPiece(
    val id: Int,
    /** Play order. Lower numbers are offered first. */
    val order: Int,
    /** Short name such as "meadow", or null when the file was only numbered. */
    val label: String?,
    /** Tight crop, drawn at [bbox] once the piece locks. */
    val cropFile: String,
    /** [cropFile] padded by [RevealScene.liftPad] with an outline and shadow, drawn while dragging. */
    val liftFile: String,
    /** Small copy of [liftFile] for the waiting row. */
    val iconFile: String,
    val bbox: LayerBBox,
)

enum class PlaceOutcome {
    Locked,
    Missed,
    Ignored,
}
