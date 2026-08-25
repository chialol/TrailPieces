package com.trailpieces.app.layers

data class LayerBBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class LayerDef(
    val id: Int,
    val file: String,
    val trayFile: String,
    val bbox: LayerBBox,
    val coverage: Float,
)

data class LayersManifest(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val previewFile: String,
    val mutedTraySaturation: Float,
    val layers: List<LayerDef>,
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}
