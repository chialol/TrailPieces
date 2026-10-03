package com.trailpieces.app.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

@Composable
fun rememberLayerAssetRequest(sceneId: String, path: String): ImageRequest =
    rememberBundledAssetRequest("layers/$sceneId/$path")

@Composable
fun rememberBundledAssetRequest(assetPath: String): ImageRequest {
    val context = LocalContext.current
    return remember(assetPath) {
        val bytes = runCatching {
            context.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull()
        ImageRequest.Builder(context)
            .data(bytes ?: "file:///android_asset/$assetPath")
            .memoryCacheKey(assetPath)
            .diskCacheKey(assetPath)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }
}
