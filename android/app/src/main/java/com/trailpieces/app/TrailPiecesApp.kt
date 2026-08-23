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

@Composable
fun TrailPiecesApp() {
    val context = LocalContext.current
    val manifest = PuzzleLoader.loadDefault(context)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (manifest == null) {
            MissingPuzzleScreen(modifier = Modifier.padding(innerPadding))
        } else {
            PuzzleScreen(
                manifest = manifest,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
