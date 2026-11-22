package com.trailpieces.app.tutorial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.trailpieces.app.layers.v3.RevealLoader
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Backdrop = Color(0xFF0D0E0C)
private val Hint = Color(0xFFE9E5DA)

private class TutorialFrame {
    var imageTopLeft by mutableStateOf(Offset.Zero)
    var imageSize by mutableStateOf(Size.Zero)
    var chipCenter by mutableStateOf(Offset.Zero)
    var ghostCenter by mutableStateOf(Offset.Zero)
    var rootOrigin by mutableStateOf(Offset.Zero)
}

private data class TutorialBitmaps(
    val photo: ImageBitmap,
    val piece: ImageBitmap,
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
        value = withContext(Dispatchers.IO) {
            runCatching { decodeTutorial(context) }.getOrNull()
        }?.let(TutorialLoad::Ready) ?: TutorialLoad.Missing
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
            photo = ready.bitmaps.photo,
            piece = ready.bitmaps.piece,
            home = ready.bitmaps.home,
            canExit = canExit,
            onExit = onExit,
            onFinished = onFinished,
            modifier = modifier,
        )
    }
}

@Composable
private fun TutorialPlay(
    photo: ImageBitmap,
    piece: ImageBitmap,
    home: TutorialRegion,
    canExit: Boolean,
    onExit: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coach = remember { TutorialCoach() }
    val frame = remember { TutorialFrame() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var panArmed by remember { mutableStateOf(false) }
    var panOrigin by remember { mutableIntStateOf(0) }
    var imageWidthPx by remember { mutableFloatStateOf(0f) }
    var viewW by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val chipOrigin = remember { arrayOf(Offset.Zero) }

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

    val scrollEnabled = coach.step == TutorialStep.Pan || (coach.step == TutorialStep.Drag && !dragging)
    val showChip = coach.step == TutorialStep.Look || coach.step == TutorialStep.Drag
    val prompt = when {
        coach.step == TutorialStep.Look && !coach.looking -> "Tap the piece"
        coach.step == TutorialStep.Drag -> "Drag the piece"
        else -> null
    }
    val pieceAspect = piece.width.toFloat() / piece.height.toFloat().coerceAtLeast(1f)
    val chipH = 72.dp
    val chipW = minOf(chipH * pieceAspect, 220.dp)

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
                    .height(if (showChip || canExit || prompt != null) 96.dp else 0.dp)
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canExit) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Hint)
                    }
                }
                if (showChip) {
                    Image(
                        bitmap = piece,
                        contentDescription = "Piece",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(start = if (canExit) 0.dp else 16.dp)
                            .size(chipW, chipW / pieceAspect)
                            .graphicsLayer { alpha = if (dragging || coach.looking) 0f else 1f }
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = 2.dp,
                                color = Hint.copy(alpha = if (coach.step == TutorialStep.Look) 0.9f else 0.4f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .onGloballyPositioned {
                                chipOrigin[0] = it.positionInRoot()
                                frame.chipCenter = it.boundsInRoot().center
                            }
                            .then(
                                if (coach.step == TutorialStep.Look) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { coach.openPiece() },
                                    )
                                } else {
                                    Modifier.pointerInput(coach.step) {
                                        detectDragGestures(
                                            onDragStart = { local ->
                                                dragging = true
                                                finger = chipOrigin[0] + local
                                            },
                                            onDrag = { change, _ ->
                                                finger = chipOrigin[0] + change.position
                                                change.consume()
                                            },
                                            onDragEnd = {
                                                val size = frame.imageSize
                                                if (size.width > 1f && size.height > 1f) {
                                                    val fx = (finger.x - frame.imageTopLeft.x) / size.width
                                                    val fy = (finger.y - frame.imageTopLeft.y) / size.height
                                                    if (tutorialDropHits(fx, fy, home)) {
                                                        coach.placePiece()
                                                    }
                                                }
                                                dragging = false
                                            },
                                            onDragCancel = { dragging = false },
                                        )
                                    }
                                },
                            ),
                    )
                }
                if (prompt != null) {
                    Text(
                        text = prompt,
                        color = Hint,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp),
                    )
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
                    val aspect = photo.width.toFloat() / photo.height.toFloat().coerceAtLeast(0.01f)
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
                                bitmap = photo,
                                contentDescription = "After the Fire",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (coach.step == TutorialStep.Drag) {
                                val dest = home
                                Box(
                                    Modifier
                                        .offset(x = imageW * dest.left, y = imageH * dest.top)
                                        .size(width = imageW * dest.width, height = imageH * dest.height)
                                        .onGloballyPositioned { frame.ghostCenter = it.boundsInRoot().center }
                                        .clip(RoundedCornerShape(18.dp))
                                        .border(2.dp, Hint, RoundedCornerShape(18.dp)),
                                ) {
                                    Image(
                                        bitmap = piece,
                                        contentDescription = null,
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { alpha = 0.42f },
                                    )
                                }
                            }
                        }
                    }
                }
                if (coach.step == TutorialStep.Pan) {
                    PanHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
                }
            }
        }

        if (coach.step == TutorialStep.Drag && !dragging && frame.chipCenter != Offset.Zero && frame.ghostCenter != Offset.Zero) {
            Canvas(Modifier.fillMaxSize()) {
                val from = frame.chipCenter - frame.rootOrigin
                val to = frame.ghostCenter - frame.rootOrigin
                drawTutorialArrow(from, to)
            }
        }

        if (dragging && frame.imageSize.width > 1f) {
            val destW = frame.imageSize.width * home.width
            val destH = frame.imageSize.height * home.height
            Image(
                bitmap = piece,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (finger.x - frame.rootOrigin.x - destW / 2f).roundToInt(),
                            (finger.y - frame.rootOrigin.y - destH / 2f).roundToInt(),
                        )
                    }
                    .size(
                        with(density) { destW.toDp() },
                        with(density) { destH.toDp() },
                    )
                    .clip(RoundedCornerShape(18.dp)),
            )
        }

        if (coach.looking) {
            EnlargedPiece(piece = piece, onClose = { coach.closePiece() })
        }

        if (coach.step == TutorialStep.Done) {
            FinishTutorial(onFinished = onFinished)
        }
    }
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
private fun EnlargedPiece(piece: ImageBitmap, onClose: () -> Unit) {
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
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val aspect = piece.width.toFloat() / piece.height.toFloat().coerceAtLeast(0.01f)
            val maxW = maxWidth * 0.86f
            val maxH = maxHeight * 0.62f
            val width = if (maxW / aspect <= maxH) maxW else maxH * aspect
            val height = width / aspect
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = piece,
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
    data class Ready(val bitmaps: TutorialBitmaps) : TutorialLoad
}

private fun decodeTutorial(context: Context): TutorialBitmaps {
    val scene = RevealLoader.load(context, "tutorial")
        ?: error("tutorial scene is missing")
    val piece = topmostTutorialPiece(scene.pieces)
    return TutorialBitmaps(
        photo = decodeReveal(context, scene.aliveFile),
        piece = decodeReveal(context, piece.cropFile),
        home = tutorialHome(piece, scene.width, scene.height),
    )
}

private fun decodeReveal(context: Context, file: String): ImageBitmap {
    val path = "reveal/tutorial/$file"
    val hardware = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.HARDWARE
        inScaled = false
    }
    val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, hardware) }
        ?: context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        ?: error("Could not decode $path")
    return bitmap.asImageBitmap()
}
