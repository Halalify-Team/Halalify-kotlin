package com.halalify.kotlin.ui

import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.halalify.kotlin.capture.CaptureSessionStore
import com.halalify.kotlin.capture.CaptureUiState
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurStyle
import com.halalify.kotlin.settings.BlurTarget

private val AppBackground = Color(0xFF071A1D)
private val AppSurface = Color(0xFF0D2529)
private val AppSurfaceHigh = Color(0xFF133137)
private val Accent = Color(0xFF72E4AE)
private val AccentSoft = Color(0xFF163D32)
private val TextPrimary = Color(0xFFF2FAF7)
private val TextSecondary = Color(0xFFA9BFBA)
private val Outline = Color(0xFF26464B)
private val Danger = Color(0xFFFFB4AB)
private val DangerContainer = Color(0xFF5F2024)

private val HalalifyColorScheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = AppBackground,
    primaryContainer = AccentSoft,
    onPrimaryContainer = TextPrimary,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = AppSurface,
    onSurface = TextPrimary,
    surfaceVariant = AppSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = Danger,
    errorContainer = DangerContainer,
)

@Composable
internal fun HalalifyApp(
    initialSettings: BlurSettings,
    onSave: (BlurSettings) -> Unit,
    onStartCapture: (BlurSettings) -> Unit,
    onStopCapture: () -> Unit,
) {
    var target by remember(initialSettings) { mutableStateOf(initialSettings.target) }
    var blurImages by remember(initialSettings) { mutableStateOf(initialSettings.blurImages) }
    var blurVideos by remember(initialSettings) { mutableStateOf(initialSettings.blurVideos) }
    var blurStyle by remember(initialSettings) { mutableStateOf(initialSettings.style) }
    var isolateMusic by remember(initialSettings) { mutableStateOf(initialSettings.isolateMusic) }
    val captureState by CaptureSessionStore.state.collectAsState()
    val currentSettings = BlurSettings(target, blurImages, blurVideos, blurStyle, isolateMusic)

    LaunchedEffect(currentSettings) {
        onSave(currentSettings)
    }

    MaterialTheme(colorScheme = HalalifyColorScheme) {
        Scaffold(
            containerColor = AppBackground,
            topBar = { AppHeader(isCapturing = captureState.isCapturing) },
            bottomBar = {
                PrimaryActionBar(
                    isCapturing = captureState.isCapturing,
                    settings = currentSettings,
                    onStartCapture = onStartCapture,
                    onStopCapture = onStopCapture,
                )
            },
        ) { scaffoldPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
                contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item { CaptureStatusCard(captureState = captureState, target = target) }

                captureState.previewJpeg?.let { jpeg ->
                    item { ScreenPreview(jpeg = jpeg) }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeading(
                            eyebrow = "PROTECTION PROFILE",
                            title = "Choose what to protect",
                            description = if (captureState.isCapturing) {
                                "Stop protection before changing these preferences."
                            } else {
                                "These preferences are saved automatically on this device."
                            },
                        )
                        TargetSelector(
                            selected = target,
                            enabled = !captureState.isCapturing,
                            onSelect = { target = it },
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeading(
                            eyebrow = "APPEARANCE",
                            title = "Censor style",
                            description = "Select how protected detections should appear on screen.",
                        )
                        StyleSelector(
                            selected = blurStyle,
                            enabled = !captureState.isCapturing,
                            onSelect = { blurStyle = it },
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeading(
                            eyebrow = "CONTENT",
                            title = "Protection coverage",
                            description = "Fine-tune which media Halalify monitors locally.",
                        )
                        PreferenceCard(enabled = !captureState.isCapturing) {
                            PreferenceToggle(
                                shortLabel = "IMG",
                                title = "Images",
                                description = "Protect detections in still images.",
                                checked = blurImages,
                                enabled = !captureState.isCapturing,
                                onCheckedChange = { blurImages = it },
                            )
                            HorizontalDivider(color = Outline)
                            PreferenceToggle(
                                shortLabel = "VID",
                                title = "Video",
                                description = "Protect detections in changing frames.",
                                checked = blurVideos,
                                enabled = !captureState.isCapturing,
                                onCheckedChange = { blurVideos = it },
                            )
                            HorizontalDivider(color = Outline)
                            PreferenceToggle(
                                shortLabel = "AUD",
                                title = "Music isolation",
                                description = "Monitor eligible playback with the optional local audio model.",
                                checked = isolateMusic,
                                enabled = !captureState.isCapturing,
                                onCheckedChange = { isolateMusic = it },
                            )
                        }
                    }
                }

                item { PrivacyNote() }
            }
        }
    }
}

@Composable
private fun AppHeader(isCapturing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppBackground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "H",
                color = AppBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Halalify",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Text(
                text = "Private, on-device protection",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
        StatusPill(
            label = if (isCapturing) "LIVE" else "READY",
            isActive = isCapturing,
        )
    }
}

@Composable
private fun CaptureStatusCard(captureState: CaptureUiState, target: BlurTarget) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (captureState.isCapturing) AccentSoft else AppSurface,
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            width = 1.dp,
            brush = androidx.compose.ui.graphics.SolidColor(
                if (captureState.isCapturing) Accent.copy(alpha = 0.45f) else Outline,
            ),
        ),
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (captureState.isCapturing) Accent else AppSurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (captureState.isCapturing) "ON" else "OFF",
                        color = if (captureState.isCapturing) AppBackground else TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (captureState.isCapturing) "Protection is active" else "Ready to protect",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                    Text(
                        text = if (captureState.isCapturing) {
                            captureState.targetLabel ?: "Blur target: ${target.title}"
                        } else {
                            "Current target: ${target.title}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (captureState.isCapturing) Accent else TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = if (captureState.isCapturing) Accent.copy(alpha = 0.20f) else Outline)
            Spacer(Modifier.height(16.dp))
            Text(
                text = captureState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            captureState.audioStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, isActive: Boolean) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isActive) AccentSoft else AppSurfaceHigh)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (isActive) Accent else TextSecondary),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (isActive) Accent else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
        )
    }
}

@Composable
private fun SectionHeading(eyebrow: String, title: String, description: String) {
    Column {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TargetSelector(
    selected: BlurTarget,
    enabled: Boolean,
    onSelect: (BlurTarget) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BlurTarget.entries.forEach { option ->
            SelectableTile(
                title = option.title,
                description = option.description,
                selected = selected == option,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun SelectableTile(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) AccentSoft else AppSurface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Accent else Outline,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            SelectionIndicator(selected = selected)
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 10.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StyleSelector(
    selected: BlurStyle,
    enabled: Boolean,
    onSelect: (BlurStyle) -> Unit,
) {
    Column(
        modifier = Modifier.alpha(if (enabled) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BlurStyle.entries.forEach { option ->
            val isSelected = selected == option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) AccentSoft else AppSurface)
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) Accent else Outline,
                        shape = RoundedCornerShape(18.dp),
                    )
                    .clickable(
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                SelectionIndicator(selected = isSelected)
            }
        }
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 6.dp else 1.5.dp,
                color = if (selected) Accent else TextSecondary,
                shape = CircleShape,
            ),
    )
}

@Composable
private fun PreferenceCard(enabled: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(22.dp))
            .background(AppSurface)
            .border(1.dp, Outline, RoundedCornerShape(22.dp)),
    ) {
        content()
    }
}

@Composable
private fun PreferenceToggle(
    shortLabel: String,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AppSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = shortLabel,
                color = Accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppBackground,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = AppSurfaceHigh,
                uncheckedBorderColor = Outline,
            ),
        )
    }
}

@Composable
private fun ScreenPreview(jpeg: ByteArray) {
    Column {
        SectionHeading(
            eyebrow = "LIVE PREVIEW",
            title = "Protected screen",
            description = "A private preview of the same protection drawn over the shared screen.",
        )
        Spacer(Modifier.height(14.dp))
        AndroidView(
            factory = {
                ImageView(it).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { view ->
                view.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(22.dp))
                .background(AppSurface)
                .border(1.dp, Outline, RoundedCornerShape(22.dp)),
        )
    }
}

@Composable
private fun PrivacyNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppSurface.copy(alpha = 0.65f))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(Accent),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "Your screen stays private",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Detection runs on your device. Preview frames are never saved or uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun PrimaryActionBar(
    isCapturing: Boolean,
    settings: BlurSettings,
    onStartCapture: (BlurSettings) -> Unit,
    onStopCapture: () -> Unit,
) {
    Surface(
        color = AppBackground.copy(alpha = 0.98f),
        shadowElevation = 14.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = {
                    if (isCapturing) onStopCapture() else onStartCapture(settings)
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCapturing) DangerContainer else Accent,
                    contentColor = if (isCapturing) Danger else AppBackground,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (isCapturing) "Stop protection" else "Start protection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = if (isCapturing) {
                    "Protection is running in the background"
                } else {
                    "Android will ask for overlay and screen-sharing access"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}
