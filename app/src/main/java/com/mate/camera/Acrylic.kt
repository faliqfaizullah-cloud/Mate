package com.mate.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/** Fine grain tile shared by every acrylic surface. */
private val AcrylicNoise by lazy {
    ShaderBrush(ImageShader(makeNoise(128).asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/**
 * Acrylic surface (Fluent-style): translucent tint, a soft luminosity wash, fine grain, and a light
 * edge that catches the top-left. Lay it over a live preview and the scene shows through.
 */
fun Modifier.acrylic(
    shape: Shape,
    tint: Color = Color(0xFF1C1C1E),
    tintAlpha: Float = 0.62f,
    grain: Float = 0.07f,
    edge: Boolean = true,
): Modifier = this
    .clip(shape)
    .drawBehind {
        drawRect(tint.copy(alpha = tintAlpha))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.02f))))
        drawRect(AcrylicNoise, alpha = grain)
    }
    .then(
        if (edge) Modifier.border(
            0.8.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.05f))),
            shape,
        ) else Modifier
    )
