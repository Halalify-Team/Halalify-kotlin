package com.halalify.kotlin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.halalify.kotlin.model.ProcessingState
import com.halalify.kotlin.ui.ChunkPlaylistPlayer
import com.halalify.kotlin.ui.components.CelebrationOverlay
import com.halalify.kotlin.ui.components.HalalifyTopBar
import com.halalify.kotlin.ui.theme.HalalifyAccent
import com.halalify.kotlin.ui.theme.HalalifyAccentDim
import com.halalify.kotlin.ui.theme.HalalifyDarkCard
import com.halalify.kotlin.ui.theme.HalalifySuccess
import com.halalify.kotlin.ui.theme.HalalifyTextOnAccent
import com.halalify.kotlin.ui.theme.HalalifyTextPrimary
import com.halalify.kotlin.ui.theme.HalalifyTextSecondary
import com.halalify.kotlin.ui.theme.HalalifyTextTertiary

@Composable
internal fun ResultScreen(
    state: ProcessingState,
    exportStatus: String?,
    isExporting: Boolean,
    onSaveToGallery: () -> Unit,
    onClearExportStatus: () -> Unit,
    onBack: () -> Unit,
    onHalalifyAnother: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var showDiscardDialog by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var hasCelebrated by remember { mutableStateOf(false) }
    var showCelebration by remember { mutableStateOf(false) }
    var hasHaptickedFirstChunk by remember { mutableStateOf(false) }
    val isFreshComplete = state.isComplete && !state.isLibraryPlayback
    val shouldWarnBeforeDiscarding = state.isComplete &&
        !state.isSavedToGallery &&
        !state.isLibraryPlayback &&
        state.playablePaths.isNotEmpty()
    val readyLabel = if (state.totalChunks > 0) {
        "Ready ${state.completedChunks} / ${state.totalChunks} chunks"
    } else {
        "Preparing preview"
    }

    SystemBarsVisibilityEffect(isFullscreen = isFullscreen, context = context)

    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
        } else if (shouldWarnBeforeDiscarding) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    LaunchedEffect(exportStatus) {
        if (exportStatus != null) {
            android.widget.Toast.makeText(context, exportStatus, android.widget.Toast.LENGTH_LONG).show()
            if (!exportStatus.contains("fail", ignoreCase = true) &&
                !exportStatus.contains("error", ignoreCase = true)
            ) {
                haptics.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                )
            }
            onClearExportStatus()
        }
    }

    LaunchedEffect(isFreshComplete) {
        if (isFreshComplete && !hasCelebrated) {
            hasCelebrated = true
            showCelebration = true
            haptics.performHapticFeedback(
                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
            )
            kotlinx.coroutines.delay(1500)
            showCelebration = false
        }
    }

    LaunchedEffect(state.firstChunkReady) {
        if (state.firstChunkReady && !state.isLibraryPlayback && !hasHaptickedFirstChunk) {
            hasHaptickedFirstChunk = true
            haptics.performHapticFeedback(
                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
            )
        }
    }

    if (isFullscreen) {
        FullscreenVideo(
            state = state,
            onExitFullscreen = { isFullscreen = false },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            HalalifyTopBar(
                title = when {
                    !state.isComplete -> "Preview"
                    state.removeMusic -> "Halalified ✓"
                    else -> "Downloaded ✓"
                },
                subtitle = state.videoTitle.takeIf { it.isNotBlank() },
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        // Video player
        if (state.playablePaths.isNotEmpty()) {
            ChunkPlaylistPlayer(
                filePaths = state.playablePaths,
                onChunkChanged = {},
                isFullscreen = isFullscreen,
                onToggleFullscreen = { isFullscreen = !isFullscreen },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }

        // Info bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = readyLabel,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = HalalifyTextSecondary,
            )
            if (!state.isComplete && state.currentPhaseLabel.isNotBlank()) {
                Text(
                    text = state.currentPhaseLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = HalalifyAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else if (state.totalDurationSeconds > 0) {
                val minutes = state.totalDurationSeconds / 60
                val seconds = state.totalDurationSeconds % 60
                Text(
                    text = "${minutes}m ${seconds}s total",
                    style = MaterialTheme.typography.bodySmall,
                    color = HalalifyTextTertiary,
                )
            }
        }

        // Bottom actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(HalalifyDarkCard)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ResultStorageStatus(
                state = state,
                isExporting = isExporting,
            )

            if (state.isComplete) {
                Button(
                    onClick = onSaveToGallery,
                    enabled = !state.isSavedToGallery && !isExporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isSavedToGallery) HalalifySuccess else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (state.isSavedToGallery) HalalifyTextOnAccent else HalalifyTextPrimary,
                        disabledContainerColor = HalalifySuccess,
                        disabledContentColor = HalalifyTextOnAccent,
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
                            else -> "Save to Gallery"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
    }
        CelebrationOverlay(
            visible = showCelebration,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopCenter),
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(text = "Discard unsaved video?") },
            text = {
                Text(
                    text = "This halalified video has not been saved to your gallery. If you leave now, the temporary file will be deleted from this device.",
                    color = HalalifyTextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onHalalifyAnother()
                    },
                ) {
                    Text(text = "Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(text = "Stay")
                }
            },
            containerColor = HalalifyDarkCard,
            titleContentColor = HalalifyTextPrimary,
            textContentColor = HalalifyTextSecondary,
        )
    }
}

@Composable
private fun ResultStorageStatus(
    state: ProcessingState,
    isExporting: Boolean,
) {
    val (title, detail, iconTint) = when {
        state.isLibraryPlayback -> Triple(
            "Saved in Library",
            "This video is already stored inside Halalify.",
            HalalifySuccess,
        )
        isExporting -> Triple(
            "Saving video",
            "Keep this screen open while Halalify writes the video to your Gallery.",
            HalalifyAccent,
        )
        state.isSavedToGallery -> Triple(
            "Saved to Gallery",
            "A copy was saved in Movies/Halalify and kept in your Halalify Library.",
            HalalifySuccess,
        )
        state.isComplete -> Triple(
            "Temporary result",
            "Save it before leaving. Unsaved temporary files are deleted when you go back.",
            HalalifyTextTertiary,
        )
        else -> Triple(
            "Preview only",
            "You can watch ready chunks while processing continues.",
            HalalifyAccent,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (state.isSavedToGallery || state.isLibraryPlayback) {
                Icons.Default.CheckCircle
            } else {
                Icons.Default.Info
            },
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = HalalifyTextPrimary,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = HalalifyTextSecondary,
            )
        }
    }
}

@Composable
private fun FullscreenVideo(
    state: ProcessingState,
    onExitFullscreen: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (state.playablePaths.isNotEmpty()) {
            ChunkPlaylistPlayer(
                filePaths = state.playablePaths,
                onChunkChanged = {},
                isFullscreen = true,
                onToggleFullscreen = onExitFullscreen,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SystemBarsVisibilityEffect(isFullscreen: Boolean, context: android.content.Context) {
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(isFullscreen, window) {
        if (window == null) {
            onDispose {}
        } else {
            val controller = androidx.core.view.WindowCompat.getInsetsController(
                window,
                window.decorView,
            )
            if (isFullscreen) {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
