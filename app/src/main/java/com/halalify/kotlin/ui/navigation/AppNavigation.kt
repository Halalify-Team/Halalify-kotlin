package com.halalify.kotlin.ui.navigation

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.halalify.kotlin.model.AppScreen
import com.halalify.kotlin.ui.screens.InputScreen
import com.halalify.kotlin.ui.screens.ProfileScreen
import com.halalify.kotlin.ui.screens.ProcessingScreen
import com.halalify.kotlin.ui.screens.ResultScreen
import com.halalify.kotlin.ui.screens.LibraryScreen
import com.halalify.kotlin.viewmodel.HalalifyViewModel

@Composable
internal fun AppNavigation(
    activity: ComponentActivity,
    viewModel: HalalifyViewModel,
) {
    val currentScreen by viewModel.screen.collectAsState()
    val processingState by viewModel.processing.collectAsState()
    val backendUrl by viewModel.backendUrl.collectAsState()
    val devEmail by viewModel.devEmail.collectAsState()
    val sessionToken by viewModel.sessionToken.collectAsState()
    val loginStatus by viewModel.loginStatus.collectAsState()
    val isLoggingIn by viewModel.isLoggingIn.collectAsState()
    val quotaState by viewModel.quotaState.collectAsState()
    val libraryItems by viewModel.libraryItems.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val libraryStatus by viewModel.libraryStatus.collectAsState()

    fun openExternalUrl(url: String) {
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(activity, "Could not open billing page.", Toast.LENGTH_LONG).show()
        }
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            when {
                targetState.ordinal > initialState.ordinal -> {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it } + fadeOut())
                }
                else -> {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { it } + fadeOut())
                }
            }
        },
        label = "screenTransition",
    ) { screen ->
        when (screen) {
            AppScreen.INPUT -> InputScreen(
                backendUrl = backendUrl,
                devEmail = devEmail,
                sessionToken = sessionToken,
                loginStatus = loginStatus,
                isLoggingIn = isLoggingIn,
                onBackendUrlChange = viewModel::updateBackendUrl,
                onDevEmailChange = viewModel::updateDevEmail,
                onSessionTokenChange = viewModel::updateSessionToken,
                onDevLogin = viewModel::devLogin,
                onStartProcessing = { url ->
                    viewModel.startProcessing(activity, url)
                },
                onNavigateToLibrary = { viewModel.navigateToLibrary() },
                onNavigateToProfile = { viewModel.navigateToProfile() },
            )
            AppScreen.PROCESSING -> ProcessingScreen(
                state = processingState,
                onWatchNow = { viewModel.navigateToResult() },
                onRetry = { viewModel.resetToInput() },
            )
            AppScreen.RESULT -> ResultScreen(
                state = processingState,
                exportStatus = exportStatus,
                isExporting = isExporting,
                onSaveToGallery = {
                    if (processingState.playablePaths.isNotEmpty()) {
                        viewModel.exportToGallery(activity, processingState.playablePaths.first(), processingState.videoTitle)
                    }
                },
                onClearExportStatus = { viewModel.clearExportStatus() },
                onBack = { viewModel.navigateBackFromResult() },
                onHalalifyAnother = { viewModel.resetToInput() },
            )
            AppScreen.LIBRARY -> LibraryScreen(
                libraryItems = libraryItems,
                exportStatus = exportStatus,
                libraryStatus = libraryStatus,
                onBack = { viewModel.resetToInput() },
                onPlayItem = { viewModel.playLibraryItem(it) },
                onDeleteItem = { viewModel.deleteFromLibrary(it) },
                onSaveToGallery = { item ->
                    viewModel.exportToGallery(activity, item.filePath, item.title)
                },
                onClearExportStatus = { viewModel.clearExportStatus() },
                onClearLibraryStatus = { viewModel.clearLibraryStatus() },
            )
            AppScreen.PROFILE -> ProfileScreen(
                backendUrl = backendUrl,
                devEmail = devEmail,
                sessionToken = sessionToken,
                loginStatus = loginStatus,
                isLoggingIn = isLoggingIn,
                quotaState = quotaState,
                onBackendUrlChange = viewModel::updateBackendUrl,
                onDevEmailChange = viewModel::updateDevEmail,
                onDevLogin = viewModel::devLogin,
                onRefreshQuota = viewModel::refreshQuota,
                onOpenSubscription = ::openExternalUrl,
                onLogout = viewModel::logout,
                onBack = { viewModel.resetToInput(discardTemporaryResult = false) },
            )
        }
    }
}
