package com.trailpieces.app.layers.v3

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFFE9E5DA)

/**
 * The enlarged piece. A tap on its opaque shape keeps it open. A tap anywhere
 * else, including the lift's shadow and the empty corners of the crop, puts
 * it back. There is no close button.
 */
@Composable
fun PieceInspectOverlay(
    piece: RevealPiece,
    bitmap: ImageBitmap,
    liftPad: Int,
    mask: PieceMask,
    onDismiss: () -> Unit,
    caption: String? = null,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        BoxWithConstraints(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
            val maxW = maxWidth * 0.86f
            val maxH = maxHeight * 0.62f
            val width = if (maxW / aspect <= maxH) maxW else maxH * aspect
            val height = width / aspect
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bitmap,
                    contentDescription = piece.label ?: "Piece ${piece.id}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(width, height)
                        .pointerInput(mask, liftPad, bitmap.width, bitmap.height) {
                            detectTapGestures { offset ->
                                val onPiece = tapLandsOnPiece(
                                    localX = offset.x,
                                    localY = offset.y,
                                    viewWidth = size.width.toFloat(),
                                    viewHeight = size.height.toFloat(),
                                    liftWidth = bitmap.width,
                                    liftHeight = bitmap.height,
                                    liftPad = liftPad,
                                    mask = mask,
                                )
                                if (!onPiece) onDismiss()
                            }
                        },
                )
                if (caption != null) {
                    Text(
                        text = caption,
                        color = Ink,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
