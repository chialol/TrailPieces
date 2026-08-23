package com.trailpieces.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.trailpieces.app.puzzle.PuzzleLoader
import com.trailpieces.app.ui.MissingPuzzleScreen
import com.trailpieces.app.ui.PuzzleScreen
import com.trailpieces.puzzle.demo.CascadeDemo
import com.trailpieces.puzzle.service.PuzzleBoard

@Composable
fun TrailPiecesApp() {
    val context = LocalContext.current
    val defaultManifest = PuzzleLoader.loadDefault(context)
    val useCascadeDemo = CascadeDemo.ENABLED && defaultManifest != null
    val manifest = if (useCascadeDemo) {
        CascadeDemo.manifest.copy(
            tiles = CascadeDemo.manifest.tiles.map { tile ->
                tile.copy(assetPath = defaultManifest!!.tiles[tile.id.coerceAtMost(defaultManifest.tiles.lastIndex)].assetPath)
            },
        )
    } else {
        defaultManifest
    }
    val initialBoard: PuzzleBoard? = if (useCascadeDemo) {
        CascadeDemo.board(defaultManifest)
    } else {
        null
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (manifest == null) {
            MissingPuzzleScreen(modifier = Modifier.padding(innerPadding))
        } else {
            PuzzleScreen(
                manifest = manifest,
                initialBoard = initialBoard,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
