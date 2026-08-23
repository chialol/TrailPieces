package com.trailpieces.app.puzzle

import android.content.Context
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
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
                        id = tile.getInt("index"),
                        home = GridPos(
                            row = tile.getInt("row"),
                            col = tile.getInt("col"),
                        ),
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
            puzzleWidth = root.getInt("puzzleWidth"),
            puzzleHeight = root.getInt("puzzleHeight"),
            tiles = tiles,
            previewFile = root.optString("previewFile").takeIf { it.isNotEmpty() },
        ).also { manifest ->
            require(manifest.cols > 0 && manifest.rows > 0) { "Invalid grid size" }
            require(manifest.tiles.isNotEmpty()) { "Puzzle has no tiles" }
            require(manifest.tiles.size <= manifest.slotCount) { "Too many tiles for grid" }
        }
    }
}
