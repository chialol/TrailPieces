package com.trailpieces.app.ui

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trailpieces.puzzle.model.GridPos
import com.trailpieces.app.puzzle.PuzzleGame
import com.trailpieces.app.puzzle.PuzzleLoader
import com.trailpieces.puzzle.model.PuzzleManifest
import com.trailpieces.puzzle.model.PuzzleTile
import com.trailpieces.puzzle.service.PuzzleBoard
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "PuzzleScreen"

private val OffsetVectorConverter = TwoWayConverter<Offset, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.x, it.y) },
    convertFromVector = { Offset(it.v1, it.v2) },
)

@Composable
fun PuzzleScreen(
    manifest: PuzzleManifest,
    initialBoard: PuzzleBoard? = null,
    modifier: Modifier = Modifier,
) {
    var shuffleSeed by remember(manifest) { mutableIntStateOf(0) }
    val game = remember(manifest, shuffleSeed, initialBoard) {
        if (initialBoard != null) PuzzleGame(manifest, initialBoard) else PuzzleGame(manifest)
    }
    var drawEpoch by remember { mutableIntStateOf(0) }
    val snapAnim = remember { Animatable(Offset.Zero, OffsetVectorConverter) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    fun bumpDraw() {
        drawEpoch++
    }

    LaunchedEffect(manifest, shuffleSeed) {
        Log.d(TAG, "board ready isSolved=${game.isSolved}")
    }

    val lockedCount = game.board.manifest.tiles.maxOfOrNull { game.lockedGroupSize(it.id) } ?: 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = manifest.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = when {
                game.isSolved -> "Solved!"
                lockedCount > 1 -> "Locked groups up to $lockedCount tiles — drag to slide and push."
                else -> "Drag tiles — they push neighbors and snap into place."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val playfieldRows = maxOf(game.board.rows, game.drag?.grid?.rows ?: 0, manifest.rows)
            val boardWidth = maxWidth
            val boardHeight = boardWidth * manifest.puzzleHeight / manifest.puzzleWidth *
                playfieldRows / manifest.rows
            val cellWidth = boardWidth / manifest.cols
            val cellHeight = boardHeight / playfieldRows
            val cellWidthPx = with(density) { cellWidth.toPx() }
            val cellHeightPx = with(density) { cellHeight.toPx() }

            Box(
                modifier = Modifier
                    .size(boardWidth, boardHeight)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                if (!game.isSolved) {
                    PuzzleTileLayer(
                        game = game,
                        drawEpoch = drawEpoch,
                        manifest = manifest,
                        context = context,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        snapAnim = snapAnim,
                    )
                    PuzzleGestureLayer(
                        game = game,
                        manifest = manifest,
                        playfieldRows = playfieldRows,
                        cellWidthPx = cellWidthPx,
                        cellHeightPx = cellHeightPx,
                        snapAnim = snapAnim,
                        onDraw = ::bumpDraw,
                        scope = scope,
                    )
                } else {
                    SolvedView(
                        manifest = manifest,
                        context = context,
                        boardWidth = boardWidth,
                        boardHeight = boardHeight,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        game = game,
                    )
                }
            }
        }

        if (game.isSolved) {
            Button(
                onClick = {
                    game.reshuffle()
                    bumpDraw()
                },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text("Play again")
            }
        }
    }
}

@Composable
private fun PuzzleTileLayer(
    game: PuzzleGame,
    drawEpoch: Int,
    manifest: PuzzleManifest,
    context: android.content.Context,
    cellWidth: Dp,
    cellHeight: Dp,
    snapAnim: Animatable<Offset, AnimationVector2D>,
) {
    @Suppress("UNUSED_VARIABLE")
    val redraw = drawEpoch

    val drag = game.drag
    val restingGrid = drag?.grid ?: game.board.grid
    val visualOffset = game.visualOffsetPx() + snapAnim.value
    val rows = restingGrid.rows
    val cols = restingGrid.cols

    Box(modifier = Modifier.fillMaxSize()) {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val pos = GridPos(row, col)
                val tileId = restingGrid.tileAt(pos) ?: continue
                if (drag?.liftedTileIds?.contains(tileId) == true) continue

                val tile = manifest.tileOrNull(tileId) ?: continue
                val groupSize = game.lockedGroupSize(tileId)
                TileImage(
                    tile = tile,
                    context = context,
                    modifier = Modifier
                        .tileAt(pos, cellWidth, cellHeight, Offset.Zero)
                        .then(if (groupSize > 1) Modifier.lockedTileStyle() else Modifier),
                )
            }
        }

        drag?.let { active ->
            val isGroup = active.liftedTileIds.size > 1
            active.liftedTileIds.forEach { tileId ->
                val offset = active.shapeOffsets[tileId] ?: return@forEach
                val startSlot = active.startAnchor.offset(offset.row, offset.col)
                val tile = manifest.tileOrNull(tileId) ?: return@forEach
                TileImage(
                    tile = tile,
                    context = context,
                    modifier = Modifier
                        .zIndex(1f)
                        .tileAt(startSlot, cellWidth, cellHeight, visualOffset)
                        .then(
                            if (isGroup) {
                                Modifier
                            } else {
                                Modifier
                                    .shadow(8.dp, RoundedCornerShape(2.dp))
                                    .graphicsLayer {
                                        scaleX = 1.008f
                                        scaleY = 1.008f
                                    }
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun PuzzleGestureLayer(
    game: PuzzleGame,
    manifest: PuzzleManifest,
    playfieldRows: Int,
    cellWidthPx: Float,
    cellHeightPx: Float,
    snapAnim: Animatable<Offset, AnimationVector2D>,
    onDraw: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .pointerInput(manifest.cols, playfieldRows, cellWidthPx, cellHeightPx) {
                awaitEachGesture {
                    var dragActive = false
                    try {
                        if (!cellWidthPx.isFinite() || cellWidthPx <= 0f ||
                            !cellHeightPx.isFinite() || cellHeightPx <= 0f
                        ) {
                            return@awaitEachGesture
                        }

                        val down = awaitFirstDown(requireUnconsumed = false)
                        val col = (down.position.x / cellWidthPx).toInt()
                            .coerceIn(0, manifest.cols - 1)
                        // Use playfield height — not manifest.rows — or taps on
                        // inserted/bottom rows clamp to the last solved row and
                        // look like "bottom tiles aren't draggable".
                        val row = (down.position.y / cellHeightPx).toInt()
                            .coerceIn(0, playfieldRows - 1)
                        Log.d(TAG, "pointer down at ($row,$col) px=${down.position}")

                        if (!game.startDrag(GridPos(row, col))) return@awaitEachGesture
                        dragActive = true
                        onDraw()
                        scope.launch { snapAnim.snapTo(Offset.Zero) }

                        val pointerId = down.id
                        // Loop until this pointer lifts — do not cap event count.
                        // A low cap ends the drag mid-gesture (looks like a random snap).
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break

                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                game.moveFinger(delta, cellWidthPx, cellHeightPx)
                                onDraw()
                            }
                            change.consume()
                        }

                        Log.d(TAG, "pointer up finger=${game.visualOffsetPx()}")
                        scope.launch { snapAnim.snapTo(Offset.Zero) }
                        game.clearFingerDelta()
                        game.endDrag()
                        dragActive = false
                        onDraw()
                    } catch (e: Exception) {
                        Log.e(TAG, "gesture failed — cancelling drag", e)
                    } finally {
                        if (dragActive) {
                            game.cancelDragSafely()
                            scope.launch { snapAnim.snapTo(Offset.Zero) }
                            onDraw()
                        }
                    }
                }
            },
    )
}

@Composable
private fun SolvedView(
    manifest: PuzzleManifest,
    context: android.content.Context,
    boardWidth: Dp,
    boardHeight: Dp,
    cellWidth: Dp,
    cellHeight: Dp,
    game: PuzzleGame,
) {
    val preview = manifest.previewFile
    if (preview != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(PuzzleLoader.assetUri(preview))
                .crossfade(true)
                .build(),
            contentDescription = "Completed ${manifest.title}",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            for (row in 0 until manifest.rows) {
                for (col in 0 until manifest.cols) {
                    val pos = GridPos(row, col)
                    val tileId = game.board.tileAt(pos) ?: continue
                    val tile = manifest.tileOrNull(tileId) ?: continue
                    TileImage(
                        tile = tile,
                        context = context,
                        modifier = Modifier.tileAt(pos, cellWidth, cellHeight, Offset.Zero),
                    )
                }
            }
        }
    }
}

@Composable
private fun TileImage(
    tile: PuzzleTile,
    context: android.content.Context,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(PuzzleLoader.assetUri(tile.assetPath))
            .crossfade(false)
            .build(),
        contentDescription = "Tile (r${tile.home.row}, c${tile.home.col})",
        contentScale = ContentScale.FillBounds,
        modifier = modifier,
    )
}

@Composable
private fun Modifier.tileAt(
    pos: GridPos,
    cellWidth: Dp,
    cellHeight: Dp,
    offset: Offset,
): Modifier {
    val density = LocalDensity.current
    val x = pos.col * with(density) { cellWidth.toPx() } + offset.x
    val y = pos.row * with(density) { cellHeight.toPx() } + offset.y
    return this
        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
        .size(cellWidth, cellHeight)
}

private fun Modifier.lockedTileStyle(): Modifier = graphicsLayer {
    shadowElevation = 2f
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
