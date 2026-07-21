package com.halalify.kotlin.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class ConfettiParticle(
    val color: Color,
    val angle: Float,
    val speed: Float,
    val size: Float,
    val rotationSpeed: Float,
    val initialRotation: Float,
)

@Composable
fun CelebrationOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    colors: List<Color> = defaultCelebrationColors(),
) {
    if (!visible) return
    val progress = remember { Animatable(0f) }
    val particles = remember(colors) {
        List(28) {
            ConfettiParticle(
                color = colors.random(),
                angle = (Random.nextFloat() * 360f),
                speed = 0.7f + Random.nextFloat() * 0.6f,
                size = (6f + Random.nextFloat() * 6f),
                rotationSpeed = (Random.nextFloat() - 0.5f) * 720f,
                initialRotation = (Random.nextFloat() * 360f),
            )
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec = tween(durationMillis = 1400, easing = LinearOutSlowInEasing))
        }
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = progress.value
            if (p >= 1f) return@Canvas
            val cx = size.width / 2f
            val cy = size.height * 0.35f
            val maxRadius = size.minDimension * 0.55f
            val gravityY = p * p * 120f
            particles.forEach { particle ->
                val radians = Math.toRadians(particle.angle.toDouble())
                val distance = particle.speed * maxRadius * p
                val x = cx + (cos(radians) * distance).toFloat()
                val y = cy + (sin(radians) * distance).toFloat() + gravityY
                val rotation = particle.initialRotation + particle.rotationSpeed * p
                drawRect(
                    color = particle.color.copy(alpha = (1f - p).coerceIn(0f, 1f)),
                    topLeft = Offset(x - particle.size / 2f, y - particle.size / 2f),
                    size = Size(particle.size, particle.size * 0.6f),
                )
            }
        }
    }
}

private fun defaultCelebrationColors() = listOf(
    Color(0xFF4ECDC4),
    Color(0xFFF7D794),
    Color(0xFF6BCB77),
    Color(0xFFFF6B6B),
    Color(0xFFF4F1E8),
    Color(0xFF7EDDD7),
)