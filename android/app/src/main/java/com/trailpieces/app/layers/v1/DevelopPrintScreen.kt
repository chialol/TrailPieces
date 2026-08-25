package com.trailpieces.app.layers.v1

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.trailpieces.app.layers.LayerDef
import com.trailpieces.app.layers.LayersLoader
import com.trailpieces.app.layers.LayersManifest
import com.trailpieces.app.layers.saturationColorMatrix
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private val SceneShape = RoundedCornerShape(12.dp)
private val ChipShape = RoundedCornerShape(10.dp)
private const val DevelopMs = 900
private const val GhostPreviewAlpha = 0.40f
private val ChipWidth = 96.dp
private val ChipHeight = 120.dp

@Composable
fun DevelopPrintScreen(
    manifest: LayersManifest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var sessionEpoch by remember { mutableStateOf(0) }
    val session = remember(manifest, sessionEpoch) { DevelopPrintSession(manifest) }

    var tick by remember { mutableStateOf(0) }
    fun bump() {
        tick++
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var fingerInRoot by remember { mutableStateOf<Offset?>(null) }

    val developProgress = remember(manifest, sessionEpoch) {
        mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()
    }

    fun developOf(id: Int): Animatable<Float, AnimationVector1D> =
        developProgress.getOrPut(id) { Animatable(0f) }

    val completeAlpha by animateFloatAsState(
        targetValue = if (session.isComplete) 1f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "complete",
    )

    val trayLayers = manifest.layers.filter { it.id !in session.placed }
    val chipWpx = with(density) { ChipWidth.toPx() }
    val chipHpx = with(density) { ChipHeight.toPx() }
    // Compose Modifier.aspectRatio wants width/height.
    val sceneAspect = manifest.width.toFloat() / manifest.height.toFloat()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                    session.isComplete -> "The print settles."
                    session.placed.isEmpty() -> "Drag a layer onto the scene."
                    else -> "Oh — that's where that goes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Scene: take remaining space, fit aspect inside it (never taller than available).
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

                Box(
                    modifier = Modifier
                        .size(canvasW, canvasH)
                        .shadow(10.dp, SceneShape, clip = false)
                        .clip(SceneShape)
                        .background(Color(0xFF1B4332))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            shape = SceneShape,
                        )
                        .onGloballyPositioned { coords ->
                            canvasBoundsInRoot = coords.boundsInRoot()
                        },
                ) {
                    SubcomposeAsyncImage(
                        model = rememberAssetImage(manifest.id, manifest.previewFile),
                        contentDescription = "Scene preview",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = GhostPreviewAlpha },
                        loading = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF2D6A4F)),
                            )
                        },
                        error = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF6B2D2D)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Preview missing", color = Color.White)
                            }
                        },
                    )

                    session.placed.forEach { layerId ->
                        key(layerId) {
                            val layer = session.layer(layerId)
                            val progress = developOf(layerId).value
                            val sat = lerp(manifest.mutedTraySaturation, 1f, progress)
                            AsyncImage(
                                model = rememberAssetImage(manifest.id, layer.file),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                colorFilter = ColorFilter.colorMatrix(saturationColorMatrix(sat)),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = 0.35f + 0.65f * progress
                                    },
                            )
                        }
                    }

                    if (completeAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.08f * completeAlpha)),
                        )
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
                    .height(ChipHeight + 16.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trayLayers.forEach { layer ->
                    key(layer.id) {
                        val isDragging = session.dragging?.layerId == layer.id
                        TrayChip(
                            manifest = manifest,
                            layer = layer,
                            visible = !isDragging,
                            onDragStart = { rootPos ->
                                session.startDrag(layer.id, Offset.Zero)
                                fingerInRoot = rootPos
                                bump()
                                softHaptic(view)
                            },
                            onDrag = { rootPos ->
                                fingerInRoot = rootPos
                                bump()
                            },
                            onDragEnd = {
                                val finger = fingerInRoot
                                val over = finger != null &&
                                    canvasBoundsInRoot.inflate(72f).contains(finger)
                                val placedId = session.dragging?.layerId
                                when (session.commitIfOverCanvas(over)) {
                                    LayerCommitKind.Placed -> {
                                        softHaptic(view)
                                        if (placedId != null) {
                                            scope.launch {
                                                val anim = developOf(placedId)
                                                anim.snapTo(0f)
                                                anim.animateTo(
                                                    1f,
                                                    tween(DevelopMs, easing = FastOutSlowInEasing),
                                                )
                                                bump()
                                            }
                                        }
                                    }
                                    LayerCommitKind.ReturnedToTray -> softHaptic(view)
                                }
                                fingerInRoot = null
                                bump()
                            },
                            onDragCancel = {
                                session.clearDrag()
                                fingerInRoot = null
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
                    Text("Develop again")
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }

        val dragging = session.dragging
        val finger = fingerInRoot
        if (dragging != null && finger != null) {
            val layer = session.layer(dragging.layerId)
            AsyncImage(
                model = rememberAssetImage(manifest.id, layer.trayFile),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(
                    saturationColorMatrix(manifest.mutedTraySaturation),
                ),
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (finger.x - rootOrigin.x - chipWpx / 2f).roundToInt(),
                            (finger.y - rootOrigin.y - chipHpx / 2f).roundToInt(),
                        )
                    }
                    .size(ChipWidth, ChipHeight)
                    .shadow(14.dp, ChipShape)
                    .clip(ChipShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .graphicsLayer { alpha = 0.95f },
            )
        }
    }
}

@Composable
private fun TrayChip(
    manifest: LayersManifest,
    layer: LayerDef,
    visible: Boolean,
    onDragStart: (rootPos: Offset) -> Unit,
    onDrag: (rootPos: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val context = LocalContext.current
    var chipOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var pointerInChip by remember { mutableStateOf(Offset.Zero) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(ChipWidth, ChipHeight)
                .graphicsLayer { alpha = if (visible) 1f else 0f }
                .shadow(6.dp, ChipShape, clip = false)
                .clip(ChipShape)
                .background(Color(0xFFE8F5E9))
                .border(
                    1.5.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    ChipShape,
                )
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInRoot()
                    chipOriginInRoot = Offset(b.left, b.top)
                }
                .pointerInput(layer.id) {
                    detectDragGestures(
                        onDragStart = { start ->
                            pointerInChip = start
                            onDragStart(chipOriginInRoot + start)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            pointerInChip += dragAmount
                            onDrag(chipOriginInRoot + pointerInChip)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = rememberAssetImage(manifest.id, layer.trayFile),
                contentDescription = "Layer ${layer.id}",
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(
                    saturationColorMatrix(manifest.mutedTraySaturation),
                ),
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Text("${layer.id}", color = MaterialTheme.colorScheme.primary)
                },
                error = {
                    Text("${layer.id}!", color = Color(0xFFB71C1C))
                },
            )
        }
    }
}

@Composable
private fun rememberAssetImage(sceneId: String, path: String): ImageRequest {
    val context = LocalContext.current
    return remember(sceneId, path) {
        val assetPath = "layers/$sceneId/$path"
        val bytes = runCatching {
            context.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull()
        ImageRequest.Builder(context)
            .data(bytes ?: LayersLoader.assetUri(sceneId, path))
            .memoryCacheKey(assetPath)
            .diskCacheKey(assetPath)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun softHaptic(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

private fun Rect.inflate(amount: Float): Rect =
    Rect(left - amount, top - amount, right + amount, bottom + amount)
