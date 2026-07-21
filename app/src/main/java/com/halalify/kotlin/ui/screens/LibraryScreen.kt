package com.halalify.kotlin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halalify.kotlin.model.LibraryItem
import com.halalify.kotlin.ui.components.HalalifyLogo
import com.halalify.kotlin.ui.components.HalalifyTopBar
import com.halalify.kotlin.ui.theme.HalalifyAccent
import com.halalify.kotlin.ui.theme.HalalifyDarkCard
import com.halalify.kotlin.ui.theme.HalalifyGradientEnd
import com.halalify.kotlin.ui.theme.HalalifyGradientStart
import com.halalify.kotlin.ui.theme.HalalifyTextOnAccent
import com.halalify.kotlin.ui.theme.HalalifyTextPrimary
import com.halalify.kotlin.ui.theme.HalalifyTextSecondary
import com.halalify.kotlin.ui.theme.HalalifyTextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LibraryScreen(
    libraryItems: List<LibraryItem>,
    exportStatus: String?,
    libraryStatus: String?,
    onBack: () -> Unit,
    onPlayItem: (LibraryItem) -> Unit,
    onDeleteItem: (String) -> Unit,
    onSaveToGallery: (LibraryItem) -> Unit,
    onClearExportStatus: () -> Unit,
    onClearLibraryStatus: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(exportStatus) {
        if (exportStatus != null) {
            android.widget.Toast.makeText(context, exportStatus, android.widget.Toast.LENGTH_LONG).show()
            onClearExportStatus()
        }
    }

    LaunchedEffect(libraryStatus) {
        if (libraryStatus != null) {
            android.widget.Toast.makeText(context, libraryStatus, android.widget.Toast.LENGTH_LONG).show()
            onClearLibraryStatus()
        }
    }

    Scaffold(
        topBar = {
            HalalifyTopBar(
                title = "Library",
                subtitle = if (libraryItems.isNotEmpty()) {
                    "${libraryItems.size} saved video${if (libraryItems.size == 1) "" else "s"}"
                } else {
                    null
                },
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        if (libraryItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    HalalifyLogo(size = 88.dp, animated = false)
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Your Library is Empty",
                        style = MaterialTheme.typography.titleLarge,
                        color = HalalifyTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Saved halalified videos will appear here. Unsaved results are deleted when you leave them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HalalifyTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(libraryItems, key = { it.id }) { item ->
                    LibraryCard(
                        item = item,
                        onPlay = { onPlayItem(item) },
                        onDelete = { onDeleteItem(item.id) },
                        onSave = { onSaveToGallery(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(
    item: LibraryItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var swipeDismissed by remember { mutableStateOf(false) }
    val dateString = remember(item.timestamp) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(item.timestamp))
    }

    val durationText = remember(item.durationSeconds) {
        val minutes = item.durationSeconds / 60
        val seconds = item.durationSeconds % 60
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
    val sizeText = remember(item.fileSizeBytes) {
        item.fileSizeBytes.toHumanFileSize()
    }
    val monogram = remember(item.title) {
        item.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    }
    val monogramBrush = remember(item.id) {
        Brush.linearGradient(colors = listOf(HalalifyGradientStart, HalalifyGradientEnd))
    }

    fun triggerDelete() {
        showDeleteDialog = true
    }

    if (swipeDismissed) {
        triggerDelete()
        swipeDismissed = false
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                swipeDismissed = true
                false
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = HalalifyDarkCard
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlay() },
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(monogramBrush),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = monogram,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = HalalifyTextOnAccent,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = HalalifyTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = durationText,
                                style = MaterialTheme.typography.bodySmall,
                                color = HalalifyAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "$sizeText · $dateString",
                                style = MaterialTheme.typography.bodySmall,
                                color = HalalifyTextTertiary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = HalalifyTextTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Tap card to play",
                            style = MaterialTheme.typography.labelSmall,
                            color = HalalifyTextTertiary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onSave) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save to Gallery",
                                tint = HalalifyTextSecondary
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "Delete from Library?") },
            text = {
                Text(
                    text = "This removes the saved copy from the app. Gallery exports are not affected.",
                    color = HalalifyTextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text(text = "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = "Cancel")
                }
            },
            containerColor = HalalifyDarkCard,
            titleContentColor = HalalifyTextPrimary,
            textContentColor = HalalifyTextSecondary,
        )
    }
}

private fun Long.toHumanFileSize(): String {
    if (this <= 0L) return "Unknown size"
    val mb = this / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        val kb = (this / 1024.0).coerceAtLeast(1.0)
        String.format(Locale.US, "%.0f KB", kb)
    }
}
