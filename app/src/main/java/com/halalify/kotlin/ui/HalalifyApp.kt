package com.halalify.kotlin.ui

import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.halalify.kotlin.appselection.AppTarget
import com.halalify.kotlin.appselection.loadLaunchableApps
import com.halalify.kotlin.capture.CaptureSessionStore
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurTarget

private val Background = Color(0xFF071D22)
private val SurfaceDark = Color(0xFF10313A)
private val Accent = Color(0xFF79E0B0)
private val TextPrimary = Color(0xFFF2F7F5)
private val TextMuted = Color(0xFFB8C8C5)

@Composable
internal fun HalalifyApp(
    initialSettings: BlurSettings,
    onSave: (BlurSettings) -> Unit,
    onStartCapture: (AppTarget) -> Unit,
    onStopCapture: () -> Unit,
) {
    var target by remember { mutableStateOf(initialSettings.target) }
    var blurImages by remember { mutableStateOf(initialSettings.blurImages) }
    var blurVideos by remember { mutableStateOf(initialSettings.blurVideos) }
    val captureState by CaptureSessionStore.state.collectAsState()
    val context = LocalContext.current
    val apps = remember { loadLaunchableApps(context.applicationContext) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    Text("Halalify", color = TextPrimary, style = MaterialTheme.typography.headlineLarge)
                    Text("Capture supported app audio and screen frames locally.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    SettingsCard("Capture session") {
                        Text(if (captureState.isCapturing) "● CAPTURE ACTIVE" else "● READY", color = if (captureState.isCapturing) Accent else TextMuted)
                        Text(captureState.targetLabel ?: captureState.message, color = TextPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        if (captureState.targetLabel != null) Text(captureState.message, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        if (captureState.isCapturing) Button(
                            onClick = onStopCapture,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB7414D)),
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text("Stop capture") }
                    }
                }
                captureState.previewJpeg?.let { jpeg ->
                    item { ScreenPreview(jpeg) }
                }
                item {
                    Text("Choose an app", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("Android may deny playback audio from apps that opt out. Screen preview is saved only in memory.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                items(apps, key = { it.packageName }) { app ->
                    AppRow(app, !captureState.isCapturing) { onStartCapture(app) }
                }
                item { SettingsCard("Blur target") {
                    BlurTarget.entries.forEach { option ->
                        FilterChip(
                            selected = target == option,
                            onClick = { target = option },
                            label = { Text(option.title) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Background, labelColor = TextPrimary),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        )
                    }
                    Text("These settings are stored now and will be used when a local detection-and-blur engine is connected.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                } }
                item { SettingsCard("Content type") {
                    ToggleRow("Blur images", "Apply to detected still images.", blurImages) { blurImages = it }
                    ToggleRow("Blur video", "Apply to future captured video frames.", blurVideos) { blurVideos = it }
                    Button(
                        onClick = { onSave(BlurSettings(target, blurImages, blurVideos)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Save blur settings") }
                } }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

@Composable private fun ScreenPreview(jpeg: ByteArray) = SettingsCard("Live screen preview") {
    AndroidView(
        factory = { ImageView(it).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { view -> view.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) },
        modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(10.dp)),
    )
    Text("One low-resolution frame per second; not persisted or uploaded.", color = TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 7.dp))
}

@Composable private fun AppRow(app: AppTarget, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceDark.copy(alpha = if (enabled) 1f else .55f))
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(factory = { ImageView(it).apply { setImageDrawable(app.icon) } }, modifier = Modifier.size(42.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(app.packageName, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Text("UID: ${app.uid}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
        Text("Capture", color = if (enabled) Accent else TextMuted, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceDark).padding(18.dp)) {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp)); content()
    }
}

@Composable private fun ToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = TextPrimary); Text(description, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
