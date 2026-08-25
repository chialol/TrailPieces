package com.trailpieces.app.layers

import androidx.compose.ui.graphics.ColorMatrix

/** Saturation 0 = gray, 1 = full color. */
fun saturationColorMatrix(saturation: Float): ColorMatrix {
    val s = saturation.coerceIn(0f, 2f)
    val inv = 1f - s
    val r = 0.213f * inv
    val g = 0.715f * inv
    val b = 0.072f * inv
    return ColorMatrix(
        floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
