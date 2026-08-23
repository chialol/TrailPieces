package com.trailpieces.app.puzzle

import android.content.Context
import org.json.JSONObject

object PuzzleLoader {
    private const val MANIFEST_PATH = "puzzles/default/puzzle.json"

    fun loadDefault(context: Context): PuzzleManifest? {
        return runCatching {
            context.assets.open(MANIFEST_PATH).bufferedReader().use { reader ->
                parseManifest(reader.readText())
            }
        }.getOrNull()
    }

    fun assetUri(path: String): String = "file:///android_asset/puzzles/default/$path"

    private fun parseManifest(json: String): PuzzleManifest {
        val root = JSONObject(json)
        val tilesJson = root.getJSONArray("tiles")
        val tiles = buildList {
            for (i in 0 until tilesJson.length()) {
                val tile = tilesJson.getJSONObject(i)
                add(
                    PuzzleTile(
                        index = tile.getInt("index"),
                        row = tile.getInt("row"),
                        col = tile.getInt("col"),
                        assetPath = tile.getString("file"),
                    ),
                )
            }
        }
        return PuzzleManifest(
            id = root.getString("id"),
            title = root.getString("title"),
            cols = root.getInt("cols"),
            rows = root.getInt("rows"),
            tileWidth = root.getInt("tileWidth"),
            tileHeight = root.getInt("tileHeight"),
            puzzleWidth = root.getInt("puzzleWidth"),
            puzzleHeight = root.getInt("puzzleHeight"),
            emptyIndex = root.getInt("emptyIndex"),
            tiles = tiles,
        )
    }
}
