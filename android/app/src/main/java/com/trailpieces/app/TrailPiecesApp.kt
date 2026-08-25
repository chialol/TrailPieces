package com.trailpieces.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.trailpieces.app.layers.LayersScreen
import com.trailpieces.app.puzzle.PuzzleLoader
import com.trailpieces.app.ui.HomeMenuScreen
import com.trailpieces.app.ui.MissingPuzzleScreen
import com.trailpieces.app.ui.PuzzleScreen
import com.trailpieces.puzzle.demo.CascadeDemo
import com.trailpieces.puzzle.service.PuzzleBoard

private enum class AppDestination {
    Menu,
    TrailPuzzle,
    LayersConcept,
}

@Composable
fun TrailPiecesApp() {
    val context = LocalContext.current
    var destination by rememberSaveable { mutableStateOf(AppDestination.Menu) }

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
        val contentModifier = Modifier.padding(innerPadding)
        when (destination) {
            AppDestination.Menu -> {
                HomeMenuScreen(
                    onTrailPuzzle = { destination = AppDestination.TrailPuzzle },
                    onLayersConcept = { destination = AppDestination.LayersConcept },
                    modifier = contentModifier,
                )
            }
            AppDestination.TrailPuzzle -> {
                if (manifest == null) {
                    MissingPuzzleScreen(
                        onBack = { destination = AppDestination.Menu },
                        modifier = contentModifier,
                    )
                } else {
                    PuzzleScreen(
                        manifest = manifest,
                        initialBoard = initialBoard,
                        onBack = { destination = AppDestination.Menu },
                        modifier = contentModifier,
                    )
                }
            }
            AppDestination.LayersConcept -> {
                LayersScreen(
                    onBack = { destination = AppDestination.Menu },
                    modifier = contentModifier,
                )
            }
        }
    }
}
