package com.halalify.kotlin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.halalify.kotlin.model.ProcessingState
import com.halalify.kotlin.ui.theme.HalalifyAccent
import com.halalify.kotlin.ui.theme.HalalifyError
import com.halalify.kotlin.ui.theme.HalalifySuccess
import com.halalify.kotlin.ui.theme.HalalifyTextOnAccent
import com.halalify.kotlin.ui.theme.HalalifyTextPrimary
import com.halalify.kotlin.ui.theme.HalalifyTextSecondary
import com.halalify.kotlin.ui.theme.HalalifyTextTertiary

@Composable
internal fun DownloadScreen(
    state: ProcessingState,
    isExporting: Boolean,
    onWatch: () -> Unit,
    onSaveToGallery: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Download,
            contentDescription = null,
            tint = if (state.errorMessage == null) HalalifyAccent else HalalifyError,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = when {
                state.errorMessage != null -> "Download Failed"
                state.isComplete -> "Video Downloaded"
                else -> "Downloading Video"
            },
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = when {
                state.errorMessage != null -> HalalifyError
                state.isComplete -> HalalifySuccess
                else -> HalalifyTextPrimary
            },
        )
        if (state.videoTitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = state.videoTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = HalalifyTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Original video • ${state.quality.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = HalalifyTextTertiary,
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (!state.isComplete && state.errorMessage == null) {
            CircularProgressIndicator(color = HalalifyAccent)
            Spacer(modifier = Modifier.height(20.dp))
        }
        Text(
            text = state.errorMessage ?: state.currentPhaseLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.errorMessage != null) HalalifyError else HalalifyTextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(36.dp))
        Button(
            onClick = onSaveToGallery,
            enabled = state.isComplete &&
                state.playablePaths.isNotEmpty() &&
                !state.isSavedToGallery &&
                !isExporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HalalifyAccent,
                contentColor = HalalifyTextOnAccent,
            ),
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Text(
                text = when {
                    isExporting -> "  Saving..."
                    state.isSavedToGallery -> "  Saved to Gallery"
                    state.isComplete -> "  Save to Gallery"
                    else -> "  Available after download"
                },
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onWatch,
            enabled = state.isComplete && state.playablePaths.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HalalifySuccess,
                contentColor = HalalifyTextOnAccent,
            ),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text("  Watch Video", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = HalalifyTextPrimary,
            ),
        ) {
            Text(if (state.errorMessage != null) "Try Another Video" else "Back")
        }
    }
}
