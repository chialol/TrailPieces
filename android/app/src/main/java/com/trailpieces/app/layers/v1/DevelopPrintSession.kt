package com.trailpieces.app.layers.v1

import androidx.compose.ui.geometry.Offset
import com.trailpieces.app.layers.LayerDef
import com.trailpieces.app.layers.LayersManifest

data class DraggingLayer(
    val layerId: Int,
    val grabInChip: Offset,
)

enum class LayerCommitKind {
    Placed,
    ReturnedToTray,
}

/**
 * Develop-print v1 — pure session state (no Compose).
 * Finger parks a chip; develop animation is owned by the UI.
 */
class DevelopPrintSession(
    val manifest: LayersManifest,
) {
    private val _placed = linkedSetOf<Int>()
    val placed: Set<Int> get() = _placed

    var dragging: DraggingLayer? = null
        private set

    val isComplete: Boolean
        get() = _placed.size == manifest.layers.size

    fun layer(id: Int): LayerDef =
        manifest.layers.first { it.id == id }

    fun startDrag(layerId: Int, grabInChip: Offset) {
        if (layerId in _placed) return
        dragging = DraggingLayer(layerId, grabInChip)
    }

    fun clearDrag() {
        dragging = null
    }

    fun commitIfOverCanvas(overCanvas: Boolean): LayerCommitKind {
        val drag = dragging ?: return LayerCommitKind.ReturnedToTray
        dragging = null
        return if (overCanvas) {
            _placed.add(drag.layerId)
            LayerCommitKind.Placed
        } else {
            LayerCommitKind.ReturnedToTray
        }
    }

    fun reset() {
        _placed.clear()
        dragging = null
    }
}
