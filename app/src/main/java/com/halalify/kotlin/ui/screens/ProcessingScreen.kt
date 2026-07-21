package com.halalify.kotlin.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import com.halalify.kotlin.model.ChunkPhase
import com.halalify.kotlin.model.ChunkState
import com.halalify.kotlin.model.ProcessingState
import com.halalify.kotlin.ui.components.HalalifyTopBar
import com.halalify.kotlin.ui.theme.HalalifyAccent
import com.halalify.kotlin.ui.theme.HalalifyAccentDim
import com.halalify.kotlin.ui.theme.HalalifyAccentGold
import com.halalify.kotlin.ui.theme.HalalifyDarkCard
import com.halalify.kotlin.ui.theme.HalalifyError
import com.halalify.kotlin.ui.theme.HalalifySuccess
import com.halalify.kotlin.ui.theme.HalalifyTextOnAccent
import com.halalify.kotlin.ui.theme.HalalifyTextPrimary
import com.halalify.kotlin.ui.theme.HalalifyTextSecondary
import com.halalify.kotlin.ui.theme.HalalifyTextTertiary
import kotlinx.coroutines.delay

@Composable
internal fun ProcessingScreen(
    state: ProcessingState,
    isExporting: Boolean,
    onWatchNow: () -> Unit,
    onSaveToGallery: () -> Unit,
    onRetry: () -> Unit,
    onRetryFailedChunk: () -> Unit,
    onSkipFailedChunk: () -> Unit,
) {
    val overallProgress = if (state.totalChunks > 0) {
        state.completedChunks.toFloat() / state.totalChunks
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = overallProgress,
        animationSpec = tween(800),
        label = "progressAnim",
    )
    val hasReadyPreview = state.firstChunkReady && state.playablePaths.isNotEmpty()
    val statusText = when {
        state.isComplete -> "All chunks are ready."
        state.pausedChunkIndex != null && hasReadyPreview -> "Chunk ${state.pausedChunkIndex + 1} failed. Retry it, skip it, or watch the ready part."
        state.pausedChunkIndex != null -> "Chunk ${state.pausedChunkIndex + 1} failed. You can retry or skip it."
        state.errorMessage != null && hasReadyPreview -> "Processing stopped, but your ready part is watchable."
        state.errorMessage != null -> "Processing stopped before a playable chunk was ready."
        hasReadyPreview -> "You can watch now while the rest continues."
        state.totalChunks > 0 -> "Preparing the first playable chunk."
        else -> "Reading the video and preparing the pipeline."
    }
    val context = LocalContext.current

    // Keep the screen awake while processing so the user can watch progress.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Recompute ETA every second based on measured chunk throughput.
    val etaSeconds by produceState<Long?>(initialValue = null, state.processingStartedAt, state.completedChunks) {
        while (true) {
            value = estimateRemainingSecondsMeasured(state)
            delay(1000L)
        }
    }

    var showDetails by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            HalalifyTopBar(
                title = if (state.errorMessage != null) "Processing Failed" else "Processing",
                subtitle = state.videoTitle.takeIf { it.isNotBlank() },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProgressCenterpiece(
            state = state,
            animatedProgress = animatedProgress,
            etaSeconds = etaSeconds,
        )

        StatusBubble(statusText = statusText, isError = state.errorMessage != null && !hasReadyPreview)

        Spacer(modifier = Modifier.height(20.dp))

        // Watch button stays available even if later chunks fail.
        AnimatedVisibility(
            visible = hasReadyPreview && !state.isComplete,
            enter = fadeIn() + slideInVertically(),
        ) {
            Column {
                Button(
                    onClick = onWatchNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HalalifyAccentGold,
                        contentColor = HalalifyTextOnAccent,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.errorMessage == null) "Watch while processing" else "Watch ready part",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        Button(
            onClick = onSaveToGallery,
            enabled = state.isComplete &&
                state.playablePaths.isNotEmpty() &&
                !state.isSavedToGallery &&
                !isExporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HalalifyAccent,
                contentColor = HalalifyTextOnAccent,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = HalalifyTextTertiary,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Save,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isExporting -> "Saving..."
                    state.isSavedToGallery -> "Saved to Gallery"
                    state.isComplete -> "Save to Gallery"
                    else -> "Available when processing finishes"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Error state
        if (state.errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HalalifyError.copy(alpha = 0.1f))
                    .padding(16.dp),
            ) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = HalalifyError,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (state.pausedChunkIndex != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onRetryFailedChunk,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HalalifyAccent,
                            contentColor = HalalifyTextOnAccent,
                        ),
                    ) {
                        Text("Retry chunk ${state.pausedChunkIndex + 1}")
                    }
                    Button(
                        onClick = onSkipFailedChunk,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = HalalifyTextPrimary,
                        ),
                    ) {
                        Text("Skip chunk")
                    }
                }
            } else {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HalalifyAccentDim,
                    ),
                ) {
                    Text(if (hasReadyPreview) "Start Over" else "Try Again")
                }
            }
        }

        // Complete state button
        if (state.isComplete) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onWatchNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HalalifySuccess,
                    contentColor = HalalifyTextOnAccent,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Watch Result",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        DetailsSection(
            expanded = showDetails,
            onToggle = { showDetails = !showDetails },
            chunks = state.chunks,
        )
    }
    }
}

@Composable
private fun ProgressCenterpiece(
    state: ProcessingState,
    animatedProgress: Float,
    etaSeconds: Long?,
) {
    val percent = (animatedProgress * 100).toInt()
    val phaseColor = when {
        state.isComplete -> HalalifySuccess
        state.errorMessage != null -> HalalifyError
        else -> HalalifyAccent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(180.dp),
    ) {
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxSize(),
            color = phaseColor,
            strokeWidth = 10.dp,
            trackColor = HalalifyDarkCard,
            strokeCap = StrokeCap.Round,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = HalalifyTextPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${state.completedChunks}/${state.totalChunks.takeIf { it > 0 } ?: "--"} chunks",
                style = MaterialTheme.typography.labelMedium,
                color = HalalifyTextTertiary,
            )
        }
    }
    Spacer(modifier = Modifier.height(14.dp))
    AnimatedContent(
        targetState = state.currentPhaseLabel,
        transitionSpec = {
            (fadeIn(animationSpec = tween(300)) + slideInVertically { it / 4 }) togetherWith
                fadeOut(animationSpec = tween(200))
        },
        label = "phaseLabel",
    ) { phase ->
        Text(
            text = phase,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = phaseColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (etaSeconds != null && !state.isComplete && state.errorMessage == null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Est. ${formatDuration(etaSeconds.toInt())} remaining",
            style = MaterialTheme.typography.bodySmall,
            color = HalalifyAccentGold,
        )
    }
}

@Composable
private fun StatusBubble(statusText: String, isError: Boolean) {
    Spacer(modifier = Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isError) HalalifyError.copy(alpha = 0.12f)
                else HalalifyAccent.copy(alpha = 0.10f),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (isError) HalalifyError else HalalifyTextPrimary,
        )
    }
}

@Composable
private fun DetailsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    chunks: List<ChunkState>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HalalifyDarkCard.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.HourglassEmpty,
                contentDescription = null,
                tint = HalalifyAccent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Chunk details",
                style = MaterialTheme.typography.titleSmall,
                color = HalalifyTextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (chunks.isNotEmpty()) {
                Text(
                    text = "${chunks.count { it.phase == ChunkPhase.DONE }}/${chunks.size} ready",
                    style = MaterialTheme.typography.labelMedium,
                    color = HalalifyTextTertiary,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = HalalifyTextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(250)) + fadeIn(),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (chunks.isEmpty()) {
                    Text(
                        text = "Chunks will appear here once processing starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HalalifyTextTertiary,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    chunks.forEach { chunk ->
                        ChunkRow(chunk)
                    }
                }
            }
        }
    }
}

private fun estimateRemainingSecondsMeasured(state: ProcessingState): Long? {
    if (state.isComplete || state.errorMessage != null) return null
    if (state.totalChunks <= 0 || state.completedChunks <= 0) return null
    val remaining = state.totalChunks - state.completedChunks
    if (remaining <= 0) return 0L
    val startedAt = state.processingStartedAt
    if (startedAt <= 0L) return null
    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
    if (elapsedMs <= 0L) return null
    val avgMsPerChunk = elapsedMs / state.completedChunks
    return (avgMsPerChunk * remaining / 1000L).coerceAtLeast(0L)
}

@Composable
private fun ChunkRow(chunk: ChunkState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HalalifyDarkCard.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon
        when (chunk.phase) {
            ChunkPhase.DONE -> Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Done",
                tint = HalalifySuccess,
                modifier = Modifier.size(20.dp),
            )
            ChunkPhase.ERROR -> Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                tint = HalalifyError,
                modifier = Modifier.size(20.dp),
            )
            ChunkPhase.WAITING -> Icon(
                imageVector = Icons.Default.HourglassEmpty,
                contentDescription = "Waiting",
                tint = HalalifyTextTertiary,
                modifier = Modifier.size(20.dp),
            )
            else -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = HalalifyAccent,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Chunk number
        Text(
            text = "Chunk ${chunk.index + 1}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = HalalifyTextPrimary,
            modifier = Modifier.width(72.dp),
        )

        // Phase label
        Text(
            text = chunk.phase.displayLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = when (chunk.phase) {
                ChunkPhase.DONE -> HalalifySuccess
                ChunkPhase.ERROR -> HalalifyError
                ChunkPhase.WAITING -> HalalifyTextTertiary
                else -> HalalifyAccentGold
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ChunkPhase.displayLabel(): String = when (this) {
    ChunkPhase.WAITING -> "Waiting..."
    ChunkPhase.CUTTING_VIDEO -> "Cutting video"
    ChunkPhase.BLURRING_VIDEO -> "Blurring women"
    ChunkPhase.EXTRACTING_AUDIO -> "Downloading audio"
    ChunkPhase.CLEANING_BACKEND -> "Removing music"
    ChunkPhase.MUXING -> "Merging"
    ChunkPhase.DONE -> "Ready ✓"
    ChunkPhase.SKIPPED -> "Skipped"
    ChunkPhase.ERROR -> "Failed"
}

private fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes}m ${seconds}s"
    } else {
        "${seconds}s"
    }
}
