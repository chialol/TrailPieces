package com.trailpieces.app.layers.v3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Decoded images for one scene, at the asset's own pixel size.
 * Decoded once up front as GPU bitmaps so drags and pans only draw.
 */
class RevealBitmaps(
    val plate: ImageBitmap,
    val alive: ImageBitmap,
    val crops: Map<Int, ImageBitmap>,
    val lifts: Map<Int, ImageBitmap>,
    val icons: Map<Int, ImageBitmap>,
) {
    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)

        suspend fun load(context: Context, scene: RevealScene): RevealBitmaps =
            withContext(decodeDispatcher) {
                coroutineScope {
                    val plate = async { decode(context, scene, scene.plateFile) }
                    val alive = async { decode(context, scene, scene.aliveFile) }
                    val crops = scene.pieces.map { p -> async { p.id to decode(context, scene, p.cropFile) } }
                    val lifts = scene.pieces.map { p -> async { p.id to decode(context, scene, p.liftFile) } }
                    val icons = scene.pieces.map { p -> async { p.id to decode(context, scene, p.iconFile) } }
                    RevealBitmaps(
                        plate = plate.await(),
                        alive = alive.await(),
                        crops = crops.awaitAll().toMap(),
                        lifts = lifts.awaitAll().toMap(),
                        icons = icons.awaitAll().toMap(),
                    )
                }
            }

        suspend fun loadCover(context: Context, scene: RevealScene): ImageBitmap =
            withContext(Dispatchers.IO) { decode(context, scene, scene.coverFile) }

        private fun decode(context: Context, scene: RevealScene, file: String): ImageBitmap {
            val path = "reveal/${scene.id}/$file"
            val hardware = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.HARDWARE
                inScaled = false
            }
            val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, hardware) }
                ?: context.assets.open(path).use { BitmapFactory.decodeStream(it) }
                ?: error("Could not decode $path")
            return bitmap.asImageBitmap()
        }
    }
}
