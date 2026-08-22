package com.halalify.kotlin.ui

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import com.halalify.kotlin.R
import com.halalify.kotlin.capture.CaptureUiState
import com.halalify.kotlin.settings.AppLanguage
import com.halalify.kotlin.settings.AppThemeMode
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurStyle
import com.halalify.kotlin.settings.BlurTarget
import com.halalify.kotlin.settings.MIN_BLUR_INTENSITY
import com.halalify.kotlin.settings.normalizeBlurIntensity

private val StopRed = Color(0xFFC62828)
private const val MUSIC_ISOLATION_AVAILABLE = false

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

private data class UiStrings(
    val quickControls: String,
    val protectionProfile: String,
    val quickControlsDescription: String,
    val settings: String,
    val settingsTitle: String,
    val settingsDescription: String,
    val monitoring: String,
    val monitoringDescription: String,
    val images: String,
    val imagesDescription: String,
    val video: String,
    val videoDescription: String,
    val musicIsolation: String,
    val musicIsolationDescription: String,
    val comingSoon: String,
    val websiteProtection: String,
    val websiteProtectionDescription: String,
    val active: String,
    val automatic: String,
    val appearance: String,
    val appearanceDescription: String,
    val colorMode: String,
    val edit: String,
    val done: String,
    val language: String,
    val languageDescription: String,
    val back: String,
    val openSettings: String,
    val on: String,
    val off: String,
    val musicCardTitle: String,
    val musicCardDescription: String,
    val ready: String,
    val mediaUrlPlaceholder: String,
    val chooseFile: String,
    val noFile: String,
    val createSpeechCopy: String,
    val addSource: String,
    val blurStrength: String,
    val denseProtection: String,
    val lightProtection: String,
    val balancedProtection: String,
    val levelLabel: (Int) -> String,
    val levelOne: String,
    val levelFive: String,
    val protectedScreen: String,
    val protectedScreenDescription: String,
    val privateTitle: String,
    val privateDescription: String,
)

private val EnglishUi = UiStrings(
    quickControls = "QUICK CONTROLS",
    protectionProfile = "Protection profile",
    quickControlsDescription = "Choose who to blur and adjust the protection effect.",
    settings = "SETTINGS",
    settingsTitle = "Everything in one place",
    settingsDescription = "Manage monitoring, audio isolation and the app appearance.",
    monitoring = "Monitoring",
    monitoringDescription = "Choose which content Halalify protects locally.",
    images = "Images",
    imagesDescription = "Protect detections in still images.",
    video = "Video",
    videoDescription = "Protect detections in changing frames.",
    musicIsolation = "Music isolation",
    musicIsolationDescription = "Mute detected music during protected playback.",
    comingSoon = "Coming soon",
    websiteProtection = "Website protection",
    websiteProtectionDescription = "Optional website filtering. Turn it on when you need it.",
    active = "Active",
    automatic = "Automatic",
    appearance = "Appearance",
    appearanceDescription = "Change the app theme. Blur controls stay on the home screen for quick access.",
    colorMode = "Color mode",
    edit = "Edit",
    done = "Done",
    language = "Language",
    languageDescription = "Choose the language used throughout Halalify.",
    back = "Back",
    openSettings = "Open settings",
    on = "ON",
    off = "OFF",
    musicCardTitle = "Remove music from a file",
    musicCardDescription = "Choose a media file or direct MP4/M4A link to create a speech-focused copy.",
    ready = "Ready",
    mediaUrlPlaceholder = "https://example.com/video.mp4 or audio.m4a",
    chooseFile = "Choose file",
    noFile = "No file selected",
    createSpeechCopy = "Create speech-only copy",
    addSource = "Add source to start",
    blurStrength = "Blur strength",
    denseProtection = "Dense protection",
    lightProtection = "Light protection",
    balancedProtection = "Balanced protection",
    levelLabel = { level -> "Level $level/5" },
    levelOne = "Level 1",
    levelFive = "Level 5",
    protectedScreen = "Protected screen",
    protectedScreenDescription = "A private preview of the same protection drawn over the shared screen.",
    privateTitle = "Your screen stays private",
    privateDescription = "Detection runs on your device. Preview frames are never saved or uploaded.",
)

private val ArabicUi = UiStrings(
    quickControls = "تحكم سريع",
    protectionProfile = "ملف الحماية",
    quickControlsDescription = "اختر الفئة التي تريد حجبها واضبط تأثير الحجب.",
    settings = "الإعدادات",
    settingsTitle = "كل الخيارات في مكان واحد",
    settingsDescription = "تحكم بالمراقبة وعزل الصوت ومظهر التطبيق.",
    monitoring = "المراقبة",
    monitoringDescription = "اختر المحتوى الذي يحميه Halalify على جهازك.",
    images = "الصور",
    imagesDescription = "حماية العناصر المكتشفة في الصور الثابتة.",
    video = "الفيديو",
    videoDescription = "حماية العناصر المكتشفة في الإطارات المتحركة.",
    musicIsolation = "عزل الموسيقى",
    musicIsolationDescription = "كتم الموسيقى المكتشفة أثناء التشغيل المحمي.",
    comingSoon = "سيتوفر قريبًا",
    websiteProtection = "حماية المواقع",
    websiteProtectionDescription = "حماية اختيارية للمواقع. فعّلها عند الحاجة.",
    active = "نشطة",
    automatic = "تلقائية",
    appearance = "المظهر",
    appearanceDescription = "غيّر مظهر التطبيق. تبقى خيارات البلور في الصفحة الرئيسية للوصول السريع.",
    colorMode = "نمط الألوان",
    edit = "تعديل",
    done = "تم",
    language = "اللغة",
    languageDescription = "اختر اللغة المستخدمة في Halalify.",
    back = "رجوع",
    openSettings = "فتح الإعدادات",
    on = "تشغيل",
    off = "إيقاف",
    musicCardTitle = "إزالة الموسيقى من ملف",
    musicCardDescription = "اختر ملفًا أو رابط MP4/M4A مباشرًا لإنشاء نسخة تركز على الكلام.",
    ready = "جاهز",
    mediaUrlPlaceholder = "رابط مباشر مثل video.mp4 أو audio.m4a",
    chooseFile = "اختيار ملف",
    noFile = "لم يتم اختيار ملف",
    createSpeechCopy = "إنشاء نسخة بدون موسيقى",
    addSource = "أضف مصدرًا للبدء",
    blurStrength = "قوة البلور",
    denseProtection = "حماية قوية",
    lightProtection = "حماية خفيفة",
    balancedProtection = "حماية متوازنة",
    levelLabel = { level -> "المستوى $level/5" },
    levelOne = "المستوى 1",
    levelFive = "المستوى 5",
    protectedScreen = "الشاشة المحمية",
    protectedScreenDescription = "معاينة خاصة لنفس الحماية المطبقة على الشاشة المشتركة.",
    privateTitle = "شاشتك تبقى خاصة",
    privateDescription = "تعمل المعالجة على جهازك، ولا يتم حفظ إطارات المعاينة أو رفعها.",
)

private fun uiStrings(language: AppLanguage): UiStrings =
    if (language == AppLanguage.ARABIC) ArabicUi else EnglishUi

private fun BlurTarget.localizedTitle(language: AppLanguage): String =
    if (language == AppLanguage.ARABIC) {
        if (this == BlurTarget.FEMALE) "أنثى" else "ذكر"
    } else {
        title
    }

private fun BlurTarget.localizedDescription(language: AppLanguage): String =
    if (language == AppLanguage.ARABIC) {
        if (this == BlurTarget.FEMALE) {
            "حجب العناصر المصنفة كأنثى."
        } else {
            "حجب العناصر المصنفة كذكر."
        }
    } else {
        description
    }

private fun BlurStyle.localizedTitle(language: AppLanguage): String =
    if (language == AppLanguage.ARABIC) {
        when (this) {
            BlurStyle.SOFT_BLUR -> "بلور ناعم"
            BlurStyle.PIXELATED -> "مربعات"
            BlurStyle.SOLID -> "تغطية كاملة"
        }
    } else {
        title
    }

private fun BlurStyle.localizedDescription(language: AppLanguage): String =
    if (language == AppLanguage.ARABIC) {
        when (this) {
            BlurStyle.SOFT_BLUR -> "بلور ناعم منخفض التفاصيل يمزج المنطقة المحمية."
            BlurStyle.PIXELATED -> "مربعات حجب صغيرة تركز على العنصر المكتشف."
            BlurStyle.SOLID -> "تغطية سوداء كاملة لأقصى قدر من الخصوصية."
        }
    } else {
        description
    }

private fun AppThemeMode.localizedTitle(language: AppLanguage): String =
    if (language == AppLanguage.ARABIC) {
        when (this) {
            AppThemeMode.NORMAL -> "تلقائي"
            AppThemeMode.DARK -> "داكن"
            AppThemeMode.LIGHT -> "فاتح"
        }
    } else {
        title
    }

private fun AppThemeMode.localizedDescription(language: AppLanguage): String =
    if (language == AppLanguage.ARABIC) {
        when (this) {
            AppThemeMode.NORMAL -> "اتباع مظهر الهاتف الفاتح أو الداكن."
            AppThemeMode.DARK -> "استخدام المظهر الأخضر الداكن دائمًا."
            AppThemeMode.LIGHT -> "استخدام المظهر الأخضر الفاتح دائمًا."
        }
    } else {
        description
    }
@Composable
internal fun HalalifyApp(
    initialSettings: BlurSettings,
    captureState: CaptureUiState,
    onSave: (BlurSettings) -> Unit,
    onStartCapture: (BlurSettings) -> Unit,
    onStopCapture: () -> Unit,
    onStartIsolation: (BlurSettings) -> Unit = {},
    onWebsiteProtectionChange: (Boolean, BlurSettings) -> Unit = { _, _ -> },
    websiteFilterEnabled: Boolean = initialSettings.blockAdultSites,
) {
    var settings by remember(initialSettings) {
        mutableStateOf(
            initialSettings.copy(
                isolateMusic = initialSettings.isolateMusic && MUSIC_ISOLATION_AVAILABLE,
            ),
        )
    }
    var showingSettings by remember { mutableStateOf(false) }
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

    LaunchedEffect(settings, websiteFilterEnabled, captureState.isCapturing) {
        val selectedWebsiteProtection = if (captureState.isCapturing) {
            websiteFilterEnabled
        } else {
            settings.blockAdultSites
        }
        onSave(settings.copy(blockAdultSites = selectedWebsiteProtection))
    }

    val useDarkTheme = when (settings.themeMode) {
        AppThemeMode.NORMAL -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    MaterialTheme(
        colorScheme = if (useDarkTheme) HalalifyDarkColorScheme else HalalifyLightColorScheme,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (settings.language == AppLanguage.ARABIC) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            },
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground),
                containerColor = AppBackground,
                topBar = {
                    AppHeader(
                        showingSettings = showingSettings,
                        language = settings.language,
                        onOpenSettings = { showingSettings = true },
                        onNavigateBack = { showingSettings = false },
                    )
                },
            ) { innerPadding ->
                if (showingSettings) {
                    SettingsScreen(
                        contentPadding = innerPadding,
                        settings = settings,
                        captureState = captureState,
                        websiteFilterEnabled = websiteFilterEnabled,
                        language = settings.language,
                        onSettingsChange = { settings = it },
                        onWebsiteProtectionChange = onWebsiteProtectionChange,
                        onOpenFilePicker = {
                            filePickerLauncher.launch(arrayOf("audio/*", "video/*"))
                        },
                        onStartIsolation = { onStartIsolation(settings) },
                    )
                } else {
                    HomeScreen(
                        contentPadding = innerPadding,
                        settings = settings,
                        captureState = captureState,
                        language = settings.language,
                        onSettingsChange = { settings = it },
                        onStartCapture = { onStartCapture(settings) },
                        onStopCapture = onStopCapture,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    contentPadding: PaddingValues,
    settings: BlurSettings,
    captureState: CaptureUiState,
    language: AppLanguage,
    onSettingsChange: (BlurSettings) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    val ui = uiStrings(language)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            CaptureStatusCard(
                captureState = captureState,
                language = language,
                onToggleProtection = {
                    if (captureState.isCapturing) onStopCapture() else onStartCapture()
                },
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeading(
                    eyebrow = ui.quickControls,
                    title = ui.protectionProfile,
                    description = ui.quickControlsDescription,
                )
                TargetSelector(
                    selected = settings.target,
                    language = language,
                    enabled = !captureState.isCapturing,
                    onSelect = { target -> onSettingsChange(settings.copy(target = target)) },
                )
                BlurStyleSelector(
                    selected = settings.style,
                    enabled = true,
                    language = language,
                    onSelect = { style -> onSettingsChange(settings.copy(style = style)) },
                )
                BlurIntensitySelector(
                    intensity = settings.intensity,
                    enabled = true,
                    language = language,
                    onIntensityChange = { intensity ->
                        onSettingsChange(settings.copy(intensity = intensity))
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    contentPadding: PaddingValues,
    settings: BlurSettings,
    captureState: CaptureUiState,
    websiteFilterEnabled: Boolean,
    language: AppLanguage,
    onSettingsChange: (BlurSettings) -> Unit,
    onWebsiteProtectionChange: (Boolean, BlurSettings) -> Unit,
    onOpenFilePicker: () -> Unit,
    onStartIsolation: () -> Unit,
) {
    var showThemeEditor by remember { mutableStateOf(false) }
    var showLanguageEditor by remember { mutableStateOf(false) }
    val ui = uiStrings(language)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SectionHeading(
                eyebrow = ui.settings,
                title = ui.settingsTitle,
                description = ui.settingsDescription,
            )
        }

        item {
            SettingsSectionTitle(
                icon = "◉",
                title = ui.monitoring,
                description = ui.monitoringDescription,
            )
        }

        item {
            PreferenceCard(enabled = true) {
                PreferenceToggle(
                    shortLabel = "▧",
                    title = ui.images,
                    description = ui.imagesDescription,
                    checked = settings.blurImages,
                    enabled = !captureState.isCapturing,
                    onCheckedChange = { enabled ->
                        onSettingsChange(settings.copy(blurImages = enabled))
                    },
                )
                HorizontalDivider(color = Outline)
                PreferenceToggle(
                    shortLabel = "▶",
                    title = ui.video,
                    description = ui.videoDescription,
                    checked = settings.blurVideos,
                    enabled = !captureState.isCapturing,
                    onCheckedChange = { enabled ->
                        onSettingsChange(settings.copy(blurVideos = enabled))
                    },
                )
                HorizontalDivider(color = Outline)
                PreferenceToggle(
                    shortLabel = "♫",
                    title = ui.musicIsolation,
                    description = ui.musicIsolationDescription,
                    checked = MUSIC_ISOLATION_AVAILABLE && settings.isolateMusic,
                    enabled = MUSIC_ISOLATION_AVAILABLE && !captureState.isCapturing,
                    trailingLabel = if (MUSIC_ISOLATION_AVAILABLE) null else ui.comingSoon,
                    onCheckedChange = { enabled ->
                        if (MUSIC_ISOLATION_AVAILABLE) {
                            onSettingsChange(settings.copy(isolateMusic = enabled))
                        }
                    },
                )
            }
        }

        if (!MUSIC_ISOLATION_AVAILABLE || settings.isolateMusic) {
            item {
                MusicIsolationSourceCard(
                    settings = settings,
                    language = language,
                    enabled = MUSIC_ISOLATION_AVAILABLE && !captureState.isCapturing,
                    comingSoon = !MUSIC_ISOLATION_AVAILABLE,
                    isolationStatus = captureState.audioStatus,
                    onUrlChange = { url -> onSettingsChange(settings.copy(musicSourceUrl = url)) },
                    onOpenFilePicker = onOpenFilePicker,
                    onStartIsolation = onStartIsolation,
                )
            }
        }

        item {
            val websiteProtectionChecked = if (captureState.isCapturing) {
                websiteFilterEnabled
            } else {
                settings.blockAdultSites
            }
            PreferenceCard(enabled = true) {
                PreferenceToggle(
                    shortLabel = "WEB",
                    title = ui.websiteProtection,
                    description = ui.websiteProtectionDescription,
                    checked = websiteProtectionChecked,
                    enabled = true,
                    trailingLabel = if (websiteProtectionChecked) ui.active else ui.off,
                    onCheckedChange = { enabled ->
                        val updatedSettings = settings.copy(blockAdultSites = enabled)
                        onSettingsChange(updatedSettings)
                        onWebsiteProtectionChange(enabled, updatedSettings)
                    },
                )
            }
        }

        item {
            SettingsSectionTitle(
                icon = "☼",
                title = ui.appearance,
                description = ui.appearanceDescription,
            )
        }

        item {
            PreferenceCard(enabled = true) {
                SettingActionRow(
                    icon = "☼",
                    title = ui.colorMode,
                    description = settings.themeMode.localizedTitle(language),
                    actionLabel = if (showThemeEditor) ui.done else ui.edit,
                    onAction = { showThemeEditor = !showThemeEditor },
                )
            }
        }

        if (showThemeEditor) {
            item {
                ThemeModeSelector(
                    selected = settings.themeMode,
                    language = language,
                    onSelect = { mode -> onSettingsChange(settings.copy(themeMode = mode)) },
                )
            }
        }

        item {
            PreferenceCard(enabled = true) {
                SettingActionRow(
                    icon = "文A",
                    title = ui.language,
                    description = language.title,
                    actionLabel = if (showLanguageEditor) ui.done else ui.edit,
                    onAction = { showLanguageEditor = !showLanguageEditor },
                )
            }
        }

        if (showLanguageEditor) {
            item {
                LanguageSelector(
                    selected = language,
                    onSelect = { selected ->
                        showLanguageEditor = false
                        onSettingsChange(settings.copy(language = selected))
                    },
                )
            }
        }

        item { PrivacyNote(language = language) }
    }
}

@Composable
private fun AppHeader(
    showingSettings: Boolean,
    language: AppLanguage,
    onOpenSettings: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val ui = uiStrings(language)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppBackground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showingSettings) {
            HeaderIconButton(
                icon = "‹",
                description = ui.back,
                onClick = onNavigateBack,
            )
            Spacer(Modifier.width(10.dp))
        }
        Image(
            painter = painterResource(id = R.drawable.icon),
            contentDescription = "Halalify",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Halalify",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        if (!showingSettings) {
            HeaderIconButton(
                icon = "⚙",
                description = ui.openSettings,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: String,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon,
            color = TextPrimary,
            fontSize = if (icon == "‹") 34.sp else 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CaptureStatusCard(
    captureState: CaptureUiState,
    language: AppLanguage,
    onToggleProtection: () -> Unit,
) {
    val ui = uiStrings(language)
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
                    text = if (captureState.isCapturing) ui.on else ui.off,
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
private fun SettingsSectionTitle(
    icon: String,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SettingIcon(icon: String) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppSurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon,
            color = Accent,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SettingInfoRow(
    icon: String,
    title: String,
    description: String,
    status: String,
) {
    PreferenceCard(enabled = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIcon(icon)
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
            Text(
                text = status,
                color = Accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SettingActionRow(
    icon: String,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon)
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
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentSoft,
                contentColor = Accent,
            ),
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TargetSelector(
    selected: BlurTarget,
    enabled: Boolean,
    language: AppLanguage,
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
                title = option.localizedTitle(language),
                description = option.localizedDescription(language),
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
    trailingLabel: String? = null,
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
        trailingLabel?.let { label ->
            Text(
                text = label,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
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
    language: AppLanguage,
    enabled: Boolean,
    comingSoon: Boolean = false,
    isolationStatus: String?,
    onUrlChange: (String) -> Unit,
    onOpenFilePicker: () -> Unit,
    onStartIsolation: () -> Unit,
) {
    val ui = uiStrings(language)
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
                    text = ui.musicCardTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = ui.musicCardDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (comingSoon) {
                Text(
                    text = ui.comingSoon,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            } else if (settings.hasMusicIsolationSource) {
                Text(
                    text = ui.ready,
                    color = Accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        OutlinedTextField(
            value = settings.musicSourceUrl,
            onValueChange = onUrlChange,
            enabled = enabled && settings.isolateMusic && !comingSoon,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(ui.mediaUrlPlaceholder) },
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
                enabled = enabled && settings.isolateMusic && !comingSoon,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppSurfaceHigh,
                    contentColor = TextPrimary,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(ui.chooseFile)
            }
            Text(
                text = settings.musicSourceFileName.ifBlank { ui.noFile },
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Button(
            onClick = onStartIsolation,
            enabled = enabled && settings.isolateMusic && !comingSoon && settings.hasMusicIsolationSource,
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
                text = if (comingSoon) {
                    ui.comingSoon
                } else if (settings.isolateMusic && settings.hasMusicIsolationSource) {
                    ui.createSpeechCopy
                } else {
                    ui.addSource
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
private fun LanguageSelector(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppSurface)
            .border(1.dp, Outline, RoundedCornerShape(22.dp))
            .padding(14.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppLanguage.entries.forEach { language ->
            val isSelected = language == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                        onClick = { onSelect(language) },
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) Accent else TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                SelectionIndicator(selected = isSelected)
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selected: AppThemeMode,
    language: AppLanguage,
    onSelect: (AppThemeMode) -> Unit,
) {
    val ui = uiStrings(language)
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
            text = ui.colorMode,
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
                        text = mode.localizedTitle(language),
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
            text = selected.localizedDescription(language),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}
@Composable
private fun BlurStyleSelector(
    selected: BlurStyle,
    enabled: Boolean,
    language: AppLanguage,
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
                    language = language,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(option) },
                )
            }
        }
        Text(
            text = selected.localizedDescription(language),
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
    language: AppLanguage,
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
            text = style.localizedTitle(language),
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
    language: AppLanguage,
    onIntensityChange: (Float) -> Unit,
) {
    val ui = uiStrings(language)
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
                    text = ui.blurStrength,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                text = when {
                    level >= 4 -> ui.denseProtection
                    level <= 2 -> ui.lightProtection
                    else -> ui.balancedProtection
                },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = ui.levelLabel(level),
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
                text = ui.levelOne,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = ui.levelFive,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun ScreenPreview(jpeg: ByteArray, language: AppLanguage) {
    val ui = uiStrings(language)
    Column {
        SectionHeading(
            eyebrow = "LIVE PREVIEW",
            title = ui.protectedScreen,
            description = ui.protectedScreenDescription,
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
private fun PrivacyNote(language: AppLanguage) {
    val ui = uiStrings(language)
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
                text = ui.privateTitle,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ui.privateDescription,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
