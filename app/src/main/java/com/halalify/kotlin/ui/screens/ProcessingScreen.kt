package com.halalify.kotlin.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halalify.kotlin.model.ChunkPhase
import com.halalify.kotlin.model.ChunkState
import com.halalify.kotlin.model.ProcessingState
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

@Composable
internal fun ProcessingScreen(
    state: ProcessingState,
    onWatchNow: () -> Unit,
    onRetry: () -> Unit,
) {
    val overallProgress = if (state.totalChunks > 0) {
        state.completedChunks.toFloat() / state.totalChunks
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = overallProgress,
        animationSpec = tween(600),
        label = "progressAnim",
    )
    val hasReadyPreview = state.firstChunkReady && state.playablePaths.isNotEmpty()
    val remainingChunks = (state.totalChunks - state.completedChunks).coerceAtLeast(0)
    val statusText = when {
        state.isComplete -> "All chunks are ready."
        state.errorMessage != null && hasReadyPreview -> "Processing stopped, but your ready part is watchable."
        state.errorMessage != null -> "Processing stopped before a playable chunk was ready."
        hasReadyPreview -> "You can watch now while the rest continues."
        state.totalChunks > 0 -> "Preparing the first playable chunk."
        else -> "Reading the video and preparing the pipeline."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp, bottom = 24.dp),
    ) {
        // Title
        Text(
            text = if (state.errorMessage != null) "Processing Failed" else "Processing",
            style = MaterialTheme.typography.headlineMedium,
            color = if (state.errorMessage != null) HalalifyError else HalalifyTextPrimary,
        )

        if (state.videoTitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.videoTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = HalalifyTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        ProcessingSummary(
            state = state,
            remainingChunks = remainingChunks,
            statusText = statusText,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Overall progress bar
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.currentPhaseLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HalalifyAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = HalalifyAccentGold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = HalalifyAccent,
                trackColor = HalalifyDarkCard,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${state.completedChunks} / ${state.totalChunks} chunks",
                style = MaterialTheme.typography.bodySmall,
                color = HalalifyTextTertiary,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Watch button stays available even if later chunks fail.
        AnimatedVisibility(
            visible = hasReadyPreview && !state.isComplete,
            enter = fadeIn() + slideInVertically(),
        ) {
            Button(
                onClick = onWatchNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
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
            Spacer(modifier = Modifier.height(12.dp))
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

        // Complete state button
        if (state.isComplete) {
            Spacer(modifier = Modifier.height(16.dp))
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

        Spacer(modifier = Modifier.height(16.dp))

        // Chunk list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(state.chunks) { _, chunk ->
                ChunkRow(chunk)
            }
        }
    }
}

@Composable
private fun ProcessingSummary(
    state: ProcessingState,
    remainingChunks: Int,
    statusText: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HalalifyDarkCard.copy(alpha = 0.8f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (state.errorMessage != null) HalalifyAccentGold else HalalifyTextPrimary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryPill(
                label = "Duration",
                value = state.totalDurationSeconds.takeIf { it > 0 }?.let(::formatDuration) ?: "--",
            )
            SummaryPill(
                label = "Ready",
                value = "${state.completedChunks}/${state.totalChunks.takeIf { it > 0 } ?: "--"}",
            )
            SummaryPill(
                label = "Remaining",
                value = if (state.totalChunks > 0) remainingChunks.toString() else "--",
            )
        }
    }
}

@Composable
private fun SummaryPill(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = HalalifyTextTertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = HalalifyTextPrimary,
        )
    }
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
    ChunkPhase.EXTRACTING_AUDIO -> "Downloading audio"
    ChunkPhase.CLEANING_BACKEND -> "Removing music"
    ChunkPhase.MUXING -> "Merging"
    ChunkPhase.DONE -> "Ready ✓"
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
