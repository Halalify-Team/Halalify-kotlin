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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.halalify.kotlin.BuildConfig
import com.halalify.kotlin.model.AppScreen
import com.halalify.kotlin.ui.screens.InputScreen
import com.halalify.kotlin.ui.screens.DownloadScreen
import com.halalify.kotlin.ui.screens.ProfileScreen
import com.halalify.kotlin.ui.screens.ProcessingScreen
import com.halalify.kotlin.ui.screens.ResultScreen
import com.halalify.kotlin.ui.screens.LibraryScreen
import com.halalify.kotlin.viewmodel.HalalifyViewModel
import kotlinx.coroutines.launch

@Composable
internal fun AppNavigation(
    activity: ComponentActivity,
    viewModel: HalalifyViewModel,
) {
    val currentScreen by viewModel.screen.collectAsState()
    val processingState by viewModel.processing.collectAsState()
    val formatDiscovery by viewModel.formatDiscovery.collectAsState()
    val sharedYoutubeUrl by viewModel.sharedYoutubeUrl.collectAsState()
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
    val coroutineScope = rememberCoroutineScope()

    fun launchGoogleSignIn() {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        if (webClientId.isBlank()) {
            Toast.makeText(
                activity,
                "Google sign-in needs GOOGLE_WEB_CLIENT_ID in the Android build.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        viewModel.beginGoogleSignIn()
        coroutineScope.launch {
            try {
                val credentialManager = CredentialManager.create(activity)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(webClientId)
                    .setFilterByAuthorizedAccounts(false) // show all accounts, not just previously used ones
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val result = credentialManager.getCredential(
                    request = request,
                    context = activity,
                )
                val credential = result.credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    error("Google returned an unsupported credential type.")
                }
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                if (idToken.isBlank()) {
                    Toast.makeText(activity, "Google sign-in returned an empty token.", Toast.LENGTH_LONG).show()
                } else {
                    viewModel.googleLogin(idToken)
                }
            } catch (e: GetCredentialCancellationException) {
                viewModel.cancelGoogleSignIn()
            } catch (e: NoCredentialException) {
                viewModel.reportGoogleSignInFailure(
                    "No Google account is available. Add an account to this device and try again.",
                )
            } catch (e: Exception) {
                val details = e.message
                    ?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName
                viewModel.reportGoogleSignInFailure(details)
                Toast.makeText(
                    activity,
                    "Google sign-in failed: $details",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

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
                sharedYoutubeUrl = sharedYoutubeUrl,
                backendUrl = backendUrl,
                devEmail = devEmail,
                sessionToken = sessionToken,
                loginStatus = loginStatus,
                isLoggingIn = isLoggingIn,
                formatDiscovery = formatDiscovery,
                showDeveloperControls = BuildConfig.DEBUG,
                onBackendUrlChange = viewModel::updateBackendUrl,
                onDevEmailChange = viewModel::updateDevEmail,
                onSessionTokenChange = viewModel::updateSessionToken,
                onDevLogin = viewModel::devLogin,
                onGoogleLogin = ::launchGoogleSignIn,
                onSharedYoutubeUrlConsumed = viewModel::consumeSharedYoutubeUrl,
                onDiscoverFormats = { url ->
                    viewModel.discoverFormats(activity, url)
                },
                onStartProcessing = { url, removeMusic, blurWomen, quality, blurStrictness ->
                    viewModel.startProcessing(activity, url, removeMusic, blurWomen, quality, blurStrictness)
                },
                onNavigateToLibrary = { viewModel.navigateToLibrary() },
                onNavigateToProfile = { viewModel.navigateToProfile() },
            )
            AppScreen.PROCESSING -> ProcessingScreen(
                state = processingState,
                isExporting = isExporting,
                onWatchNow = { viewModel.navigateToResult() },
                onSaveToGallery = {
                    processingState.playablePaths.firstOrNull()?.let { path ->
                        viewModel.exportToGallery(
                            activity,
                            path,
                            processingState.videoTitle,
                        )
                    }
                },
                onRetry = { viewModel.resetToInput() },
            )
            AppScreen.DOWNLOAD -> DownloadScreen(
                state = processingState,
                isExporting = isExporting,
                onWatch = { viewModel.navigateToResult() },
                onSaveToGallery = {
                    processingState.playablePaths.firstOrNull()?.let { path ->
                        viewModel.exportToGallery(
                            activity,
                            path,
                            processingState.videoTitle,
                        )
                    }
                },
                onBack = { viewModel.resetToInput() },
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
                showDeveloperControls = BuildConfig.DEBUG,
                onBackendUrlChange = viewModel::updateBackendUrl,
                onDevEmailChange = viewModel::updateDevEmail,
                onDevLogin = viewModel::devLogin,
                onGoogleLogin = ::launchGoogleSignIn,
                onRefreshQuota = viewModel::refreshQuota,
                onOpenSubscription = ::openExternalUrl,
                onLogout = viewModel::logout,
                onBack = { viewModel.resetToInput(discardTemporaryResult = false) },
            )
        }
    }
}
