package com.trailpieces.app.layers.v3

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SnapThresholdDp = 20.dp
private val ActiveIconHeight = 60.dp
private val WaitingIconHeight = 40.dp
private val IconMaxWidth = 150.dp
private val Backdrop = Color(0xFF0D0E0C)
private val OnBackdrop = Color(0xFFE9E5DA)

private const val GrowMs = 170
private const val CenterMs = 140
private val PieceCenter = Offset(0.5f, 0.5f)
private const val ReturnMs = 260
private const val SettleSlide = 0.3f
private const val SettleMs = 520
private const val AliveMs = 1100
private const val SheenMs = 1500

@Composable
fun RevealScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    feedback: RevealFeedback = SilentRevealFeedback,
) {
    val context = LocalContext.current
    val scenes = remember { RevealLoader.loadAll(context) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = scenes.firstOrNull { it.id == selectedId }

    when {
        scenes.isEmpty() -> MissingRevealScene(onBack = onBack, modifier = modifier)
        selected == null -> ScenePicker(
            scenes = scenes,
            onPick = { selectedId = it.id },
            onBack = onBack,
            modifier = modifier,
        )
        else -> {
            BackHandler { selectedId = null }
            key(selected.id) {
                RevealPlayfield(
                    scene = selected,
                    onBack = { selectedId = null },
                    feedback = feedback,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun ScenePicker(
    scenes: List<RevealScene>,
    onPick: (RevealScene) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Backdrop)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnBackdrop)
        }
        Text(
            text = "Choose a photo",
            style = MaterialTheme.typography.headlineSmall,
            color = OnBackdrop,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp),
        )
        scenes.forEach { scene ->
            key(scene.id) {
                SceneCard(scene = scene, onClick = { onPick(scene) })
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SceneCard(scene: RevealScene, onClick: () -> Unit) {
    val context = LocalContext.current
    val cover by produceState<ImageBitmap?>(null, scene.id) {
        value = runCatching { RevealBitmaps.loadCover(context, scene) }.getOrNull()
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .background(Color(0xFF1C1E1A))
            .clickable(onClick = onClick),
    ) {
        cover?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = scene.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.7f),
                    ),
                ),
        )
        Text(
            text = scene.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        )
    }
}

/** Lift-in-progress. [finger] changes every frame and is only read while drawing. */
private class DragState {
    var pieceId by mutableStateOf<Int?>(null)
    var finger by mutableStateOf(Offset.Zero)
    /**
     * Where on the lift image the finger is, 0–1 on each axis. Starts at the touch
     * point on the icon and settles on the center while the piece is still icon-sized.
     */
    val grabAnim = Animatable(PieceCenter, Offset.VectorConverter)
    val grab: Offset get() = grabAnim.value
    var iconSizePx = Size.Zero
    var iconTopLeftInRoot = Offset.Zero
    val grow = Animatable(0f)
    var returning = false
}

/** Layout positions that change with pan or resize, written from layout callbacks. */
private class PlayGeometry {
    var rootOrigin = Offset.Zero
    var viewportBounds = Rect.Zero
    var imageTopLeftInRoot = Offset.Zero
    val iconTopLeftInRoot = HashMap<Int, Offset>()
}

private class Settle(val pieceId: Int, val fromScene: Offset) {
    val progress = Animatable(0f)
}

private class Finale {
    val alive = Animatable(0f)
    val flash = Animatable(0f)
    val sheen = Animatable(0f)
    val pulse = Animatable(1f)
}

@Composable
private fun RevealPlayfield(
    scene: RevealScene,
    onBack: () -> Unit,
    feedback: RevealFeedback,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val bitmaps by produceState<RevealBitmaps?>(null, scene.id) {
        value = runCatching { RevealBitmaps.load(context, scene) }
            .onFailure { failed = true }
            .getOrNull()
    }
    val loaded = bitmaps
    if (failed) {
        MissingRevealScene(onBack = onBack, modifier = modifier)
        return
    }
    if (loaded == null) {
        Box(modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OnBackdrop)
        }
        return
    }

    var epoch by remember { mutableStateOf(0) }
    key(epoch) {
        RevealRound(
            scene = scene,
            bitmaps = loaded,
            feedback = feedback,
            onBack = onBack,
            onReplay = { epoch++ },
            modifier = modifier,
        )
    }
}

@Composable
private fun RevealRound(
    scene: RevealScene,
    bitmaps: RevealBitmaps,
    feedback: RevealFeedback,
    onBack: () -> Unit,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val session = remember { RevealSession(scene) }
    session.feedback = feedback
    val drag = remember { DragState() }
    val geo = remember { PlayGeometry() }
    val finale = remember { Finale() }
    var settle by remember { mutableStateOf<Settle?>(null) }
    var fit by remember { mutableStateOf(PhotoFit(0f, 0f)) }
    val scroll = rememberScrollState()
    val snapThresholdPx = with(density) { SnapThresholdDp.toPx() }

    LaunchedEffect(Unit) {
        val max = snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
        scroll.scrollTo(max / 2)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { drag.pieceId != null && geo.viewportBounds.contains(drag.finger) }
            .distinctUntilChanged()
            .collect { over ->
                if (!drag.returning && drag.pieceId != null) {
                    launch {
                        runCatching {
                            drag.grow.animateTo(if (over) 1f else 0f, tween(GrowMs, easing = FastOutSlowInEasing))
                        }
                    }
                }
            }
    }

    fun displayScale(): Float = session.displayScale(fit.widthPx)

    fun returnToRow(pieceId: Int) {
        drag.returning = true
        scope.launch {
            val iconTopLeft = geo.iconTopLeftInRoot[pieceId] ?: drag.iconTopLeftInRoot
            val target = iconTopLeft + Offset(
                drag.grab.x * drag.iconSizePx.width,
                drag.grab.y * drag.iconSizePx.height,
            )
            try {
                coroutineScope {
                    launch {
                        runCatching { drag.grow.animateTo(0f, tween(ReturnMs, easing = FastOutSlowInEasing)) }
                    }
                    animate(
                        typeConverter = Offset.VectorConverter,
                        initialValue = drag.finger,
                        targetValue = target,
                        animationSpec = tween(ReturnMs, easing = FastOutSlowInEasing),
                    ) { value, _ -> drag.finger = value }
                }
            } finally {
                drag.pieceId = null
                drag.returning = false
            }
        }
    }

    fun cancelDrag() {
        val id = drag.pieceId ?: return
        if (drag.returning) return
        session.cancelDrag()
        returnToRow(id)
    }

    fun playFinale() {
        scope.launch {
            delay(SettleMs.toLong())
            launch {
                finale.flash.animateTo(0.32f, tween(140, easing = LinearEasing))
                finale.flash.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
            }
            launch {
                finale.pulse.animateTo(1.025f, tween(220, easing = FastOutSlowInEasing))
                finale.pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
            }
            launch { finale.alive.animateTo(1f, tween(AliveMs, easing = FastOutSlowInEasing)) }
            delay(380)
            finale.sheen.animateTo(1f, tween(SheenMs, easing = FastOutSlowInEasing))
        }
    }

    fun release() {
        val id = drag.pieceId ?: return
        if (drag.returning) return
        val lift = bitmaps.lifts.getValue(id)
        val scale = displayScale()
        val finger = drag.finger
        if (!geo.viewportBounds.contains(finger) || scale <= 0f) {
            session.cancelDrag()
            returnToRow(id)
            return
        }
        val liftTopLeft = anchoredTopLeft(finger, drag.grab, lift.width * scale, lift.height * scale)
        val pieceTopLeft = liftTopLeft + Offset(scene.liftPad * scale, scene.liftPad * scale)
        val outcome = session.tryPlace(
            pieceTopLeftInRoot = pieceTopLeft,
            imageTopLeftInRoot = geo.imageTopLeftInRoot,
            imageWidthPx = fit.widthPx,
            snapThresholdPx = snapThresholdPx,
        )
        if (outcome != PlaceOutcome.Locked) {
            returnToRow(id)
            return
        }
        val landing = Settle(id, (pieceTopLeft - geo.imageTopLeftInRoot) / scale)
        settle = landing
        drag.pieceId = null
        scope.launch {
            runCatching { drag.grow.snapTo(0f) }
            landing.progress.animateTo(1f, tween(SettleMs, easing = LinearEasing))
            if (settle === landing) settle = null
        }
        if (session.isComplete) playFinale()
    }

    val complete = session.isComplete

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Backdrop)
            .onGloballyPositioned { geo.rootOrigin = it.positionInRoot() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ActiveIconHeight + 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnBackdrop)
                }
                if (complete) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onReplay) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Play again", tint = OnBackdrop)
                    }
                } else {
                    WaitingRow(
                        session = session,
                        bitmaps = bitmaps,
                        drag = drag,
                        geo = geo,
                        modifier = Modifier.weight(1f),
                        onDragStart = { piece, fingerInRoot, grab, iconSize, iconTopLeft ->
                            if (drag.returning || drag.pieceId != null) return@WaitingRow
                            session.startDrag(piece.id)
                            if (session.draggingId != piece.id) return@WaitingRow
                            drag.iconSizePx = iconSize
                            drag.iconTopLeftInRoot = iconTopLeft
                            drag.finger = fingerInRoot
                            scope.launch { runCatching { drag.grow.snapTo(0f) } }
                            scope.launch {
                                runCatching {
                                    drag.grabAnim.snapTo(grab)
                                    drag.grabAnim.animateTo(PieceCenter, tween(CenterMs, easing = FastOutSlowInEasing))
                                }
                            }
                            drag.pieceId = piece.id
                        },
                        onDrag = { fingerInRoot ->
                            if (!drag.returning && drag.pieceId != null) drag.finger = fingerInRoot
                        },
                        onDragEnd = { if (!drag.returning) release() },
                        onDragCancel = { cancelDrag() },
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .onGloballyPositioned { geo.viewportBounds = it.boundsInRoot() },
            ) {
                val viewW = constraints.maxWidth.toFloat()
                val viewH = constraints.maxHeight.toFloat()
                val nextFit = PhotoFit.of(scene, viewW, viewH)
                if (nextFit != fit) fit = nextFit
                val imageW = with(density) { nextFit.widthPx.toDp() }
                val imageH = with(density) { nextFit.heightPx.toDp() }
                val pans = nextFit.pans(viewW)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = finale.pulse.value
                            scaleY = finale.pulse.value
                        }
                        .then(
                            if (pans) {
                                Modifier.horizontalScroll(scroll, enabled = drag.pieceId == null)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = if (pans) Alignment.CenterStart else Alignment.Center,
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(imageW, imageH)
                            .onGloballyPositioned { geo.imageTopLeftInRoot = it.positionInRoot() },
                    ) {
                        drawPhoto(
                            scene = scene,
                            bitmaps = bitmaps,
                            placedIds = session.placedIds,
                            settle = settle,
                            finale = finale,
                            visibleLeft = if (pans) scroll.value.toFloat() else 0f,
                            visibleWidth = if (pans) viewW else size.width,
                        )
                    }
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(),
        ) {
            val id = drag.pieceId ?: return@Canvas
            val lift = bitmaps.lifts[id] ?: return@Canvas
            val icon = bitmaps.icons[id] ?: return@Canvas
            val scale = displayScale()
            if (scale <= 0f) return@Canvas
            val fullW = lift.width * scale
            val fullH = lift.height * scale
            val iconScale = if (fullW > 0f) drag.iconSizePx.width / fullW else 1f
            val grow = drag.grow.value
            val drawScale = iconScale + (1f - iconScale) * grow
            val w = fullW * drawScale
            val h = fullH * drawScale
            val topLeft = anchoredTopLeft(drag.finger, drag.grab, w, h) - geo.rootOrigin
            // Small sizes use the icon: shrinking the full lift that far with bilinear filtering breaks up the outline.
            if (grow < 1f) {
                drawScaled(icon, topLeft, w, h, alpha = 1f)
            }
            if (grow > 0f) {
                drawScaled(lift, topLeft, w, h, alpha = grow)
            }
        }
    }
}

private fun DrawScope.drawPhoto(
    scene: RevealScene,
    bitmaps: RevealBitmaps,
    placedIds: List<Int>,
    settle: Settle?,
    finale: Finale,
    visibleLeft: Float,
    visibleWidth: Float,
) {
    val scale = size.width / scene.width
    drawImage(bitmaps.plate, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))

    val settlingId = settle?.pieceId
    placedIds.forEach { id ->
        if (id == settlingId) return@forEach
        drawCrop(scene, bitmaps, id, scale)
    }

    if (settle != null) {
        val piece = scene.pieces.first { it.id == settle.pieceId }
        val home = Offset(piece.bbox.left.toFloat(), piece.bbox.top.toFloat())
        val t = settle.progress.value
        val lift = bitmaps.lifts.getValue(piece.id)
        if (t < SettleSlide) {
            val k = FastOutSlowInEasing.transform(t / SettleSlide)
            val at = settle.fromScene + (home - settle.fromScene) * k
            drawLift(lift, at, scene.liftPad, scale, alpha = 1f)
        } else {
            drawCrop(scene, bitmaps, piece.id, scale)
            val fade = 1f - (t - SettleSlide) / (1f - SettleSlide)
            drawLift(lift, home, scene.liftPad, scale, alpha = fade.coerceIn(0f, 1f))
        }
    }

    val alive = finale.alive.value
    if (alive > 0f) {
        drawImage(
            bitmaps.alive,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = alive,
        )
    }

    val sheen = finale.sheen.value
    if (sheen > 0f && sheen < 1f) {
        val left = visibleLeft.coerceIn(0f, size.width)
        val right = (visibleLeft + visibleWidth).coerceIn(0f, size.width)
        val band = (right - left) * 0.35f
        val travel = (right - left) + band * 2f + size.height * 0.5f
        val center = left - band - size.height * 0.25f + travel * sheen
        drawRect(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                0.38f to Color.White.copy(alpha = 0f),
                0.5f to Color.White.copy(alpha = 0.55f),
                0.62f to Color.White.copy(alpha = 0f),
                1f to Color.Transparent,
                start = Offset(center - band, 0f),
                end = Offset(center + band, size.height * 0.55f),
            ),
            topLeft = Offset(left, 0f),
            size = Size(right - left, size.height),
            blendMode = BlendMode.Plus,
        )
    }

    val flash = finale.flash.value
    if (flash > 0f) {
        drawRect(Color.White, alpha = flash, blendMode = BlendMode.Plus)
    }
}

private fun DrawScope.drawScaled(image: ImageBitmap, topLeft: Offset, width: Float, height: Float, alpha: Float) {
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(width / image.width, height / image.height, pivot = Offset.Zero)
    }) {
        drawImage(image, alpha = alpha)
    }
}

private fun DrawScope.drawCrop(scene: RevealScene, bitmaps: RevealBitmaps, id: Int, scale: Float) {
    val crop = bitmaps.crops[id] ?: return
    val piece = scene.pieces.first { it.id == id }
    drawImage(
        image = crop,
        dstOffset = IntOffset((piece.bbox.left * scale).roundToInt(), (piece.bbox.top * scale).roundToInt()),
        dstSize = IntSize(
            (crop.width * scale).roundToInt().coerceAtLeast(1),
            (crop.height * scale).roundToInt().coerceAtLeast(1),
        ),
    )
}

private fun DrawScope.drawLift(lift: ImageBitmap, pieceTopLeftScene: Offset, pad: Int, scale: Float, alpha: Float) {
    if (alpha <= 0f) return
    val x = (pieceTopLeftScene.x - pad) * scale
    val y = (pieceTopLeftScene.y - pad) * scale
    drawImage(
        image = lift,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(
            (lift.width * scale).roundToInt().coerceAtLeast(1),
            (lift.height * scale).roundToInt().coerceAtLeast(1),
        ),
        alpha = alpha,
    )
}

@Composable
private fun WaitingRow(
    session: RevealSession,
    bitmaps: RevealBitmaps,
    drag: DragState,
    geo: PlayGeometry,
    modifier: Modifier = Modifier,
    onDragStart: (piece: RevealPiece, fingerInRoot: Offset, grab: Offset, iconSize: Size, iconTopLeft: Offset) -> Unit,
    onDrag: (fingerInRoot: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val currentId = session.current?.id
    val draggingId = drag.pieceId
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        session.pieces.filter { it.id !in session.placedIds }.forEach { piece ->
            key(piece.id) {
                val icon = bitmaps.icons.getValue(piece.id)
                val active = piece.id == currentId
                val (w, h) = iconDp(icon, if (active) ActiveIconHeight else WaitingIconHeight)
                WaitingIcon(
                    piece = piece,
                    icon = icon,
                    width = w,
                    height = h,
                    active = active,
                    hidden = draggingId == piece.id,
                    geo = geo,
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            }
        }
    }
}

@Composable
private fun WaitingIcon(
    piece: RevealPiece,
    icon: ImageBitmap,
    width: Dp,
    height: Dp,
    active: Boolean,
    hidden: Boolean,
    geo: PlayGeometry,
    onDragStart: (piece: RevealPiece, fingerInRoot: Offset, grab: Offset, iconSize: Size, iconTopLeft: Offset) -> Unit,
    onDrag: (fingerInRoot: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val origin = remember { arrayOf(Offset.Zero) }
    val down = remember { arrayOf(Offset.Zero) }
    Image(
        bitmap = icon,
        contentDescription = piece.label,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                alpha = when {
                    hidden -> 0f
                    active -> 1f
                    else -> 0.4f
                }
            }
            .onGloballyPositioned {
                origin[0] = it.positionInRoot()
                geo.iconTopLeftInRoot[piece.id] = origin[0]
            }
            .then(
                if (active) {
                    Modifier
                        .pointerInput(piece.id) {
                            awaitEachGesture {
                                down[0] = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                            }
                        }
                        .pointerInput(piece.id) {
                            detectDragGestures(
                                onDragStart = { local ->
                                    val sizePx = Size(size.width.toFloat(), size.height.toFloat())
                                    val grabAt = down[0]
                                    val grab = Offset(
                                        (grabAt.x / sizePx.width).coerceIn(0f, 1f),
                                        (grabAt.y / sizePx.height).coerceIn(0f, 1f),
                                    )
                                    onDragStart(piece, origin[0] + local, grab, sizePx, origin[0])
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    onDrag(origin[0] + change.position)
                                },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                            )
                        }
                } else {
                    Modifier
                },
            ),
    )
}

private fun iconDp(icon: ImageBitmap, targetHeight: Dp): Pair<Dp, Dp> {
    val aspect = icon.width.toFloat() / icon.height.toFloat().coerceAtLeast(1f)
    val width = targetHeight * aspect
    return if (width <= IconMaxWidth) width to targetHeight else IconMaxWidth to (IconMaxWidth / aspect)
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
