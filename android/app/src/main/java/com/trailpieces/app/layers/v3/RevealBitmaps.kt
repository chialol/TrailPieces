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
    /** Crop alpha for each piece, used to tell a tap on the shape from a tap beside it. */
    val hitMasks: Map<Int, PieceMask>,
) {
    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)

        suspend fun load(context: Context, scene: RevealScene): RevealBitmaps =
            withContext(decodeDispatcher) {
                coroutineScope {
                    val plate = async { decode(context, scene, scene.plateFile).image }
                    val alive = async { decode(context, scene, scene.aliveFile).image }
                    val crops = scene.pieces.map { p ->
                        async { p.id to decode(context, scene, p.cropFile, withMask = true) }
                    }
                    val lifts = scene.pieces.map { p -> async { p.id to decode(context, scene, p.liftFile).image } }
                    val icons = scene.pieces.map { p -> async { p.id to decode(context, scene, p.iconFile).image } }
                    val decodedCrops = crops.awaitAll()
                    RevealBitmaps(
                        plate = plate.await(),
                        alive = alive.await(),
                        crops = decodedCrops.associate { it.first to it.second.image },
                        lifts = lifts.awaitAll().toMap(),
                        icons = icons.awaitAll().toMap(),
                        hitMasks = decodedCrops.associate { it.first to it.second.mask!! },
                    )
                }
            }

        suspend fun loadCover(context: Context, scene: RevealScene): ImageBitmap =
            withContext(Dispatchers.IO) { decode(context, scene, scene.coverFile).image }

        private class Decoded(val image: ImageBitmap, val mask: PieceMask?)

        private fun decode(
            context: Context,
            scene: RevealScene,
            file: String,
            withMask: Boolean = false,
        ): Decoded {
            val path = scene.directory?.let { "${it.path}/$file" } ?: "reveal/${scene.id}/$file"
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val software = openScene(context, scene, file).use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("Could not decode $path")
            val mask = if (withMask) maskOf(software) else null
            val gpu = software.copy(Bitmap.Config.HARDWARE, false)
            val image = if (gpu != null) {
                software.recycle()
                gpu.asImageBitmap()
            } else {
                software.asImageBitmap()
            }
            return Decoded(image, mask)
        }

        private fun openScene(context: Context, scene: RevealScene, file: String): java.io.InputStream {
            val directory = scene.directory
            if (directory != null) {
                val resolved = java.io.File(directory, file)
                if (!resolved.canonicalFile.path.startsWith(directory.canonicalFile.path)) {
                    error("Scene file escapes its folder")
                }
                return resolved.inputStream()
            }
            return context.assets.open("reveal/${scene.id}/$file")
        }

        private fun maskOf(bitmap: Bitmap): PieceMask {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val alpha = ByteArray(pixels.size) { index -> (pixels[index] ushr 24).toByte() }
            return PieceMask(width, height, alpha)
        }
    }
}
