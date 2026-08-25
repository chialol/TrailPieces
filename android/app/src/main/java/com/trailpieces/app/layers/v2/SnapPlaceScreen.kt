package com.trailpieces.app.layers.v2

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trailpieces.app.layers.LayerDef
import com.trailpieces.app.layers.LayersManifest
import com.trailpieces.app.layers.rememberLayerAssetRequest
import com.trailpieces.app.layers.saturationColorMatrix
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private const val RevealMs = 650
private val SnapThresholdDp = 72.dp
private val TrayPreviewScale = 0.2f

@Composable
fun SnapPlaceScreen(
    manifest: LayersManifest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var sessionEpoch by remember { mutableStateOf(0) }
    val session = remember(manifest, sessionEpoch) { SnapPlaceSession(manifest) }

    var tick by remember { mutableStateOf(0) }
    fun bump() {
        tick++
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var sceneBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var sceneWidthPx by remember { mutableStateOf(0f) }
    var sceneHeightPx by remember { mutableStateOf(0f) }
    var pieceTopLeftInRoot by remember { mutableStateOf<Offset?>(null) }

    val revealProgress = remember(manifest, sessionEpoch) {
        mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()
    }
    fun revealOf(id: Int): Animatable<Float, AnimationVector1D> =
        revealProgress.getOrPut(id) { Animatable(0f) }

    val snapThresholdPx = with(density) { SnapThresholdDp.toPx() }
    val sceneAspect = manifest.width.toFloat() / manifest.height.toFloat()
    val trayLayers = manifest.layers.filter { it.id !in session.placed }

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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Text(
                    text = manifest.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }

            Text(
                text = when {
                    session.isComplete -> "The scene is whole."
                    session.placed.isEmpty() -> "Drag a piece close to where it belongs."
                    else -> "Oh — that's where that goes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val fittedW = min(maxW, maxH * sceneAspect)
                val fittedH = fittedW / sceneAspect
                val canvasW = with(density) { fittedW.toDp() }
                val canvasH = with(density) { fittedH.toDp() }

                if (sceneWidthPx != fittedW || sceneHeightPx != fittedH) {
                    sceneWidthPx = fittedW
                    sceneHeightPx = fittedH
                }

                Box(
                    modifier = Modifier
                        .size(canvasW, canvasH)
                        .onGloballyPositioned { coords ->
                            sceneBoundsInRoot = coords.boundsInRoot()
                        },
                ) {
                    session.placed.forEach { layerId ->
                        key(layerId) {
                            val progress = revealOf(layerId).value
                            MaskedLayerImage(
                                manifest = manifest,
                                assetPath = session.layer(layerId).file,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = 0.25f + 0.75f * progress
                                    },
                                saturation = lerp(manifest.mutedTraySaturation, 1f, progress),
                            )
                        }
                    }
                }
            }

            Text(
                text = "${session.placed.size} / ${manifest.layers.size} layers",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trayLayers.forEach { layer ->
                    key(layer.id) {
                        val isDragging = session.dragging?.layerId == layer.id
                        val (bboxW, bboxH) = session.traySizePx(layer, sceneWidthPx, sceneHeightPx)
                        val previewW = with(density) { (bboxW * TrayPreviewScale).toDp() }
                        val previewH = with(density) { (bboxH * TrayPreviewScale).toDp() }

                        TrayPiece(
                            manifest = manifest,
                            layer = layer,
                            width = previewW,
                            height = previewH,
                            visible = !isDragging,
                            onDragStart = { pieceTopLeft ->
                                session.startDrag(layer.id, Offset.Zero)
                                pieceTopLeftInRoot = pieceTopLeft
                                bump()
                                softHaptic(view)
                            },
                            onDrag = { pieceTopLeft ->
                                pieceTopLeftInRoot = pieceTopLeft
                                bump()
                            },
                            onDragEnd = {
                                val topLeft = pieceTopLeftInRoot
                                if (topLeft != null) {
                                    val placedId = session.dragging?.layerId
                                    when (
                                        session.trySnap(
                                            pieceTopLeftInRoot = topLeft,
                                            sceneTopLeftInRoot = Offset(
                                                sceneBoundsInRoot.left,
                                                sceneBoundsInRoot.top,
                                            ),
                                            sceneWidthPx = sceneWidthPx,
                                            sceneHeightPx = sceneHeightPx,
                                            snapThresholdPx = snapThresholdPx,
                                        )
                                    ) {
                                        SnapResult.Snapped -> {
                                            softHaptic(view)
                                            val id = placedId ?: session.placed.last()
                                            scope.launch {
                                                revealOf(id).snapTo(0f)
                                                revealOf(id).animateTo(
                                                    1f,
                                                    tween(RevealMs, easing = FastOutSlowInEasing),
                                                )
                                                bump()
                                            }
                                        }
                                        SnapResult.ReturnedToTray -> softHaptic(view)
                                    }
                                } else {
                                    session.clearDrag()
                                }
                                pieceTopLeftInRoot = null
                                bump()
                            },
                            onDragCancel = {
                                session.clearDrag()
                                pieceTopLeftInRoot = null
                                bump()
                            },
                        )
                    }
                }
            }

            if (session.isComplete) {
                Button(
                    onClick = {
                        sessionEpoch++
                        bump()
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Text("Build again")
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }

        val drag = session.dragging
        val topLeft = pieceTopLeftInRoot
        if (drag != null && topLeft != null && sceneWidthPx > 0f) {
            val layer = session.layer(drag.layerId)
            val (pieceW, pieceH) = session.traySizePx(layer, sceneWidthPx, sceneHeightPx)
            MaskedLayerImage(
                manifest = manifest,
                assetPath = layer.trayFile,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (topLeft.x - rootOrigin.x).roundToInt(),
                            (topLeft.y - rootOrigin.y).roundToInt(),
                        )
                    }
                    .size(
                        with(density) { pieceW.toDp() },
                        with(density) { pieceH.toDp() },
                    )
                    .graphicsLayer {
                        alpha = 0.9f
                        shadowElevation = 10f
                    },
                saturation = manifest.mutedTraySaturation,
            )
        }
    }
}

@Composable
private fun TrayPiece(
    manifest: LayersManifest,
    layer: LayerDef,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    visible: Boolean,
    onDragStart: (pieceTopLeftInRoot: Offset) -> Unit,
    onDrag: (pieceTopLeftInRoot: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var pieceOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .size(width, height)
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .onGloballyPositioned { coords ->
                val b = coords.boundsInRoot()
                pieceOriginInRoot = Offset(b.left, b.top)
            }
            .pointerInput(layer.id) {
                detectDragGestures(
                    onDragStart = {
                        dragDelta = Offset.Zero
                        onDragStart(pieceOriginInRoot)
                    },
                    onDrag = { _, amount ->
                        dragDelta += amount
                        onDrag(pieceOriginInRoot + dragDelta)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        MaskedLayerImage(
            manifest = manifest,
            assetPath = layer.trayFile,
            modifier = Modifier.fillMaxSize(),
            saturation = manifest.mutedTraySaturation,
        )
    }
}

@Composable
private fun MaskedLayerImage(
    manifest: LayersManifest,
    assetPath: String,
    modifier: Modifier = Modifier,
    saturation: Float = 1f,
) {
    AsyncImage(
        model = rememberLayerAssetRequest(manifest.id, assetPath),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.colorMatrix(saturationColorMatrix(saturation)),
        modifier = modifier,
    )
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun softHaptic(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
