package com.halalify.kotlin.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.halalify.kotlin.model.AppScreen
import com.halalify.kotlin.ui.theme.HalalifyAccent
import com.halalify.kotlin.ui.theme.HalalifyTextSecondary
import com.halalify.kotlin.ui.theme.HalalifyTextTertiary

private data class BottomDestination(
    val screen: AppScreen,
    val label: String,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(AppScreen.INPUT, "Home", Icons.Default.Home),
    BottomDestination(AppScreen.LIBRARY, "Library", Icons.Default.VideoLibrary),
    BottomDestination(AppScreen.PROFILE, "Profile", Icons.Default.AccountCircle),
)

@Composable
internal fun HalalifyBottomBar(
    currentScreen: AppScreen,
    onNavigateToInput: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val callbacks = mapOf(
        AppScreen.INPUT to onNavigateToInput,
        AppScreen.LIBRARY to onNavigateToLibrary,
        AppScreen.PROFILE to onNavigateToProfile,
    )

    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        bottomDestinations.forEach { destination ->
            val selected = currentScreen == destination.screen
            NavigationBarItem(
                selected = selected,
                onClick = { callbacks[destination.screen]?.invoke() },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = HalalifyAccent,
                    selectedTextColor = HalalifyAccent,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    unselectedIconColor = HalalifyTextTertiary,
                    unselectedTextColor = HalalifyTextSecondary,
                ),
            )
        }
    }
}