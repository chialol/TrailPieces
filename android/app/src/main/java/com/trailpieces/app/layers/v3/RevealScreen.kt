package com.trailpieces.app.layers.v3

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trailpieces.app.layers.rememberBundledAssetRequest

private const val AliveMs = 1400
private val SnapThresholdDp = 112.dp
private val IconMaxWidth = 200.dp
private val IconMaxHeight = 68.dp
private val IconMinHeight = 40.dp

@Composable
fun RevealScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    feedback: RevealFeedback = SilentRevealFeedback,
) {
    val context = LocalContext.current
    val scene = remember { RevealLoader.loadDefault(context) }
    if (scene == null) {
        MissingRevealScene(onBack = onBack, modifier = modifier)
        return
    }
    RevealPlayfield(
        scene = scene,
        onBack = onBack,
        feedback = feedback,
        modifier = modifier,
    )
}

@Composable
private fun RevealPlayfield(
    scene: RevealScene,
    onBack: () -> Unit,
    feedback: RevealFeedback,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var sessionEpoch by remember { mutableStateOf(0) }
    val session = remember(scene, sessionEpoch) { RevealSession(scene) }
    session.feedback = feedback
    val frame = remember { PlaceFrame() }
    val scroll = rememberScrollState()
    var tick by remember { mutableStateOf(0) }
    fun bump() {
        tick++
    }

    @Suppress("UNUSED_EXPRESSION")
    tick

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewportBounds by remember { mutableStateOf(Rect.Zero) }
    var viewportWidthPx by remember { mutableStateOf(0f) }
    var imageWidthPx by remember { mutableStateOf(0f) }
    var imageHeightPx by remember { mutableStateOf(0f) }

    val alive = remember(scene.id, sessionEpoch) { Animatable(0f) }
    LaunchedEffect(sessionEpoch) {
        scroll.scrollTo(0)
    }
    LaunchedEffect(sessionEpoch, session.isComplete) {
        if (session.isComplete) {
            alive.animateTo(1f, tween(AliveMs, easing = FastOutSlowInEasing))
        } else {
            alive.snapTo(0f)
        }
    }

    val snapThresholdPx = with(density) { SnapThresholdDp.toPx() }
    frame.scrollPx = scroll.value.toFloat()
    frame.viewportBounds = viewportBounds
    frame.viewportWidthPx = viewportWidthPx
    frame.imageWidthPx = imageWidthPx
    frame.imageHeightPx = imageHeightPx
    frame.snapThresholdPx = snapThresholdPx
    val canPan = imageWidthPx > viewportWidthPx + 1f
    val aliveAlpha = alive.value
    val placedIds = session.placedIds.toList()
    val currentPieceId = session.current?.id
    val draggingId = session.draggingId

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val b = coords.boundsInRoot()
                rootOrigin = Offset(b.left, b.top)
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Text(
                    text = scene.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${placedIds.size}/${session.pieces.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (placedIds.size < session.pieces.size && imageWidthPx > 0f) {
                WaitingPieces(
                    scene = scene,
                    session = session,
                    placedIds = placedIds,
                    currentPieceId = currentPieceId,
                    draggingId = draggingId,
                    imageWidthPx = imageWidthPx,
                    imageHeightPx = imageHeightPx,
                    onFrame = { pieceW, pieceH, iconW, iconH ->
                        frame.pieceW = pieceW
                        frame.pieceH = pieceH
                        frame.iconW = iconW
                        frame.iconH = iconH
                    },
                    onDragStart = { pieceId, finger, fraction ->
                        session.startDrag(pieceId)
                        frame.grabFraction = fraction
                        frame.finger = finger
                        bump()
                    },
                    onDrag = { finger ->
                        frame.finger = finger
                        bump()
                    },
                    onDragEnd = {
                        val finger = frame.finger
                        val overPhoto = finger != null && frame.viewportBounds.contains(finger)
                        if (finger != null && overPhoto && frame.pieceW > 1f) {
                            session.tryPlace(
                                pieceTopLeftInRoot = anchoredTopLeft(
                                    finger,
                                    frame.grabFraction,
                                    frame.pieceW,
                                    frame.pieceH,
                                ),
                                viewportTopLeftInRoot = Offset(
                                    frame.viewportBounds.left,
                                    frame.viewportBounds.top,
                                ),
                                viewportWidthPx = frame.viewportWidthPx,
                                imageWidthPx = frame.imageWidthPx,
                                imageHeightPx = frame.imageHeightPx,
                                scrollPx = frame.scrollPx,
                                snapThresholdPx = frame.snapThresholdPx,
                            )
                        } else {
                            session.cancelDrag()
                        }
                        frame.finger = null
                        bump()
                    },
                    onDragCancel = {
                        session.cancelDrag()
                        frame.finger = null
                        bump()
                    },
                )
            }

            Text(
                text = hint(session, canPan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val viewW = constraints.maxWidth.toFloat()
                val viewH = constraints.maxHeight.toFloat()
                val fittedH = viewH
                val fittedW = fittedH * scene.aspectRatio
                if (viewportWidthPx != viewW) viewportWidthPx = viewW
                if (imageWidthPx != fittedW) imageWidthPx = fittedW
                if (imageHeightPx != fittedH) imageHeightPx = fittedH

                val imageW = with(density) { fittedW.toDp() }
                val imageH = with(density) { fittedH.toDp() }
                val pans = fittedW > viewW + 1f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .background(Color.Black)
                        .onGloballyPositioned { coords ->
                            viewportBounds = coords.boundsInRoot()
                        },
                ) {
                    if (pans) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(
                                    state = scroll,
                                    enabled = draggingId == null,
                                ),
                        ) {
                            PhotoStack(
                                scene = scene,
                                placedIds = placedIds,
                                aliveAlpha = aliveAlpha,
                                modifier = Modifier.size(imageW, imageH),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            PhotoStack(
                                scene = scene,
                                placedIds = placedIds,
                                aliveAlpha = aliveAlpha,
                                modifier = Modifier.size(imageW, imageH),
                            )
                        }
                    }
                }
            }

            if (placedIds.size == session.pieces.size) {
                Button(
                    onClick = { sessionEpoch++ },
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                ) {
                    Text("Look again")
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }

        val finger = frame.finger
        val dragged = draggingId?.let { id -> session.pieces.firstOrNull { it.id == id } }
        if (dragged != null && finger != null && frame.pieceW > 1f) {
            val overPhoto = frame.viewportBounds.contains(finger)
            val drawW = if (overPhoto) frame.pieceW else frame.iconW
            val drawH = if (overPhoto) frame.pieceH else frame.iconH
            val topLeft = anchoredTopLeft(finger, frame.grabFraction, drawW, drawH)
            AssetImage(
                assetPath = "reveal/${scene.id}/${dragged.trayFile}",
                modifier = Modifier
                    .graphicsLayer {
                        translationX = topLeft.x - rootOrigin.x
                        translationY = topLeft.y - rootOrigin.y
                        alpha = 0.94f
                        shadowElevation = if (overPhoto) 12f else 4f
                        clip = false
                    }
                    .requiredSize(
                        with(density) { drawW.toDp() },
                        with(density) { drawH.toDp() },
                    ),
            )
        }
    }
}

@Composable
private fun PhotoStack(
    scene: RevealScene,
    placedIds: List<Int>,
    aliveAlpha: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        AssetImage(
            assetPath = "reveal/${scene.id}/${scene.plateFile}",
            modifier = Modifier.fillMaxSize(),
        )
        scene.pieces.filter { it.id in placedIds }.forEach { piece ->
            key(piece.id) {
                PlacedPiece(scene = scene, piece = piece)
            }
        }
        if (aliveAlpha > 0f) {
            AssetImage(
                assetPath = "reveal/${scene.id}/${scene.aliveFile}",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = aliveAlpha },
            )
        }
    }
}

@Composable
private fun PlacedPiece(
    scene: RevealScene,
    piece: RevealPiece,
) {
    val alpha = remember(piece.id) { Animatable(0f) }
    LaunchedEffect(piece.id) {
        alpha.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    AssetImage(
        assetPath = "reveal/${scene.id}/${piece.file}",
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value },
    )
}

@Composable
private fun WaitingPieces(
    scene: RevealScene,
    session: RevealSession,
    placedIds: List<Int>,
    currentPieceId: Int?,
    draggingId: Int?,
    imageWidthPx: Float,
    imageHeightPx: Float,
    onFrame: (pieceW: Float, pieceH: Float, iconW: Float, iconH: Float) -> Unit,
    onDragStart: (pieceId: Int, finger: Offset, fraction: Offset) -> Unit,
    onDrag: (finger: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        session.pieces.filter { it.id !in placedIds }.forEach { piece ->
            key(piece.id) {
                val (pieceW, pieceH) = session.pieceSizePx(piece, imageWidthPx, imageHeightPx)
                val (iconW, iconH) = iconDpSize(pieceW, pieceH)
                val active = piece.id == currentPieceId
                if (active) {
                    onFrame(
                        pieceW,
                        pieceH,
                        with(density) { iconW.toPx() },
                        with(density) { iconH.toPx() },
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconPiece(
                        scene = scene,
                        piece = piece,
                        width = iconW,
                        height = iconH,
                        visible = draggingId != piece.id,
                        dimmed = !active,
                        draggable = active,
                        onDragStart = { finger, fraction ->
                            onDragStart(piece.id, finger, fraction)
                        },
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                    if (active && piece.label != null) {
                        Text(
                            text = pieceHeading(piece.label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconPiece(
    scene: RevealScene,
    piece: RevealPiece,
    width: Dp,
    height: Dp,
    visible: Boolean,
    dimmed: Boolean,
    draggable: Boolean,
    onDragStart: (finger: Offset, fraction: Offset) -> Unit,
    onDrag: (finger: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var pieceOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var fingerInRoot by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                alpha = when {
                    !visible -> 0f
                    dimmed -> 0.38f
                    else -> 1f
                }
            }
            .then(
                if (draggable) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .onGloballyPositioned { coords ->
                val b = coords.boundsInRoot()
                pieceOriginInRoot = Offset(b.left, b.top)
            }
            .then(
                if (draggable) {
                    Modifier.pointerInput(piece.id) {
                        detectDragGestures(
                            onDragStart = { localStart ->
                                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                                val heightPx = size.height.toFloat().coerceAtLeast(1f)
                                fingerInRoot = pieceOriginInRoot + localStart
                                onDragStart(
                                    fingerInRoot,
                                    Offset(
                                        (localStart.x / widthPx).coerceIn(0f, 1f),
                                        (localStart.y / heightPx).coerceIn(0f, 1f),
                                    ),
                                )
                            },
                            onDrag = { _, amount ->
                                fingerInRoot += amount
                                onDrag(fingerInRoot)
                            },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        AssetImage(
            assetPath = "reveal/${scene.id}/${piece.trayFile}",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Latest pan, photo size, and finger, so a drag still matches the canvas if the photo was slid first. */
private class PlaceFrame {
    var scrollPx = 0f
    var viewportBounds = Rect.Zero
    var viewportWidthPx = 0f
    var imageWidthPx = 0f
    var imageHeightPx = 0f
    var pieceW = 0f
    var pieceH = 0f
    var iconW = 0f
    var iconH = 0f
    var grabFraction = Offset(0.5f, 0.5f)
    var finger: Offset? = null
    var snapThresholdPx = 0f
}

@Composable
private fun AssetImage(
    assetPath: String,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = rememberBundledAssetRequest(assetPath),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier,
    )
}

@Composable
private fun MissingRevealScene(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No reveal scene yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Drop photo.jpg, photo-bw.jpg, and numbered pieces in shared/source/reveal/<name>/, then run tools/prep_reveal.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
            Text("Back")
        }
    }
}

private fun hint(session: RevealSession, canPan: Boolean): String {
    val name = session.current?.label
    return when {
        session.isComplete -> "The whole photo comes alive."
        name != null && canPan -> "Slide the photo, then drag the $name onto it."
        name != null -> "Drag the $name onto the photo."
        canPan -> "Slide the photo, then drag the next piece onto it."
        else -> "Drag the next piece onto the photo."
    }
}

private fun iconDpSize(pieceW: Float, pieceH: Float): Pair<Dp, Dp> {
    if (pieceW < 1f || pieceH < 1f) return IconMaxHeight to IconMaxHeight
    val aspect = pieceW / pieceH
    val width: Dp
    val height: Dp
    if (aspect >= IconMaxWidth / IconMaxHeight) {
        width = IconMaxWidth
        height = IconMaxWidth / aspect
    } else {
        height = IconMaxHeight
        width = IconMaxHeight * aspect
    }
    if (height >= IconMinHeight) return width to height
    val widened = (IconMinHeight * aspect).coerceAtMost(IconMaxWidth)
    return widened to (widened / aspect)
}

private fun pieceHeading(label: String): String =
    label.replaceFirstChar { char ->
        if (char.isLowerCase()) char.uppercaseChar() else char
    }
