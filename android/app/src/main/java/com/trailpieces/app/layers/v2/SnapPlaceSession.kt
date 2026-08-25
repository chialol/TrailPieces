package com.trailpieces.app.layers.v2

import androidx.compose.ui.geometry.Offset
import com.trailpieces.app.layers.LayerDef
import com.trailpieces.app.layers.LayersManifest
import kotlin.math.hypot

data class ActiveDrag(
    val layerId: Int,
    /** Finger minus piece top-left in root space. */
    val grabOffset: Offset,
)

enum class SnapResult {
    Snapped,
    ReturnedToTray,
}

/**
 * Snap-place v2 — piece homes at its bbox origin on the scene canvas.
 * Full aligned layer is revealed once snapped; no scene preview background.
 */
class SnapPlaceSession(
    val manifest: LayersManifest,
) {
    private val _placed = linkedSetOf<Int>()
    val placed: Set<Int> get() = _placed

    var dragging: ActiveDrag? = null
        private set

    val isComplete: Boolean
        get() = _placed.size == manifest.layers.size

    fun layer(id: Int): LayerDef =
        manifest.layers.first { it.id == id }

    fun startDrag(layerId: Int, grabOffset: Offset) {
        if (layerId in _placed) return
        dragging = ActiveDrag(layerId, grabOffset)
    }

    fun clearDrag() {
        dragging = null
    }

    /** Home top-left for the tray piece (bbox origin) in scene-local px. */
    fun homeTopLeftInScene(
        layer: LayerDef,
        sceneWidthPx: Float,
        sceneHeightPx: Float,
    ): Offset {
        val scaleX = sceneWidthPx / manifest.width
        val scaleY = sceneHeightPx / manifest.height
        return Offset(layer.bbox.left * scaleX, layer.bbox.top * scaleY)
    }

    fun traySizePx(
        layer: LayerDef,
        sceneWidthPx: Float,
        sceneHeightPx: Float,
    ): Pair<Float, Float> {
        val scaleX = sceneWidthPx / manifest.width
        val scaleY = sceneHeightPx / manifest.height
        val w = (layer.bbox.right - layer.bbox.left) * scaleX
        val h = (layer.bbox.bottom - layer.bbox.top) * scaleY
        return w to h
    }

    fun trySnap(
        pieceTopLeftInRoot: Offset,
        sceneTopLeftInRoot: Offset,
        sceneWidthPx: Float,
        sceneHeightPx: Float,
        snapThresholdPx: Float,
    ): SnapResult {
        val drag = dragging ?: return SnapResult.ReturnedToTray
        val layer = layer(drag.layerId)
        val home = sceneTopLeftInRoot + homeTopLeftInScene(layer, sceneWidthPx, sceneHeightPx)
        val dist = hypot(
            pieceTopLeftInRoot.x - home.x,
            pieceTopLeftInRoot.y - home.y,
        )
        dragging = null
        return if (dist <= snapThresholdPx) {
            _placed.add(drag.layerId)
            SnapResult.Snapped
        } else {
            SnapResult.ReturnedToTray
        }
    }

    fun reset() {
        _placed.clear()
        dragging = null
    }
}
