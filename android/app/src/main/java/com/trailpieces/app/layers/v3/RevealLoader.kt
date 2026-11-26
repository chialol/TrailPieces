package com.trailpieces.app.layers.v3

import android.content.Context
import org.json.JSONObject

object RevealLoader {
    fun loadAll(context: Context): List<RevealScene> {
        val assetIds = context.assets.list("reveal").orEmpty().toSet()
        val remoteRoot = java.io.File(context.filesDir, "remote/scenes")
        val remoteIds = remoteRoot.list()?.filter { id ->
            id !in assetIds && java.io.File(remoteRoot, "$id/scene.json").isFile
        }.orEmpty()
        return (assetIds + remoteIds).sorted().mapNotNull { id -> load(context, id) }
    }

    fun load(context: Context, sceneId: String): RevealScene? {
        loadAsset(context, sceneId)?.let { return it }
        val file = java.io.File(context.filesDir, "remote/scenes/$sceneId/scene.json")
        if (!file.isFile) return null
        return runCatching {
            parse(file.readText()).copy(id = sceneId, directory = file.parentFile)
        }.getOrNull()
    }

    private fun loadAsset(context: Context, sceneId: String): RevealScene? {
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
                        cropFile = piece.getString("cropFile"),
                        liftFile = piece.getString("liftFile"),
                        iconFile = piece.getString("iconFile"),
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
            coverFile = root.optString("coverFile", root.getString("aliveFile")),
            liftPad = root.optInt("liftPad", 0),
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
