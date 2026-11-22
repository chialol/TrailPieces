package com.trailpieces.app.tutorial

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.trailpieces.app.layers.v3.PlaceOutcome
import com.trailpieces.app.layers.v3.RevealBitmaps
import com.trailpieces.app.layers.v3.RevealLoader
import com.trailpieces.app.layers.v3.RevealPiece
import com.trailpieces.app.layers.v3.RevealScene
import com.trailpieces.app.layers.v3.RevealSession
import com.trailpieces.app.layers.v3.StripGesture
import com.trailpieces.app.layers.v3.anchoredTopLeft
import com.trailpieces.app.layers.v3.classifyStripMove
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val Backdrop = Color(0xFF0D0E0C)
private val Hint = Color(0xFFE9E5DA)
private val SnapThreshold = 20.dp
private val IconHeight = 68.dp
private val IconMaxWidth = 140.dp

private class TutorialFrame {
    var imageTopLeft by mutableStateOf(Offset.Zero)
    var imageSize by mutableStateOf(Size.Zero)
    var chipCenter by mutableStateOf(Offset.Zero)
    var ghostCenter by mutableStateOf(Offset.Zero)
    var rootOrigin by mutableStateOf(Offset.Zero)
}

private data class TutorialAssets(
    val scene: RevealScene,
    val bitmaps: RevealBitmaps,
    val guided: RevealPiece,
    val home: TutorialRegion,
)

@Composable
fun TutorialScreen(
    canExit: Boolean,
    onExit: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = canExit, onBack = onExit)
    val context = LocalContext.current
    val load by produceState<TutorialLoad>(TutorialLoad.Loading, context) {
        value = runCatching { loadTutorial(context) }.fold(
            onSuccess = { TutorialLoad.Ready(it) },
            onFailure = { TutorialLoad.Missing },
        )
    }
    when (val ready = load) {
        TutorialLoad.Loading -> Box(modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Hint)
        }
        TutorialLoad.Missing -> Box(modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
            Text(
                text = "Tutorial scene is missing.",
                color = Hint,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        is TutorialLoad.Ready -> TutorialPlay(
            assets = ready.assets,
            canExit = canExit,
            onExit = onExit,
            onFinished = onFinished,
            modifier = modifier,
        )
    }
}

@Composable
private fun TutorialPlay(
    assets: TutorialAssets,
    canExit: Boolean,
    onExit: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scene = assets.scene
    val bitmaps = assets.bitmaps
    val guided = assets.guided
    val home = assets.home
    val session = remember(scene) { RevealSession(scene) }
    val coach = remember { TutorialCoach() }
    val frame = remember { TutorialFrame() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var panArmed by remember { mutableStateOf(false) }
    var panOrigin by remember { mutableIntStateOf(0) }
    var imageWidthPx by remember { mutableFloatStateOf(0f) }
    var viewW by remember { mutableFloatStateOf(0f) }
    var dragId by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val snapPx = with(density) { SnapThreshold.toPx() }

    LaunchedEffect(scroll.maxValue) {
        if (!panArmed && scroll.maxValue > 0) {
            scroll.scrollTo(scroll.maxValue / 2)
            panOrigin = scroll.value
            panArmed = true
        }
    }
    LaunchedEffect(panArmed) {
        if (!panArmed) return@LaunchedEffect
        snapshotFlow { scroll.value }.collect { value ->
            coach.notePan((value - panOrigin).toFloat())
        }
    }
    LaunchedEffect(imageWidthPx, viewW) {
        if (viewW > 0f && imageWidthPx > 0f && imageWidthPx <= viewW + 1f) {
            coach.skipPanWhenThePhotoFits()
        }
    }
    LaunchedEffect(coach.step) {
        if (coach.step != TutorialStep.Drag) return@LaunchedEffect
        val imageW = snapshotFlow { imageWidthPx }.first { it > 0f }
        val viewport = snapshotFlow { viewW }.first { it > 0f }
        snapshotFlow { scroll.maxValue }.first { it >= 0 }
        val center = imageW * home.centerX
        val target = (center - viewport / 2f).roundToInt().coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(target)
    }

    fun canTap(piece: RevealPiece): Boolean = when (coach.step) {
        TutorialStep.Look -> piece.id == guided.id
        TutorialStep.Solve -> true
        else -> false
    }

    fun canDrag(piece: RevealPiece): Boolean = when (coach.step) {
        TutorialStep.Drag -> piece.id == guided.id
        TutorialStep.Solve -> true
        else -> false
    }

    fun releaseDrag() {
        val id = session.draggingId
        val piece = scene.pieces.firstOrNull { it.id == id }
        val size = frame.imageSize
        if (piece == null || size.width <= 1f) {
            session.cancelDrag()
            dragId = null
            return
        }
        val scale = size.width / scene.width.toFloat()
        val pieceW = (piece.bbox.right - piece.bbox.left) * scale
        val pieceH = (piece.bbox.bottom - piece.bbox.top) * scale
        val topLeft = anchoredTopLeft(finger, grab, pieceW, pieceH)
        val outcome = session.tryPlace(topLeft, frame.imageTopLeft, size.width, snapPx)
        dragId = null
        if (outcome != PlaceOutcome.Locked) return
        if (id == guided.id && coach.step == TutorialStep.Drag) {
            coach.guidedPlaced(session.isComplete)
        } else if (session.isComplete) {
            coach.puzzleComplete()
        }
    }

    val dragging = dragId != null
    val scrollEnabled = session.inspected == null && !dragging
    val caption = when (coach.step) {
        TutorialStep.Look -> "Tap the piece"
        TutorialStep.Drag -> "Drag the piece"
        TutorialStep.Solve -> "Place the rest"
        else -> null
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Backdrop)
            .onGloballyPositioned { frame.rootOrigin = it.positionInRoot() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canExit) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Hint)
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    session.pieces.filter { it.id !in session.placedIds }.forEach { piece ->
                        key(piece.id) {
                            PieceChip(
                                piece = piece,
                                icon = bitmaps.icons.getValue(piece.id),
                                highlighted = piece.id == guided.id &&
                                    (coach.step == TutorialStep.Look || coach.step == TutorialStep.Drag),
                                hidden = dragId == piece.id,
                                canTap = canTap(piece),
                                canDrag = canDrag(piece),
                                onPosition = { center ->
                                    if (piece.id == guided.id) frame.chipCenter = center
                                },
                                onTap = {
                                    session.select(piece.id)
                                    if (piece.id == guided.id) coach.openPiece()
                                },
                                onDragStart = { at, fraction ->
                                    session.startDrag(piece.id)
                                    if (session.draggingId == piece.id) {
                                        dragId = piece.id
                                        finger = at
                                        grab = fraction
                                    }
                                },
                                onDrag = { finger = it },
                                onDragEnd = { releaseDrag() },
                                onDragCancel = {
                                    session.cancelDrag()
                                    dragId = null
                                },
                            )
                        }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
            ) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { viewW = it.size.width.toFloat() },
                ) {
                    val viewportW = constraints.maxWidth.toFloat()
                    val viewportH = constraints.maxHeight.toFloat()
                    val aspect = scene.aspectRatio.coerceAtLeast(0.01f)
                    val widthPx = viewportH * aspect
                    val heightPx = viewportH
                    val imageW = with(density) { widthPx.toDp() }
                    val imageH = with(density) { heightPx.toDp() }
                    val pans = widthPx > viewportW + 1f
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (pans) {
                                    Modifier.horizontalScroll(scroll, enabled = scrollEnabled)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = if (pans) Alignment.CenterStart else Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(imageW, imageH)
                                .onGloballyPositioned {
                                    frame.imageTopLeft = it.positionInRoot()
                                    frame.imageSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                                    imageWidthPx = frame.imageSize.width
                                },
                        ) {
                            Image(
                                bitmap = bitmaps.plate,
                                contentDescription = scene.title,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize(),
                            )
                            val placedIds = session.placedIds.toList()
                            Canvas(Modifier.fillMaxSize()) {
                                val scale = size.width / scene.width.toFloat()
                                placedIds.forEach { id ->
                                    val placed = scene.pieces.first { it.id == id }
                                    val crop = bitmaps.crops[id] ?: return@forEach
                                    drawImage(
                                        image = crop,
                                        dstOffset = IntOffset(
                                            (placed.bbox.left * scale).roundToInt(),
                                            (placed.bbox.top * scale).roundToInt(),
                                        ),
                                        dstSize = IntSize(
                                            ((placed.bbox.right - placed.bbox.left) * scale).roundToInt().coerceAtLeast(1),
                                            ((placed.bbox.bottom - placed.bbox.top) * scale).roundToInt().coerceAtLeast(1),
                                        ),
                                    )
                                }
                            }
                            if (coach.step == TutorialStep.Drag) {
                                Box(
                                    Modifier
                                        .offset(x = imageW * home.left, y = imageH * home.top)
                                        .size(width = imageW * home.width, height = imageH * home.height)
                                        .onGloballyPositioned { frame.ghostCenter = it.boundsInRoot().center },
                                ) {
                                    Image(
                                        bitmap = bitmaps.crops.getValue(guided.id),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { alpha = 0.45f },
                                    )
                                }
                            }
                        }
                    }
                }
                if (coach.step == TutorialStep.Pan) {
                    PanHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
                } else if (caption != null) {
                    Text(
                        text = caption,
                        color = Hint,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 28.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (coach.step == TutorialStep.Drag && !dragging &&
            frame.chipCenter != Offset.Zero && frame.ghostCenter != Offset.Zero
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawTutorialArrow(frame.chipCenter - frame.rootOrigin, frame.ghostCenter - frame.rootOrigin)
            }
        }

        val flying = scene.pieces.firstOrNull { it.id == dragId }
        if (flying != null && frame.imageSize.width > 1f) {
            val scale = frame.imageSize.width / scene.width.toFloat()
            val pieceW = (flying.bbox.right - flying.bbox.left) * scale
            val pieceH = (flying.bbox.bottom - flying.bbox.top) * scale
            val topLeft = anchoredTopLeft(finger, grab, pieceW, pieceH) - frame.rootOrigin
            Image(
                bitmap = bitmaps.crops.getValue(flying.id),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .size(with(density) { pieceW.toDp() }, with(density) { pieceH.toDp() }),
            )
        }

        val inspected = session.inspected
        if (inspected != null) {
            EnlargedPiece(
                bitmap = bitmaps.lifts[inspected.id] ?: bitmaps.icons.getValue(inspected.id),
                onClose = {
                    session.deselect()
                    coach.closePiece()
                },
            )
        }

        if (coach.step == TutorialStep.Done) {
            FinishTutorial(onFinished = onFinished)
        }
    }
}

@Composable
private fun PieceChip(
    piece: RevealPiece,
    icon: ImageBitmap,
    highlighted: Boolean,
    hidden: Boolean,
    canTap: Boolean,
    canDrag: Boolean,
    onPosition: (Offset) -> Unit,
    onTap: () -> Unit,
    onDragStart: (fingerInRoot: Offset, grab: Offset) -> Unit,
    onDrag: (fingerInRoot: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val origin = remember { arrayOf(Offset.Zero) }
    val aspect = icon.width.toFloat() / icon.height.toFloat().coerceAtLeast(1f)
    val width = minOf(IconHeight * aspect, IconMaxWidth)
    val height = width / aspect
    Image(
        bitmap = icon,
        contentDescription = piece.label ?: "Piece ${piece.id}",
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .size(width, height)
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .then(
                if (highlighted) {
                    Modifier.border(3.dp, Hint, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            )
            .onGloballyPositioned {
                origin[0] = it.positionInRoot()
                onPosition(it.boundsInRoot().center)
            }
            .pointerInput(piece.id, canTap, canDrag) {
                if (!canTap && !canDrag) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    var claimed = false
                    var ended = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) {
                                ended = true
                                if (claimed) onDragEnd() else if (canTap) onTap()
                                break
                            }
                            if (!claimed) {
                                val delta = change.position - down.position
                                when (classifyStripMove(delta.x, delta.y, slop)) {
                                    StripGesture.Pending -> Unit
                                    StripGesture.Scroll -> {
                                        ended = true
                                        break
                                    }
                                    StripGesture.Drag -> if (canDrag) {
                                        claimed = true
                                        val sizePx = Size(size.width.toFloat(), size.height.toFloat())
                                        val fraction = Offset(
                                            (down.position.x / sizePx.width).coerceIn(0f, 1f),
                                            (down.position.y / sizePx.height).coerceIn(0f, 1f),
                                        )
                                        onDragStart(origin[0] + change.position, fraction)
                                        change.consume()
                                        onDrag(origin[0] + change.position)
                                    }
                                }
                            } else {
                                change.consume()
                                onDrag(origin[0] + change.position)
                            }
                        }
                    } finally {
                        if (claimed && !ended) onDragCancel()
                    }
                }
            },
    )
}

@Composable
private fun PanHint(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pan")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shift",
    )
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(96.dp, 36.dp)
                .offset(x = (shift * 18).dp),
        ) {
            val y = size.height / 2f
            drawChevron(Offset(size.width * 0.28f, y), pointingRight = false)
            drawChevron(Offset(size.width * 0.72f, y), pointingRight = true)
        }
        Text(
            text = "Slide left and right",
            color = Hint,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun EnlargedPiece(bitmap: ImageBitmap, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(0.01f)
            val maxW = maxWidth * 0.86f
            val maxH = maxHeight * 0.62f
            val width = if (maxW / aspect <= maxH) maxW else maxH * aspect
            val height = width / aspect
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Piece",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(width, height)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
                IconButton(onClick = onClose, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Put it back", tint = Hint)
                }
            }
        }
    }
}

@Composable
private fun FinishTutorial(onFinished: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "That's the walk.",
                color = Hint,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "The rest of the trails are next.",
                color = Hint.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onFinished, modifier = Modifier.padding(top = 24.dp)) {
                Text("Continue")
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChevron(center: Offset, pointingRight: Boolean) {
    val dir = if (pointingRight) 1f else -1f
    val arm = size.minDimension * 0.28f
    val tip = Offset(center.x + dir * arm, center.y)
    val top = Offset(center.x - dir * arm, center.y - arm)
    val bottom = Offset(center.x - dir * arm, center.y + arm)
    drawLine(Hint, top, tip, strokeWidth = 5f, cap = StrokeCap.Round)
    drawLine(Hint, tip, bottom, strokeWidth = 5f, cap = StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTutorialArrow(from: Offset, to: Offset) {
    val delta = to - from
    val len = delta.getDistance()
    if (len < 8f) return
    val unit = delta / len
    val normal = Offset(-unit.y, unit.x)
    val head = 28f
    val base = to - unit * head
    drawLine(Color.Black.copy(alpha = 0.55f), from, base, strokeWidth = 10f, cap = StrokeCap.Round)
    drawLine(Hint, from, base, strokeWidth = 5f, cap = StrokeCap.Round)
    val headPath = Path().apply {
        moveTo(to.x, to.y)
        lineTo(base.x + normal.x * 12f, base.y + normal.y * 12f)
        lineTo(base.x - normal.x * 12f, base.y - normal.y * 12f)
        close()
    }
    drawPath(headPath, Hint)
}

private sealed interface TutorialLoad {
    data object Loading : TutorialLoad
    data object Missing : TutorialLoad
    data class Ready(val assets: TutorialAssets) : TutorialLoad
}

private suspend fun loadTutorial(context: Context): TutorialAssets {
    val scene = RevealLoader.load(context, "tutorial") ?: error("tutorial scene is missing")
    val bitmaps = RevealBitmaps.load(context, scene)
    val guided = firstTutorialPiece(scene.pieces)
    return TutorialAssets(
        scene = scene,
        bitmaps = bitmaps,
        guided = guided,
        home = tutorialHome(guided, scene.width, scene.height),
    )
}
