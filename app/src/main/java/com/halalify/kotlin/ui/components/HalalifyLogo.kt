package com.halalify.kotlin.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val BrandColorStart = Color(0xFF7EDDD7)
private val BrandColorEnd = Color(0xFF4ECDC4)

@Composable
fun HalalifyLogo(
    size: Dp = 72.dp,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "logoGlow")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (animated) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(
                Brush.linearGradient(
                    colors = listOf(BrandColorStart, BrandColorEnd),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = this.size.minDimension
            val cx = canvasSize / 2f
            val cy = canvasSize / 2f
            val outerRadius = canvasSize * 0.34f
            val glowStroke = canvasSize * 0.07f
            drawCircle(
                color = Color.White.copy(alpha = if (animated) 0.18f else 0.0f),
                radius = outerRadius + glowStroke * 1.5f,
                center = Offset(cx, cy),
                style = Stroke(width = glowStroke),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = outerRadius,
                center = Offset(cx, cy),
            )
            val trianglePath = Path().apply {
                val tipX = cx + outerRadius * 0.55f
                val baseYTop = cy - outerRadius * 0.45f
                val baseYBottom = cy + outerRadius * 0.45f
                moveTo(cx - outerRadius * 0.25f, baseYTop)
                lineTo(tipX, cy)
                lineTo(cx - outerRadius * 0.25f, baseYBottom)
                close()
            }
            drawPath(
                path = trianglePath,
                color = BrandColorStart,
            )
        }
    }
}