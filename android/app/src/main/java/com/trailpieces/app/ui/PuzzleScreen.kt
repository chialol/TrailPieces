package com.trailpieces.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trailpieces.app.puzzle.PuzzleLoader
import com.trailpieces.app.puzzle.PuzzleManifest
import com.trailpieces.app.puzzle.SlidingPuzzleState

@Composable
fun PuzzleScreen(
    manifest: PuzzleManifest,
    modifier: Modifier = Modifier,
) {
    var state by remember(manifest) { mutableStateOf(SlidingPuzzleState.shuffled(manifest)) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = manifest.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (state.isSolved) "Solved!" else "Tap a tile next to the gap to slide it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        val aspectRatio = manifest.puzzleWidth.toFloat() / manifest.puzzleHeight.toFloat()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
        ) {
            for (row in 0 until manifest.rows) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    for (col in 0 until manifest.cols) {
                        val cellIndex = row * manifest.cols + col
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            PuzzleCell(
                                cellIndex = cellIndex,
                                state = state,
                                manifest = manifest,
                                context = context,
                                onMove = { state = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PuzzleCell(
    cellIndex: Int,
    state: SlidingPuzzleState,
    manifest: PuzzleManifest,
    context: android.content.Context,
    onMove: (SlidingPuzzleState) -> Unit,
) {
    val tileIndex = state.tileAt(cellIndex)
    if (tileIndex == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        val tile = manifest.tiles.first { it.index == tileIndex }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(PuzzleLoader.assetUri(tile.assetPath))
                .crossfade(true)
                .build(),
            contentDescription = "Puzzle tile ${tile.row}, ${tile.col}",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    state.move(cellIndex)?.let(onMove)
                },
        )
    }
}

@Composable
fun MissingPuzzleScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Trail Pieces",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "No puzzle loaded yet.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "1. Drop a portrait photo in shared/source/\n" +
                "2. Run tools/chop_puzzle/chop.py\n" +
                "3. Rebuild and run the app",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
