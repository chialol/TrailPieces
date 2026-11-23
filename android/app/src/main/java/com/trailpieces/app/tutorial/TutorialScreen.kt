package com.trailpieces.app.tutorial

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.trailpieces.app.R
import com.trailpieces.app.journey.LocalJourney
import com.trailpieces.app.layers.v3.PieceInspectOverlay
import com.trailpieces.app.layers.v3.PlaceOutcome
import com.trailpieces.app.layers.v3.RevealBitmaps
import com.trailpieces.app.layers.v3.RevealLoader
import com.trailpieces.app.layers.v3.RevealPiece
import com.trailpieces.app.layers.v3.RevealScene
import com.trailpieces.app.layers.v3.RevealScreen
import com.trailpieces.app.layers.v3.RevealSession
import com.trailpieces.journey.CatalogIndex
import com.trailpieces.journey.PlayerProgress
import com.trailpieces.journey.continueAfter
import com.trailpieces.app.layers.v3.StripGesture
import com.trailpieces.app.layers.v3.anchoredTopLeft
import com.trailpieces.app.layers.v3.classifyStripMove
import kotlinx.coroutines.flow.first
import kotlin.math.atan2
import kotlin.math.roundToInt

private val Backdrop = Color(0xFF0D0E0C)
private val Hint = Color(0xFFE9E5DA)
private val SnapThreshold = 20.dp
private val IconHeight = 68.dp
private val IconMaxWidth = 140.dp

/** Fingertip of Material's touch_app icon, as a fraction of the 24×24 viewport. */
private const val TouchTipX = 11.5f / 24f
private const val TouchTipY = 6f / 24f
private val TouchIconSize = 48.dp

private class TutorialFrame {
    var imageTopLeft by mutableStateOf(Offset.Zero)
    var imageSize by mutableStateOf(Size.Zero)
    var chipBounds by mutableStateOf(Rect.Zero)
    var chipCenter by mutableStateOf(Offset.Zero)
    var ghostBounds by mutableStateOf(Rect.Zero)
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
    var celebrating by remember { mutableStateOf(false) }
    if (celebrating) {
        TutorialFinale(onFinished = onFinished, modifier = modifier)
        return
    }
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
            onContinue = { celebrating = true },
            modifier = modifier,
        )
    }
}

@Composable
private fun TutorialPlay(
    assets: TutorialAssets,
    canExit: Boolean,
    onExit: () -> Unit,
    onContinue: () -> Unit,
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
    val bottomCaption = if (coach.step == TutorialStep.Solve && session.placedIds.size < 2) {
        "Place the rest of the pieces."
    } else {
        null
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
                                onPosition = { bounds ->
                                    if (piece.id == guided.id) {
                                        frame.chipBounds = bounds
                                        frame.chipCenter = bounds.center
                                    }
                                },
                                onTap = {
                                    session.select(piece.id)
                                    if (piece.id == guided.id) coach.openPiece()
                                },
                                onDragStart = { at, _ ->
                                    session.startDrag(piece.id)
                                    if (session.draggingId == piece.id) {
                                        dragId = piece.id
                                        finger = at
                                        grab = Offset(0.5f, 0.5f)
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
                                        .onGloballyPositioned {
                                            frame.ghostBounds = it.boundsInRoot()
                                            frame.ghostCenter = frame.ghostBounds.center
                                        },
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
                    PanHint(Modifier.align(Alignment.Center))
                } else if (bottomCaption != null) {
                    Text(
                        text = bottomCaption,
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

        if (coach.step == TutorialStep.Look && session.inspected == null && frame.chipBounds != Rect.Zero) {
            TapCoach(anchor = frame.chipBounds.bottomCenter - frame.rootOrigin)
        }
        if (coach.step == TutorialStep.Drag && !dragging &&
            frame.chipCenter != Offset.Zero && frame.ghostCenter != Offset.Zero
        ) {
            DragCoach(
                from = frame.chipCenter - frame.rootOrigin,
                to = frame.ghostCenter - frame.rootOrigin,
            )
            if (frame.ghostBounds != Rect.Zero) {
                PlaceCaption(topCenter = frame.ghostBounds.bottomCenter - frame.rootOrigin)
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
            PieceInspectOverlay(
                piece = inspected,
                bitmap = bitmaps.lifts[inspected.id] ?: bitmaps.icons.getValue(inspected.id),
                liftPad = scene.liftPad,
                mask = bitmaps.hitMasks.getValue(inspected.id),
                onDismiss = {
                    session.deselect()
                    coach.closePiece()
                },
                caption = if (coach.step == TutorialStep.Look) {
                    "Tap outside the piece when you're done."
                } else {
                    null
                },
            )
        }

        if (coach.step == TutorialStep.Done) {
            FinishTutorial(onContinue = onContinue)
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
    onPosition: (Rect) -> Unit,
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
                onPosition(it.boundsInRoot())
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
            text = "Drag to look around.",
            color = Hint,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun TapCoach(anchor: Offset) {
    val transition = rememberInfiniteTransition(label = "tap")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        TouchIcon(
            modifier = Modifier
                .offset {
                    val sizePx = TouchIconSize.toPx()
                    val overlap = 4.dp.toPx()
                    IntOffset(
                        (anchor.x - sizePx * TouchTipX).roundToInt(),
                        (anchor.y - overlap - sizePx * TouchTipY).roundToInt(),
                    )
                }
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                    transformOrigin = TransformOrigin(TouchTipX, TouchTipY)
                },
        )
        Text(
            text = "Tap to inspect the piece.",
            color = Hint,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset {
                    val sizePx = TouchIconSize.toPx()
                    val textW = 220.dp.toPx()
                    val maxX = (maxWidth.toPx() - textW).coerceAtLeast(0f)
                    val x = (anchor.x - textW / 2f).coerceIn(0f, maxX)
                    val iconTop = anchor.y - 4.dp.toPx() - sizePx * TouchTipY
                    val top = iconTop + sizePx + 8.dp.toPx()
                    IntOffset(x.roundToInt(), top.roundToInt())
                }
                .width(220.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PlaceCaption(topCenter: Offset) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Text(
            text = "Place the piece here.",
            color = Hint,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset {
                    val textW = 240.dp.toPx()
                    val maxX = (maxWidth.toPx() - textW).coerceAtLeast(0f)
                    val x = (topCenter.x - textW / 2f).coerceIn(0f, maxX)
                    IntOffset(x.roundToInt(), (topCenter.y + 10.dp.toPx()).roundToInt())
                }
                .width(240.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DragCoach(from: Offset, to: Offset) {
    val transition = rememberInfiniteTransition(label = "drag")
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2400
                0f at 0
                0f at 360
                1f at 1800 using FastOutSlowInEasing
                1f at 2400
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "travel",
    )
    val density = LocalDensity.current
    val stroke = with(density) { 12.dp.toPx() }
    val delta = to - from
    val len = delta.getDistance()
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (len < 8f) return@Canvas
            drawLine(Color.Black.copy(alpha = 0.35f), from, to, strokeWidth = stroke + 8f, cap = StrokeCap.Round)
            drawLine(Hint.copy(alpha = 0.38f), from, to, strokeWidth = stroke, cap = StrokeCap.Round)
            val steps = 5
            for (step in steps downTo 1) {
                val behind = travel - step * 0.04f
                if (behind <= 0f) continue
                drawCircle(
                    color = Hint.copy(alpha = 0.16f * (steps - step + 1) / steps),
                    radius = stroke * 0.55f,
                    center = from + delta * behind,
                )
            }
        }
        if (len >= 8f) {
            val head = from + delta * travel
            val rotation = -(atan2(delta.y, delta.x) * (180f / Math.PI.toFloat()) + 90f)
            val fade = when {
                travel < 0.08f -> travel / 0.08f
                travel > 0.92f -> (1f - travel) / 0.08f
                else -> 1f
            }
            TouchIcon(
                modifier = Modifier
                    .offset {
                        val sizePx = TouchIconSize.toPx()
                        IntOffset(
                            (head.x - sizePx * TouchTipX).roundToInt(),
                            (head.y - sizePx * TouchTipY).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        rotationZ = rotation
                        transformOrigin = TransformOrigin(TouchTipX, TouchTipY)
                        alpha = fade
                    },
            )
        }
    }
}

@Composable
private fun TouchIcon(modifier: Modifier = Modifier) {
    val painter = painterResource(R.drawable.ic_touch_app)
    Box(modifier.size(TouchIconSize)) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.4f),
            modifier = Modifier
                .fillMaxSize()
                .offset(1.dp, 2.dp),
        )
        Icon(
            painter = painter,
            contentDescription = null,
            tint = Hint,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FinishTutorial(onContinue: () -> Unit) {
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
            Button(onClick = onContinue, modifier = Modifier.padding(top = 24.dp)) {
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

@Composable
private fun TutorialFinale(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ready by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val reading by produceState<TutorialReading?>(null, context) {
        value = runCatching { loadTutorialReading(context) }.getOrNull()
    }
    RevealScreen(
        onBack = onFinished,
        initialSceneId = "tutorial",
        celebrate = true,
        readingBand = true,
        onPresentationSettled = { ready = true },
        onReplayStarted = { ready = false },
        footer = {
            if (ready) {
                TutorialReadingPanel(reading = reading, onDone = onFinished)
            }
        },
        modifier = modifier,
    )
}

private data class TutorialReading(
    val trailTitle: String,
    val collectedLine: String,
    val placeStory: String,
    val trailStory: String,
)

@Composable
private fun TutorialReadingPanel(
    reading: TutorialReading?,
    onDone: () -> Unit,
) {
    val trailTitle = reading?.trailTitle ?: "Collected"
    val collectedLine = reading?.collectedLine.orEmpty()
    val placeStory = reading?.placeStory.orEmpty()
    val trailStory = reading?.trailStory.orEmpty()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = trailTitle,
            style = MaterialTheme.typography.titleMedium,
            color = Hint,
        )
        if (collectedLine.isNotBlank()) {
            Text(
                text = collectedLine,
                style = MaterialTheme.typography.bodyLarge,
                color = Hint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (placeStory.isNotBlank()) {
            Text(
                text = placeStory,
                style = MaterialTheme.typography.bodyMedium,
                color = Hint.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (trailStory.isNotBlank() && trailStory != placeStory) {
            Text(
                text = trailStory,
                style = MaterialTheme.typography.bodyMedium,
                color = Hint.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Text("Done")
        }
    }
}

private suspend fun loadTutorialReading(context: Context): TutorialReading {
    val catalog = LocalJourney.graph(context).catalog.load()
    val index = CatalogIndex(catalog)
    val photo = index.photo("tutorial") ?: error("tutorial photo is not in the catalog")
    val offer = continueAfter(
        catalog,
        PlayerProgress(playerId = "", completedPhotoIds = emptySet()),
        photo.id,
        null,
    )
    val trail = offer.trailId?.let { index.trail(it) }
    val scenery = index.scenery(photo.sceneryId)
    return TutorialReading(
        trailTitle = trail?.title ?: "Collected",
        collectedLine = if (offer.total == 0) "Saved" else "${offer.collected} of ${offer.total} collected",
        placeStory = photo.story.ifBlank { scenery?.story.orEmpty() },
        trailStory = trail?.story.orEmpty(),
    )
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
