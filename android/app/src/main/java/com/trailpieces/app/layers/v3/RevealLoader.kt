package com.trailpieces.app.layers.v3

import android.content.Context
import com.trailpieces.app.layers.LayerBBox
import org.json.JSONObject

object RevealLoader {
    fun loadDefault(context: Context): RevealScene? {
        val ids = context.assets.list("reveal").orEmpty().sorted()
        return ids.firstNotNullOfOrNull { id -> load(context, id) }
    }

    fun load(context: Context, sceneId: String): RevealScene? {
        return runCatching {
            val path = "reveal/$sceneId/scene.json"
            context.assets.open(path).bufferedReader().use { reader ->
                parse(reader.readText())
            }
        }.getOrNull()
    }

    fun parse(json: String): RevealScene {
        val root = JSONObject(json)
        val piecesJson = root.getJSONArray("pieces")
        val pieces = buildList {
            for (i in 0 until piecesJson.length()) {
                val piece = piecesJson.getJSONObject(i)
                val bbox = piece.getJSONObject("bbox")
                val id = piece.getInt("id")
                add(
                    RevealPiece(
                        id = id,
                        order = piece.optInt("order", id),
                        label = piece.optString("label", "").ifBlank { null },
                        file = piece.getString("file"),
                        trayFile = piece.getString("trayFile"),
                        bbox = LayerBBox(
                            left = bbox.getInt("left"),
                            top = bbox.getInt("top"),
                            right = bbox.getInt("right"),
                            bottom = bbox.getInt("bottom"),
                        ),
                    ),
                )
            }
        }
        return RevealScene(
            id = root.getString("id"),
            title = root.getString("title"),
            width = root.getInt("width"),
            height = root.getInt("height"),
            plateFile = root.getString("plateFile"),
            aliveFile = root.getString("aliveFile"),
            pieces = pieces,
        ).also { scene ->
            require(scene.pieces.isNotEmpty()) { "Scene has no pieces" }
            require(scene.width > 0 && scene.height > 0) { "Invalid canvas size" }
            require(scene.pieces.map { it.id }.distinct().size == scene.pieces.size) {
                "Duplicate piece ids"
            }
        }
    }
}
