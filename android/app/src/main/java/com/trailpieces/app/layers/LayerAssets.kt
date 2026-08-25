package com.trailpieces.app.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

@Composable
fun rememberLayerAssetRequest(sceneId: String, path: String): ImageRequest {
    val context = LocalContext.current
    return remember(sceneId, path) {
        val assetPath = "layers/$sceneId/$path"
        val bytes = runCatching {
            context.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull()
        ImageRequest.Builder(context)
            .data(bytes ?: LayersLoader.assetUri(sceneId, path))
            .memoryCacheKey(assetPath)
            .diskCacheKey(assetPath)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }
}
