package com.halalify.kotlin.ui

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import com.halalify.kotlin.capture.CaptureUiState
import com.halalify.kotlin.settings.AppThemeMode
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurStyle
import com.halalify.kotlin.settings.BlurTarget
import com.halalify.kotlin.settings.MIN_BLUR_INTENSITY
import com.halalify.kotlin.settings.normalizeBlurIntensity

private val StopRed = Color(0xFFC62828)

private val HalalifyDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF57D59A),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF104B34),
    onPrimaryContainer = Color(0xFFD1F7E2),
    background = Color(0xFF06140E),
    onBackground = Color(0xFFF0F8F3),
    surface = Color(0xFF0B2017),
    onSurface = Color(0xFFF0F8F3),
    surfaceVariant = Color(0xFF143326),
    onSurfaceVariant = Color(0xFFB5C9BD),
    outline = Color(0xFF526F60),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5F2024),
)

private val HalalifyLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0F5D3E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1F3DF),
    onPrimaryContainer = Color(0xFF073B27),
    background = Color(0xFFF4F8F4),
    onBackground = Color(0xFF142019),
    surface = Color.White,
    onSurface = Color(0xFF142019),
    surfaceVariant = Color(0xFFE2EDE5),
    onSurfaceVariant = Color(0xFF4B6154),
    outline = Color(0xFF73877B),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val AppBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val AppSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surface
private val AppSurfaceHigh: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val Accent: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val AccentSoft: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer
private val TextPrimary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
private val TextSecondary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Outline: Color
    @Composable get() = MaterialTheme.colorScheme.outline
@Composable
internal fun HalalifyApp(
    initialSettings: BlurSettings,
    captureState: CaptureUiState,
    onSave: (BlurSettings) -> Unit,
    onStartCapture: (BlurSettings) -> Unit,
    onStopCapture: () -> Unit,
    onStartIsolation: (BlurSettings) -> Unit = {},
    websiteFilterEnabled: Boolean = initialSettings.blockAdultSites,
) {
    var settings by remember(initialSettings) { mutableStateOf(initialSettings) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val displayName = runCatching {
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
                cursor?.use {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && it.moveToFirst()) it.getString(index) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "selected_media"
            settings = settings.copy(
                musicSourceUri = uri.toString(),
                musicSourceFileName = displayName,
            )
        },
    )

    LaunchedEffect(settings, websiteFilterEnabled) {
        onSave(settings.copy(blockAdultSites = websiteFilterEnabled))
    }

    val useDarkTheme = when (settings.themeMode) {
        AppThemeMode.NORMAL -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    MaterialTheme(
        colorScheme = if (useDarkTheme) HalalifyDarkColorScheme else HalalifyLightColorScheme,
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground),
            containerColor = AppBackground,
            topBar = {
                AppHeader()
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = 12.dp,
                    end = 20.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item {
                    CaptureStatusCard(
                        captureState = captureState,
                        onToggleProtection = {
                            if (captureState.isCapturing) onStopCapture() else onStartCapture(settings)
                        },
                    )
                }

                captureState.previewJpeg?.let { jpeg ->
                    item { ScreenPreview(jpeg = jpeg) }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeading(
                            eyebrow = "PROTECTION PROFILE",
                            title = "Choose who to blur",
                            description = if (captureState.isCapturing) {
                                "Stop to edit."
                            } else {
                                "Select who to blur."
                            },
                        )
                        TargetSelector(
                            selected = settings.target,
                            enabled = !captureState.isCapturing,
                            onSelect = { target -> settings = settings.copy(target = target) },
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
                        PreferenceCard(enabled = true) {
                            PreferenceToggle(
                                shortLabel = "IMG",
                                title = "Images",
                                description = "Protect detections in still images.",
                                checked = settings.blurImages,
                                enabled = !captureState.isCapturing,
                                onCheckedChange = { enabled ->
                                    settings = settings.copy(blurImages = enabled)
                                },
                            )
                            HorizontalDivider(color = Outline)
                            PreferenceToggle(
                                shortLabel = "VID",
                                title = "Video",
                                description = "Protect detections in changing frames.",
                                checked = settings.blurVideos,
                                enabled = !captureState.isCapturing,
                                onCheckedChange = { enabled ->
                                    settings = settings.copy(blurVideos = enabled)
                                },
                            )
                            HorizontalDivider(color = Outline)
                            PreferenceToggle(
                                shortLabel = "AUD",
                                title = "Music isolation",
                                description = "Mute detected music during protected playback.",
                                checked = settings.isolateMusic,
                                enabled = !captureState.isCapturing,
                                onCheckedChange = { enabled ->
                                    settings = settings.copy(isolateMusic = enabled)
                                },
                            )
                        }
                    }
                }

                if (settings.isolateMusic) {
                    item {
                        MusicIsolationSourceCard(
                            settings = settings,
                            enabled = !captureState.isCapturing,
                            isolationStatus = captureState.audioStatus,
                            onUrlChange = { url -> settings = settings.copy(musicSourceUrl = url) },
                            onOpenFilePicker = {
                                filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
                            },
                            onStartIsolation = { onStartIsolation(settings) },
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeading(
                            eyebrow = "APPEARANCE",
                            title = "Make Halalify yours",
                            description = if (captureState.isCapturing) {
                                "Theme and blur changes apply immediately."
                            } else {
                                "Choose the app theme and how protected areas appear."
                            },
                        )
                        ThemeModeSelector(
                            selected = settings.themeMode,
                            onSelect = { mode -> settings = settings.copy(themeMode = mode) },
                        )
                        BlurStyleSelector(
                            selected = settings.style,
                            enabled = true,
                            onSelect = { style ->
                                settings = settings.copy(style = style)
                            },
                        )
                        BlurIntensitySelector(
                            intensity = settings.intensity,
                            enabled = true,
                            onIntensityChange = { intensity ->
                                settings = settings.copy(intensity = intensity)
                            },
                        )
                    }
                }

                item { PrivacyNote() }
            }
        }
    }
}

@Composable
private fun AppHeader() {
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
        Text(
            text = "Halalify",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
    }
}

@Composable
private fun CaptureStatusCard(
    captureState: CaptureUiState,
    onToggleProtection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val buttonColor = if (captureState.isCapturing) Accent else StopRed
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = onToggleProtection,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = Color.White,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 10.dp,
                ),
                border = null,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(72.dp),
            ) {
                Text(
                    text = if (captureState.isCapturing) "ON" else "OFF",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
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
            .selectableGroup()
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
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                stateDescription = if (selected) "Selected" else "Not selected"
            }
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
private fun MusicIsolationSourceCard(
    settings: BlurSettings,
    enabled: Boolean,
    isolationStatus: String?,
    onUrlChange: (String) -> Unit,
    onOpenFilePicker: () -> Unit,
    onStartIsolation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(22.dp))
            .background(AppSurface)
            .border(1.dp, Outline, RoundedCornerShape(22.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Remove music from a file",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Choose a media file or direct MP4/M4A link to create a speech-focused copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (settings.hasMusicIsolationSource) {
                Text(
                    text = "Ready",
                    color = Accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        OutlinedTextField(
            value = settings.musicSourceUrl,
            onValueChange = onUrlChange,
            enabled = enabled && settings.isolateMusic,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://example.com/video.mp4 or audio.m4a") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Outline,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent,
                focusedPlaceholderColor = TextSecondary,
                unfocusedPlaceholderColor = TextSecondary,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOpenFilePicker,
                enabled = enabled && settings.isolateMusic,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppSurfaceHigh,
                    contentColor = TextPrimary,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Choose file")
            }
            Text(
                text = settings.musicSourceFileName.ifBlank { "No file selected" },
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Button(
            onClick = onStartIsolation,
            enabled = enabled && settings.isolateMusic && settings.hasMusicIsolationSource,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = AppBackground,
                disabledContainerColor = AppSurfaceHigh,
                disabledContentColor = TextSecondary,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (settings.isolateMusic && settings.hasMusicIsolationSource) {
                    "Create speech-only copy"
                } else {
                    "Add source to start"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        isolationStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selected: AppThemeMode,
    onSelect: (AppThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppSurface)
            .border(1.dp, Outline, RoundedCornerShape(22.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Color mode",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppThemeMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) AccentSoft else AppSurfaceHigh)
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) Accent else Outline,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(mode) },
                        )
                        .semantics {
                            stateDescription = if (isSelected) "Selected" else "Not selected"
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectionIndicator(selected = isSelected)
                    Text(
                        text = mode.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) Accent else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}
@Composable
private fun BlurStyleSelector(
    selected: BlurStyle,
    enabled: Boolean,
    onSelect: (BlurStyle) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BlurStyle.entries.forEach { option ->
                BlurStyleTile(
                    style = option,
                    selected = selected == option,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(option) },
                )
            }
        }
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BlurStyleTile(
    style: BlurStyle,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AccentSoft else AppSurface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Accent else Outline,
                shape = RoundedCornerShape(18.dp),
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f),
        ) {
            BlurStylePreview(style = style)
        }
        Text(
            text = style.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Accent else TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun BlurStylePreview(style: BlurStyle) {
    val previewShape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(previewShape)
            .background(AppSurfaceHigh),
    ) {
        when (style) {
            BlurStyle.SOFT_BLUR -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFB7C7C5),
                                    Color(0xFFE29B62),
                                    Color(0xFF365D78),
                                ),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                )
            }

            BlurStyle.PIXELATED -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                ) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        PreviewBlock(Color(0xFF8A664D), Modifier.weight(1f))
                        PreviewBlock(Color(0xFFC88255), Modifier.weight(1f))
                        PreviewBlock(Color(0xFF6A89A8), Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        PreviewBlock(Color(0xFF5C6C76), Modifier.weight(1f))
                        PreviewBlock(Color(0xFFB36D49), Modifier.weight(1f))
                        PreviewBlock(Color(0xFF315A82), Modifier.weight(1f))
                    }
                }
            }

            BlurStyle.SOLID -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                )
            }
        }
    }
}

@Composable
private fun PreviewBlock(color: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color),
    )
}

@Composable
private fun BlurIntensitySelector(
    intensity: Float,
    enabled: Boolean,
    onIntensityChange: (Float) -> Unit,
) {
    val displayedIntensity = normalizeBlurIntensity(intensity)
    val level = (displayedIntensity * 4f)
        .roundToInt() + 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(22.dp))
            .background(AppSurface)
            .border(1.dp, Outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Blur strength",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                text = when {
                    level >= 4 -> "Dense protection"
                    level <= 2 -> "Light protection"
                    else -> "Balanced protection"
                },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = "Level $level/5",
                style = MaterialTheme.typography.titleMedium,
                color = Accent,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = displayedIntensity,
            onValueChange = onIntensityChange,
            enabled = enabled,
            valueRange = MIN_BLUR_INTENSITY..1f,
            steps = 3,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = AppSurfaceHigh,
                disabledThumbColor = TextSecondary,
                disabledActiveTrackColor = TextSecondary.copy(alpha = 0.45f),
                disabledInactiveTrackColor = AppSurfaceHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Level 1",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Level 5",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
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
