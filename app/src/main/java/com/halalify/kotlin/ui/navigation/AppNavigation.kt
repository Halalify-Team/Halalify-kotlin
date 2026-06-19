package com.halalify.kotlin.ui.navigation

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
    val libraryItems by viewModel.libraryItems.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()

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
            )
            AppScreen.PROCESSING -> ProcessingScreen(
                state = processingState,
                onWatchNow = { viewModel.navigateToResult() },
                onRetry = { viewModel.resetToInput() },
            )
            AppScreen.RESULT -> ResultScreen(
                state = processingState,
                exportStatus = exportStatus,
                onSaveToGallery = {
                    if (processingState.playablePaths.isNotEmpty()) {
                        viewModel.exportToGallery(activity, processingState.playablePaths.first(), processingState.videoTitle)
                    }
                },
                onClearExportStatus = { viewModel.clearExportStatus() },
                onHalalifyAnother = { viewModel.resetToInput() },
            )
            AppScreen.LIBRARY -> LibraryScreen(
                libraryItems = libraryItems,
                exportStatus = exportStatus,
                onBack = { viewModel.resetToInput() },
                onPlayItem = { viewModel.playLibraryItem(it) },
                onDeleteItem = { viewModel.deleteFromLibrary(it) },
                onSaveToGallery = { item ->
                    viewModel.exportToGallery(activity, item.filePath, item.title)
                },
                onClearExportStatus = { viewModel.clearExportStatus() },
            )
        }
    }
}
