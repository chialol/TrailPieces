package com.trailpieces.app.layers

import android.content.Context
import org.json.JSONObject

object LayersLoader {
    private const val DEFAULT_ID = "deathvalley"

    fun loadDefault(context: Context): LayersManifest? = load(context, DEFAULT_ID)

    fun load(context: Context, sceneId: String): LayersManifest? {
        return runCatching {
            val path = "layers/$sceneId/layers.json"
            context.assets.open(path).bufferedReader().use { reader ->
                parseManifest(reader.readText())
            }
        }.getOrNull()
    }

    fun assetUri(sceneId: String, relativePath: String): String =
        "file:///android_asset/layers/$sceneId/$relativePath"

    private fun parseManifest(json: String): LayersManifest {
        val root = JSONObject(json)
        val layersJson = root.getJSONArray("layers")
        val layers = buildList {
            for (i in 0 until layersJson.length()) {
                val layer = layersJson.getJSONObject(i)
                val bbox = layer.getJSONObject("bbox")
                add(
                    LayerDef(
                        id = layer.getInt("id"),
                        file = layer.getString("file"),
                        trayFile = layer.getString("trayFile"),
                        bbox = LayerBBox(
                            left = bbox.getInt("left"),
                            top = bbox.getInt("top"),
                            right = bbox.getInt("right"),
                            bottom = bbox.getInt("bottom"),
                        ),
                        coverage = layer.optDouble("coverage", 0.0).toFloat(),
                    ),
                )
            }
        }
        return LayersManifest(
            id = root.getString("id"),
            title = root.getString("title"),
            width = root.getInt("width"),
            height = root.getInt("height"),
            previewFile = root.getString("previewFile"),
            mutedTraySaturation = root.optDouble("mutedTraySaturation", 0.35).toFloat(),
            layers = layers.sortedBy { it.id },
        ).also { manifest ->
            require(manifest.layers.isNotEmpty()) { "Scene has no layers" }
            require(manifest.width > 0 && manifest.height > 0) { "Invalid canvas size" }
        }
    }
}
