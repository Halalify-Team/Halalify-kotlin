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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.ContentScale
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halalify.kotlin.ui.components.HalalifyLogo
import com.halalify.kotlin.ui.theme.HalalifyAccent
import com.halalify.kotlin.ui.theme.HalalifyAccentGold
import com.halalify.kotlin.ui.theme.HalalifyDark
import com.halalify.kotlin.ui.theme.HalalifyDarkCard
import com.halalify.kotlin.ui.theme.HalalifyGradientEnd
import com.halalify.kotlin.ui.theme.HalalifyGradientStart
import com.halalify.kotlin.ui.theme.HalalifyTextOnAccent
import com.halalify.kotlin.ui.theme.HalalifyTextPrimary
import com.halalify.kotlin.ui.theme.HalalifyTextSecondary
import com.halalify.kotlin.ui.theme.HalalifyTextTertiary
import coil.compose.AsyncImage
import com.halalify.kotlin.model.VideoQuality
import com.halalify.kotlin.model.FormatDiscoveryState
import com.halalify.kotlin.model.QuotaState
import com.halalify.kotlin.model.BlurStrictness
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
    quotaState: com.halalify.kotlin.model.QuotaState,
    showDeveloperControls: Boolean,
    onBackendUrlChange: (String) -> Unit,
    onDevEmailChange: (String) -> Unit,
    onSessionTokenChange: (String) -> Unit,
    onDevLogin: () -> Unit,
    onSignInToProcess: (String, Boolean, Boolean, VideoQuality, BlurStrictness) -> Unit,
    onSharedYoutubeUrlConsumed: () -> Unit,
    onDiscoverFormats: (youtubeUrl: String) -> Unit,
    onStartProcessing: (
        youtubeUrl: String,
        removeMusic: Boolean,
        blurWomen: Boolean,
        quality: VideoQuality,
        blurStrictness: BlurStrictness,
    ) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToProfile: () -> Unit,
) {
    var youtubeUrl by remember { mutableStateOf(sharedYoutubeUrl) }
    var removeMusic by remember { mutableStateOf(true) }
    var blurWomen by remember { mutableStateOf(false) }
    var blurStrictness by remember { mutableStateOf(BlurStrictness.BALANCED) }
    var quality by remember { mutableStateOf(VideoQuality.P360) }
    var qualityUrl by remember { mutableStateOf("") }
    var showDevSettings by remember { mutableStateOf(false) }
    var showNotificationRationale by remember { mutableStateOf(false) }
    var showPreFlightSummary by remember { mutableStateOf(false) }
    var pendingProcessing by remember { mutableStateOf<PendingProcessing?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingProcessing?.let { params ->
            if (!granted) {
                Toast.makeText(
                    context,
                    "Processing will continue in the app. Enable notifications in Settings for background updates.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            onStartProcessing(params.url, params.removeMusic, params.blurWomen, params.quality, params.blurStrictness)
            pendingProcessing = null
        }
        showNotificationRationale = false
    }

    fun startProcessingWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingProcessing = PendingProcessing(
                url = youtubeUrl,
                removeMusic = removeMusic,
                blurWomen = blurWomen,
                quality = quality,
                blurStrictness = blurStrictness,
            )
            showNotificationRationale = true
        } else {
            onStartProcessing(youtubeUrl, removeMusic, blurWomen, quality, blurStrictness)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 64.dp, bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HalalifyBrand()

            Spacer(modifier = Modifier.height(28.dp))

            HeroUrlCard(
                youtubeUrl = youtubeUrl,
                onUrlChange = { youtubeUrl = it },
                onPaste = { clipboardManager.getText()?.text?.let { youtubeUrl = it } },
                formatDiscovery = formatDiscovery,
                normalizedUrl = normalizedUrl,
                formatsReadyForUrl = formatsReadyForUrl,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OptionsCard(
                removeMusic = removeMusic,
                onRemoveMusicChange = { removeMusic = it },
                blurWomen = blurWomen,
                onBlurWomenChange = { blurWomen = it },
                blurStrictness = blurStrictness,
                onBlurStrictnessChange = { blurStrictness = it },
            )

            Spacer(modifier = Modifier.height(16.dp))

            QualityCard(
                removeMusic = removeMusic,
                availableQualities = formatDiscovery.availableQualities,
                quality = quality,
                onQualityChange = { quality = it },
                formatDiscovery = formatDiscovery,
                normalizedUrl = normalizedUrl,
                formatsReadyForUrl = formatsReadyForUrl,
            )

            Spacer(modifier = Modifier.height(20.dp))

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

            val isSignedOut = sessionToken.isBlank()
            val mainCtaEnabled = normalizedUrl.isNotBlank() &&
                formatsReadyForUrl &&
                quality in formatDiscovery.availableQualities &&
                !isLoggingIn
            val mainCtaOnClick: () -> Unit = {
                if (mainCtaEnabled) {
                    showPreFlightSummary = true
                }
            }

            if (isSignedOut) {
                SignRequiredHint()
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Main CTA Button (also serves as sign-in trigger when signed out)
            Button(
                onClick = mainCtaOnClick,
                enabled = mainCtaEnabled,
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
                if (isSignedOut && isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = HalalifyTextOnAccent,
                    )
                } else {
                    Text(
                        text = when {
                            isSignedOut -> "Sign in & Halalify It"
                            removeMusic || blurWomen -> "✨ Halalify It"
                            else -> "Download Video"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

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

        if (showNotificationRationale) {
            AlertDialog(
                onDismissRequest = {
                    showNotificationRationale = false
                    pendingProcessing = null
                },
                title = { Text("Stay updated while Halalify works") },
                text = {
                    Text(
                        "Allow notifications so Halalify can show progress and let you know when your clean video is ready, even if you switch apps.",
                        color = HalalifyTextSecondary,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HalalifyAccent,
                            contentColor = HalalifyTextOnAccent,
                        ),
                    ) {
                        Text("Allow")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showNotificationRationale = false
                            pendingProcessing?.let { params ->
                                onStartProcessing(
                                    params.url,
                                    params.removeMusic,
                                    params.blurWomen,
                                    params.quality,
                                    params.blurStrictness,
                                )
                            }
                            pendingProcessing = null
                        },
                    ) {
                        Text("No thanks", color = HalalifyTextSecondary)
                    }
                },
                containerColor = HalalifyDarkCard,
                titleContentColor = HalalifyTextPrimary,
                textContentColor = HalalifyTextSecondary,
            )
        }

        if (showPreFlightSummary) {
            PreFlightSummaryDialog(
                formatDiscovery = formatDiscovery,
                quality = quality,
                removeMusic = removeMusic,
                blurWomen = blurWomen,
                blurStrictness = blurStrictness,
                quotaState = quotaState,
                isSignedIn = sessionToken.isNotBlank(),
                onConfirm = {
                    showPreFlightSummary = false
                    if (sessionToken.isBlank()) {
                        onSignInToProcess(
                            youtubeUrl,
                            removeMusic,
                            blurWomen,
                            quality,
                            blurStrictness,
                        )
                    } else {
                        startProcessingWithPermissionCheck()
                    }
                },
                onDismiss = { showPreFlightSummary = false },
            )
        }
    }
}

@Composable
private fun HalalifyBrand() {
    HalalifyLogo(size = 72.dp)
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = "Halalify",
        style = MaterialTheme.typography.headlineLarge.copy(
            fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp,
        ),
        color = HalalifyTextPrimary,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Remove music from any YouTube video",
        style = MaterialTheme.typography.bodyMedium,
        color = HalalifyTextSecondary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun HalalifyCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(HalalifyDarkCard.copy(alpha = 0.6f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun HeroUrlCard(
    youtubeUrl: String,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    formatDiscovery: FormatDiscoveryState,
    normalizedUrl: String,
    formatsReadyForUrl: Boolean,
) {
    HalalifyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.ContentPaste,
                contentDescription = null,
                tint = HalalifyAccent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "YouTube link",
                style = MaterialTheme.typography.labelLarge,
                color = HalalifyTextSecondary,
            )
        }
        OutlinedTextField(
            value = youtubeUrl,
            onValueChange = onUrlChange,
            placeholder = { Text("https://youtube.com/watch?v=...") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onPaste) {
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
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            formatDiscovery.isLoading && formatDiscovery.url == normalizedUrl -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
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
                VideoPreviewCard(
                    thumbnailUrl = formatDiscovery.thumbnailUrl,
                    title = formatDiscovery.videoTitle,
                    channelName = formatDiscovery.channelName,
                    durationSeconds = formatDiscovery.durationSeconds,
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
    }
}

@Composable
private fun OptionsCard(
    removeMusic: Boolean,
    onRemoveMusicChange: (Boolean) -> Unit,
    blurWomen: Boolean,
    onBlurWomenChange: (Boolean) -> Unit,
    blurStrictness: BlurStrictness,
    onBlurStrictnessChange: (BlurStrictness) -> Unit,
) {
    HalalifyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Options",
                style = MaterialTheme.typography.labelLarge,
                color = HalalifyTextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (removeMusic || blurWomen) "Halalify mode" else "Original download",
                style = MaterialTheme.typography.labelSmall,
                color = if (removeMusic || blurWomen) HalalifyAccent else HalalifyTextTertiary,
            )
        }
        OptionToggleRow(
            title = "Remove music",
            subtitle = when {
                removeMusic -> "Stream clean chunks while processing"
                blurWomen -> "Stream blurred chunks while processing"
                else -> "Download the original video normally"
            },
            checked = removeMusic,
            onCheckedChange = onRemoveMusicChange,
        )
        OptionToggleRow(
            title = "Blur women",
            subtitle = "Detects faces and blurs women locally on this device",
            checked = blurWomen,
            onCheckedChange = onBlurWomenChange,
        )
        AnimatedVisibility(
            visible = blurWomen,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Blur strictness",
                    style = MaterialTheme.typography.labelMedium,
                    color = HalalifyTextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BlurStrictness.values().forEach { option ->
                        FilterChip(
                            selected = blurStrictness == option,
                            onClick = { onBlurStrictnessChange(option) },
                            label = {
                                Text(
                                    text = option.label,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = HalalifyTextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = HalalifyTextSecondary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HalalifyTextOnAccent,
                checkedTrackColor = HalalifyAccent,
                checkedBorderColor = HalalifyAccent,
                uncheckedThumbColor = HalalifyTextSecondary,
                uncheckedTrackColor = HalalifyDarkCard,
                uncheckedBorderColor = HalalifyTextTertiary,
            ),
        )
    }
}

@Composable
private fun QualityCard(
    removeMusic: Boolean,
    availableQualities: List<VideoQuality>,
    quality: VideoQuality,
    onQualityChange: (VideoQuality) -> Unit,
    formatDiscovery: FormatDiscoveryState,
    normalizedUrl: String,
    formatsReadyForUrl: Boolean,
) {
    HalalifyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (removeMusic) "Video quality (clean streaming)" else "Video quality",
                style = MaterialTheme.typography.labelLarge,
                color = HalalifyTextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (availableQualities.isNotEmpty()) {
                Text(
                    text = "${availableQualities.size} options",
                    style = MaterialTheme.typography.labelSmall,
                    color = HalalifyTextTertiary,
                )
            }
        }
        if (availableQualities.isEmpty()) {
            Text(
                text = "Paste a YouTube URL to load available qualities.",
                style = MaterialTheme.typography.bodySmall,
                color = HalalifyTextTertiary,
            )
        } else {
            availableQualities.chunked(4).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowOptions.forEach { option ->
                        FilterChip(
                            selected = quality == option,
                            onClick = { onQualityChange(option) },
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
    }
}

@Composable
private fun VideoPreviewCard(
    thumbnailUrl: String,
    title: String,
    channelName: String,
    durationSeconds: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HalalifyDarkCard.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = thumbnailUrl.takeIf { it.isNotBlank() },
            contentDescription = null,
            modifier = Modifier
                .size(width = 120.dp, height = 68.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(HalalifyDarkCard),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = HalalifyTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (channelName.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = HalalifyTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatDuration(durationSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = HalalifyAccent,
            )
        }
    }
}

@Composable
private fun PreFlightSummaryDialog(
    formatDiscovery: FormatDiscoveryState,
    quality: VideoQuality,
    removeMusic: Boolean,
    blurWomen: Boolean,
    blurStrictness: BlurStrictness,
    quotaState: QuotaState,
    isSignedIn: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val videoMinutes = formatDiscovery.durationSeconds / 60.0
    val estimatedSeconds = estimateProcessingSeconds(
        durationSeconds = formatDiscovery.durationSeconds,
        blurWomen = blurWomen,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ready to Halalify?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = formatDiscovery.thumbnailUrl.takeIf { it.isNotBlank() },
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HalalifyDarkCard),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    text = formatDiscovery.videoTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = HalalifyTextPrimary,
                )
                if (formatDiscovery.channelName.isNotBlank()) {
                    Text(
                        text = formatDiscovery.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = HalalifyTextSecondary,
                    )
                }
                InfoRow(label = "Duration", value = formatDuration(formatDiscovery.durationSeconds))
                InfoRow(label = "Quality", value = quality.label)
                InfoRow(
                    label = "Music removal",
                    value = if (removeMusic) "Yes" else "No",
                )
                InfoRow(
                    label = "Blur women",
                    value = if (blurWomen) "Yes (${blurStrictness.label})" else "No",
                )
                InfoRow(
                    label = "Estimated time",
                    value = "About ${formatDuration(estimatedSeconds)}",
                )
                if (isSignedIn && quotaState.hasLiveData) {
                    val remaining = quotaState.minutesRemaining ?: 0.0
                    InfoRow(
                        label = "Quota used",
                        value = "${"%.1f".format(videoMinutes)} min · ${"%.1f".format(remaining)} min left",
                    )
                } else {
                    Text(
                        text = "Quota will be checked after sign-in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HalalifyTextTertiary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HalalifyAccent,
                    contentColor = HalalifyTextOnAccent,
                ),
            ) {
                Text(if (isSignedIn) "Start processing" else "Sign in & Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HalalifyTextSecondary)
            }
        },
        containerColor = HalalifyDarkCard,
        titleContentColor = HalalifyTextPrimary,
        textContentColor = HalalifyTextSecondary,
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = HalalifyTextTertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = HalalifyTextSecondary,
        )
    }
}

private fun estimateProcessingSeconds(durationSeconds: Int, blurWomen: Boolean): Int {
    val multiplier = if (blurWomen) 5 else 2
    return (durationSeconds * multiplier).coerceAtLeast(15)
}

private fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes > 0 && seconds > 0 -> "${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

private data class PendingProcessing(
    val url: String,
    val removeMusic: Boolean,
    val blurWomen: Boolean,
    val quality: VideoQuality,
    val blurStrictness: BlurStrictness,
)

@Composable
private fun SignRequiredHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HalalifyAccentGold.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            tint = HalalifyAccentGold,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = "Sign in required",
                style = MaterialTheme.typography.labelLarge,
                color = HalalifyAccentGold,
            )
            Text(
                text = "Tap below to sign in with Google and start halalifying videos.",
                style = MaterialTheme.typography.bodySmall,
                color = HalalifyTextSecondary,
            )
        }
    }
}
