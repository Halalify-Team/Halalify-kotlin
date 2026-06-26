package com.halalify.kotlin.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halalify.kotlin.ui.theme.HalalifyAccent
import com.halalify.kotlin.ui.theme.HalalifyDark
import com.halalify.kotlin.ui.theme.HalalifyGradientEnd
import com.halalify.kotlin.ui.theme.HalalifyGradientStart
import com.halalify.kotlin.ui.theme.HalalifyTextOnAccent
import com.halalify.kotlin.ui.theme.HalalifyTextPrimary
import com.halalify.kotlin.ui.theme.HalalifyTextSecondary
import com.halalify.kotlin.ui.theme.HalalifyTextTertiary
import com.halalify.kotlin.model.VideoQuality
import com.halalify.kotlin.model.FormatDiscoveryState
import kotlinx.coroutines.delay

@Composable
internal fun InputScreen(
    sharedYoutubeUrl: String,
    backendUrl: String,
    devEmail: String,
    sessionToken: String,
    loginStatus: String,
    isLoggingIn: Boolean,
    formatDiscovery: FormatDiscoveryState,
    showDeveloperControls: Boolean,
    onBackendUrlChange: (String) -> Unit,
    onDevEmailChange: (String) -> Unit,
    onSessionTokenChange: (String) -> Unit,
    onDevLogin: () -> Unit,
    onGoogleLogin: () -> Unit,
    onSharedYoutubeUrlConsumed: () -> Unit,
    onDiscoverFormats: (youtubeUrl: String) -> Unit,
    onStartProcessing: (
        youtubeUrl: String,
        removeMusic: Boolean,
        blurWomen: Boolean,
        quality: VideoQuality,
    ) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToProfile: () -> Unit,
) {
    var youtubeUrl by remember { mutableStateOf(sharedYoutubeUrl) }
    var removeMusic by remember { mutableStateOf(true) }
    var blurWomen by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(VideoQuality.P360) }
    var qualityUrl by remember { mutableStateOf("") }
    var showDevSettings by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val normalizedUrl = youtubeUrl.trim()
    val formatsReadyForUrl = formatDiscovery.url == normalizedUrl &&
        formatDiscovery.availableQualities.isNotEmpty()

    LaunchedEffect(sharedYoutubeUrl) {
        if (sharedYoutubeUrl.isNotBlank()) {
            if (sharedYoutubeUrl != youtubeUrl) {
                youtubeUrl = sharedYoutubeUrl
            }
            onSharedYoutubeUrlConsumed()
        }
    }

    LaunchedEffect(normalizedUrl) {
        if (normalizedUrl.isBlank()) {
            onDiscoverFormats("")
        } else {
            delay(150)
            onDiscoverFormats(normalizedUrl)
        }
    }

    LaunchedEffect(formatDiscovery.url, formatDiscovery.availableQualities) {
        if (formatDiscovery.url == normalizedUrl &&
            formatDiscovery.availableQualities.isNotEmpty()
        ) {
            if (qualityUrl != normalizedUrl || quality !in formatDiscovery.availableQualities) {
                quality = formatDiscovery.availableQualities
                    .firstOrNull { it == VideoQuality.P720 }
                    ?: formatDiscovery.availableQualities
                        .filter { it.maxHeight <= VideoQuality.P720.maxHeight }
                        .lastOrNull()
                    ?: formatDiscovery.availableQualities.last()
                qualityUrl = normalizedUrl
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 80.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo / Brand
            Text(
                text = "🎬",
                fontSize = 64.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Halalify",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 36.sp,
                ),
                color = HalalifyTextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Remove music from any YouTube video",
                style = MaterialTheme.typography.bodyLarge,
                color = HalalifyTextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // URL Input
            OutlinedTextField(
                value = youtubeUrl,
                onValueChange = { youtubeUrl = it },
                label = { Text("Paste YouTube URL") },
                placeholder = { Text("https://youtube.com/watch?v=...") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { youtubeUrl = it }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            tint = HalalifyAccent,
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HalalifyAccent,
                    unfocusedBorderColor = HalalifyTextTertiary,
                    focusedLabelColor = HalalifyAccent,
                    cursorColor = HalalifyAccent,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = removeMusic,
                    onCheckedChange = { removeMusic = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = HalalifyAccent,
                        checkmarkColor = HalalifyTextOnAccent,
                    ),
                )
                Column {
                    Text(
                        text = "Remove music",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = HalalifyTextPrimary,
                    )
                    Text(
                        text = if (removeMusic) {
                            "Stream clean chunks while processing"
                        } else {
                            "Download the original video normally"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = HalalifyTextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = blurWomen,
                    onCheckedChange = { blurWomen = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = HalalifyAccent,
                        checkmarkColor = HalalifyTextOnAccent,
                    ),
                )
                Column {
                    Text(
                        text = "Blur women",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = HalalifyTextPrimary,
                    )
                    Text(
                        text = "Detects faces and blurs women locally on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = HalalifyTextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (removeMusic) {
                    "Video quality (clean streaming)"
                } else {
                    "Video quality"
                },
                style = MaterialTheme.typography.labelLarge,
                color = HalalifyTextSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                formatDiscovery.availableQualities.chunked(4).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowOptions.forEach { option ->
                            FilterChip(
                                selected = quality == option,
                                onClick = { quality = option },
                                label = { Text(option.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - rowOptions.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            when {
                formatDiscovery.isLoading && formatDiscovery.url == normalizedUrl -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = HalalifyAccent,
                        )
                        Text(
                            text = "Reading available qualities...",
                            style = MaterialTheme.typography.bodySmall,
                            color = HalalifyTextSecondary,
                        )
                    }
                }
                formatDiscovery.errorMessage != null &&
                    formatDiscovery.url == normalizedUrl -> {
                    Text(
                        text = formatDiscovery.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                formatsReadyForUrl -> {
                    Text(
                        text = formatDiscovery.videoTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = HalalifyTextTertiary,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                }
                else -> {
                    Text(
                        text = "Paste a valid YouTube URL to load its available qualities.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HalalifyTextTertiary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (sessionToken.isBlank()) {
                Button(
                    onClick = onGoogleLogin,
                    enabled = !isLoggingIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HalalifyAccent,
                        contentColor = HalalifyTextOnAccent,
                        disabledContainerColor = HalalifyTextTertiary.copy(alpha = 0.3f),
                    ),
                ) {
                    if (isLoggingIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = HalalifyTextOnAccent,
                        )
                    } else {
                        Text(
                            text = "Sign in with Google",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (loginStatus.isNotBlank()) {
                Text(
                    text = loginStatus.lineSequence().first(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loginStatus.startsWith("FAILED:", ignoreCase = true) ||
                        loginStatus.contains("expired", ignoreCase = true)
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        HalalifyTextSecondary
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Main CTA Button
            Button(
                onClick = { onStartProcessing(youtubeUrl, removeMusic, blurWomen, quality) },
                enabled = normalizedUrl.isNotBlank() &&
                    sessionToken.isNotBlank() &&
                    formatsReadyForUrl &&
                    quality in formatDiscovery.availableQualities,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HalalifyAccent,
                    contentColor = HalalifyTextOnAccent,
                    disabledContainerColor = HalalifyTextTertiary.copy(alpha = 0.3f),
                ),
            ) {
                Text(
                    text = when {
                        sessionToken.isBlank() -> "Sign in first"
                        removeMusic -> "✨ Halalify It"
                        else -> "Download Video"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            if (showDeveloperControls) {
                // Dev Settings Toggle
                TextButton(
                    onClick = { showDevSettings = !showDevSettings },
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = HalalifyTextTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (showDevSettings) "  Hide Dev Settings" else "  Dev Settings",
                        color = HalalifyTextTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AnimatedVisibility(
                visible = showDeveloperControls && showDevSettings,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = onBackendUrlChange,
                        label = { Text("Backend URL") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HalalifyAccent,
                            unfocusedBorderColor = HalalifyTextTertiary,
                            focusedLabelColor = HalalifyAccent,
                            cursorColor = HalalifyAccent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = devEmail,
                        onValueChange = onDevEmailChange,
                        label = { Text("Dev Email") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HalalifyAccent,
                            unfocusedBorderColor = HalalifyTextTertiary,
                            focusedLabelColor = HalalifyAccent,
                            cursorColor = HalalifyAccent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onDevLogin,
                        enabled = !isLoggingIn,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = HalalifyAccent,
                            )
                        } else {
                            Text("Dev Login", color = HalalifyTextPrimary)
                        }
                    }
                    OutlinedTextField(
                        value = sessionToken,
                        onValueChange = onSessionTokenChange,
                        label = { Text("Session Token") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HalalifyAccent,
                            unfocusedBorderColor = HalalifyTextTertiary,
                            focusedLabelColor = HalalifyAccent,
                            cursorColor = HalalifyAccent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (loginStatus.isNotBlank()) {
                        Text(
                            text = loginStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (loginStatus.startsWith("SUCCESS")) HalalifyAccent
                                    else HalalifyTextSecondary,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = onNavigateToProfile,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "Profile",
                    tint = HalalifyAccent,
                )
            }
            IconButton(
                onClick = onNavigateToLibrary,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = "Library",
                    tint = HalalifyAccent,
                )
            }
        }

        // Decorative gradient at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(HalalifyGradientStart, HalalifyGradientEnd, HalalifyGradientStart),
                        start = Offset(shimmerOffset, 0f),
                        end = Offset(shimmerOffset + 500f, 0f),
                    )
                )
                .align(Alignment.TopCenter),
        )
    }
}
