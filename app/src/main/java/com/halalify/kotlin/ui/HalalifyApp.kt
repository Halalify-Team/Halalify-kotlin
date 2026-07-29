package com.halalify.kotlin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurTarget

private val Background = Color(0xFF071D22)
private val SurfaceDark = Color(0xFF10313A)
private val Accent = Color(0xFF79E0B0)
private val TextPrimary = Color(0xFFF2F7F5)
private val TextMuted = Color(0xFFB8C8C5)

@Composable
internal fun HalalifyApp(initialSettings: BlurSettings, onSave: (BlurSettings) -> Unit) {
    var target by remember { mutableStateOf(initialSettings.target) }
    var blurImages by remember { mutableStateOf(initialSettings.blurImages) }
    var blurVideos by remember { mutableStateOf(initialSettings.blurVideos) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    Text("Halalify", color = TextPrimary, style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Choose which detected people should be blurred.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                item {
                    SettingsCard(title = "Blur target") {
                        BlurTarget.entries.forEach { option ->
                            FilterChip(
                                selected = target == option,
                                onClick = { target = option },
                                label = { Text(option.title) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent,
                                    selectedLabelColor = Background,
                                    labelColor = TextPrimary,
                                ),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            )
                        }
                        Text(
                            target.description,
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item {
                    SettingsCard(title = "Content type") {
                        ToggleRow(
                            title = "Blur images",
                            description = "Apply blur to still images.",
                            checked = blurImages,
                            onCheckedChange = { blurImages = it },
                        )
                        ToggleRow(
                            title = "Blur video",
                            description = "Apply blur to detected regions in video frames.",
                            checked = blurVideos,
                            onCheckedChange = { blurVideos = it },
                        )
                    }
                }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(SurfaceDark)
                            .padding(18.dp),
                    ) {
                        Text("Coverage", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Blur will apply after the analysis engine is connected to a built-in browser or other supported surfaces. " +
                                "Android cannot inspect every app automatically without dedicated, permitted integration.",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            onSave(BlurSettings(target, blurImages, blurVideos))
                            savedMessage = "Settings saved on this device."
                        },
                        enabled = blurImages || blurVideos,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save blur settings") }
                }
                savedMessage?.let { message ->
                    item {
                        Text(
                            message,
                            color = Accent,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item { Spacer(Modifier.height(14.dp)) }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceDark)
            .padding(18.dp),
    ) {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
