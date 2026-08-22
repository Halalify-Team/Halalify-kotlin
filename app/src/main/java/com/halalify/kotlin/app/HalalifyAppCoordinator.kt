package com.halalify.kotlin.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.google.android.gms.tflite.java.TfLiteNative
import com.halalify.kotlin.capture.AudioIsolationService
import com.halalify.kotlin.capture.CaptureSessionStore
import com.halalify.kotlin.capture.CaptureUiState
import com.halalify.kotlin.capture.ProtectionCaptureService
import com.halalify.kotlin.media.TrustedOverlayHost
import com.halalify.kotlin.network.AdultSiteVpnService
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurSettingsRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * The application use-case coordinator.
 *
 * It owns the decisions needed to start and stop protection. Android-specific
 * permission dialogs remain behind [PermissionRequester], so the activity only
 * connects lifecycle callbacks to this class.
 */
internal class HalalifyAppCoordinator(
    private val context: Context,
    private val permissionRequester: PermissionRequester,
    private val settingsRepository: BlurSettingsRepository = BlurSettingsRepository(context),
) {
    val captureState: StateFlow<CaptureUiState> = CaptureSessionStore.state
    val initialSettings: BlurSettings = settingsRepository.load()

    // Website filtering is a runtime part of an active protection session.
    // The persisted flag can be left behind after a process/service stop, so
    // it must never be enough on its own to start a VPN.
    var websiteFilterEnabled by mutableStateOf(
        initialSettings.blockAdultSites && captureState.value.isCapturing,
    )
        private set

    private var pendingCaptureSettings: BlurSettings? = null
    private var pendingWebsiteFilterEnable = false
    private var pendingWebsiteProtectionSettings: BlurSettings? = null
    private var pendingTrustedOverlaySettings: BlurSettings? = null

    fun saveSettings(settings: BlurSettings) {
        settingsRepository.save(settings)
        if (captureState.value.isCapturing) {
            context.startService(
                Intent(context, ProtectionCaptureService::class.java)
                    .setAction(ProtectionCaptureService.ACTION_UPDATE_VISUAL_SETTINGS),
            )
        }
    }

    fun setWebsiteProtection(enabled: Boolean, settings: BlurSettings) {
        val updatedSettings = settings.copy(blockAdultSites = enabled)
        saveSettings(updatedSettings)
        if (captureState.value.isCapturing) {
            setWebsiteBlocking(enabled = enabled)
        } else if (!enabled) {
            setWebsiteBlocking(enabled = false)
        }
    }

    fun onStart() {
        CaptureSessionStore.setPreviewRequested(true)
        if (captureState.value.isCapturing && initialSettings.blockAdultSites && !pendingWebsiteFilterEnable) {
            setWebsiteBlocking(enabled = true)
        } else if (!captureState.value.isCapturing) {
            // Clean up VPN instances left by an earlier app/service process.
            setWebsiteBlocking(enabled = false)
        }
    }

    fun onStop() {
        CaptureSessionStore.setPreviewRequested(false)
    }

    fun onDestroy(isFinishing: Boolean, isTaskRoot: Boolean) {
        // Configuration changes do not satisfy isFinishing, so they do not
        // interrupt website protection.
        if (isFinishing && isTaskRoot) {
            context.stopService(Intent(context, AdultSiteVpnService::class.java))
        }
    }

    fun startCapture(settings: BlurSettings) {
        saveSettings(settings)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            publishMessage("This feature needs Android 10 or newer.")
            return
        }
        if (!settings.hasEnabledProtection) {
            publishMessage("Enable visual protection or music isolation before starting.")
            return
        }
        if (settings.isolateMusic &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCaptureSettings = settings
            permissionRequester.requestAudioPermission()
            return
        }
        continueStartingCapture(settings)
    }

    fun startIsolation(settings: BlurSettings) {
        saveSettings(settings)
        val remoteUrl = settings.musicSourceUrl.trim().takeIf { it.isNotEmpty() }
        val localUri = settings.musicSourceUri.trim().takeIf {
            it.isNotEmpty() && remoteUrl == null
        }
        if (remoteUrl == null && localUri == null) {
            updateState { current ->
                current.copy(audioStatus = "Choose a media file or enter a direct media URL first.")
            }
            return
        }
        updateState { current ->
            current.copy(
                message = "Loading the on-device audio model...",
                audioStatus = "Isolation is preparing...",
            )
        }
        TfLiteNative.initialize(context)
            .addOnSuccessListener {
                startIsolationService(settings, localUri, remoteUrl)
            }
            .addOnFailureListener { error ->
                updateState { current ->
                    current.copy(
                        message = "Could not initialize the audio model.",
                        audioStatus = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
    }

    fun stopProtection() {
        context.startService(
            Intent(context, ProtectionCaptureService::class.java)
                .setAction(ProtectionCaptureService.ACTION_STOP),
        )
        // Protection and the website-filter VPN have one lifecycle. Do not
        // leave the VPN running when the main protection status becomes OFF.
        setWebsiteBlocking(enabled = false)
    }

    fun onAudioPermissionResult(granted: Boolean) {
        val settings = pendingCaptureSettings.also { pendingCaptureSettings = null }
        if (granted && settings != null) {
            continueStartingCapture(settings)
        } else {
            updateState { current ->
                current.copy(message = "Audio permission is required only when music isolation is enabled.")
            }
        }
    }

    fun onOverlayPermissionResult() {
        if (Settings.canDrawOverlays(context)) {
            initializeRuntimeAndRequestCapture()
        } else {
            updateState { current ->
                current.copy(message = "Display-over-other-apps permission is required for device-level blur.")
            }
        }
    }

    fun onAccessibilitySettingsResult() {
        val settings = pendingTrustedOverlaySettings ?: return
        if (TrustedOverlayHost.isConnected) {
            pendingTrustedOverlaySettings = null
            initializeRuntimeAndRequestCapture()
        } else {
            updateState { current ->
                current.copy(
                    message = "Enable Halalify private blur overlay, then return to start full-opacity protection.",
                )
            }
        }
    }

    fun onProjectionPermissionResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            updateState { current -> current.copy(message = "Screen capture permission was not granted.") }
            context.startService(
                Intent(context, ProtectionCaptureService::class.java)
                    .setAction(ProtectionCaptureService.ACTION_STOP),
            )
            return
        }
        val serviceIntent = Intent(context, ProtectionCaptureService::class.java).apply {
            action = ProtectionCaptureService.ACTION_START
            putExtra(ProtectionCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProtectionCaptureService.EXTRA_PROJECTION_DATA, data)
        }
        try {
            // The service was prepared while the activity was visible, before
            // Android may move Halalify to the background.
            context.startService(serviceIntent)
        } catch (error: Exception) {
            updateState { current ->
                current.copy(
                    message = "Could not start screen monitoring: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun onVpnPermissionResult(resultCode: Int) {
        if (pendingWebsiteFilterEnable && resultCode == android.app.Activity.RESULT_OK) {
            startWebsiteFilterService()
        } else if (pendingWebsiteFilterEnable) {
            val pendingProtectionSettings = pendingWebsiteProtectionSettings
            pendingWebsiteProtectionSettings = null
            websiteFilterEnabled = false
            saveSettings(settingsRepository.load().copy(blockAdultSites = false))
            updateState { current ->
                current.copy(
                    message = if (pendingProtectionSettings != null) {
                        "VPN permission is required for the complete image/video protection profile."
                    } else {
                        "VPN permission was not granted; website blocking is off."
                    },
                )
            }
        }
        pendingWebsiteFilterEnable = false
    }

    private fun continueStartingCapture(settings: BlurSettings) {
        saveSettings(settings)
        if (settings.hasVisualProtection && settings.blockAdultSites && !websiteFilterEnabled) {
            val protectedSettings = settings.copy(blockAdultSites = true)
            saveSettings(protectedSettings)
            pendingWebsiteProtectionSettings = protectedSettings
            setWebsiteBlocking(enabled = true)
            return
        }
        continueStartingCaptureAfterWebsiteFilter(settings)
    }

    private fun continueStartingCaptureAfterWebsiteFilter(settings: BlurSettings) {
        saveSettings(settings)
        if (
            settings.hasVisualProtection &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !TrustedOverlayHost.isConnected
        ) {
            pendingTrustedOverlaySettings = settings
            updateState { current ->
                current.copy(
                    message = "Enable Halalify private blur overlay for full opacity with touch-through.",
                )
            }
            permissionRequester.requestAccessibilityService()
            return
        }
        if (
            settings.hasVisualProtection &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !Settings.canDrawOverlays(context)
        ) {
            updateState { current ->
                current.copy(message = "Allow Halalify to display over other apps, then return here.")
            }
            try {
                permissionRequester.requestOverlayPermission(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri(),
                    ),
                )
            } catch (error: Exception) {
                updateState { current ->
                    current.copy(
                        message = "Could not open overlay settings: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
            return
        }
        initializeRuntimeAndRequestCapture()
    }

    private fun initializeRuntimeAndRequestCapture() {
        updateState { current -> current.copy(message = "Loading the on-device protection models...") }
        TfLiteNative.initialize(context)
            .addOnSuccessListener { requestProjection() }
            .addOnFailureListener { error ->
                updateState { current ->
                    current.copy(
                        message = "LiteRT could not start: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
    }

    private fun requestProjection() {
        try {
            // Prepare the service before the sharing dialog can background the activity.
            context.startService(
                Intent(context, ProtectionCaptureService::class.java)
                    .setAction(ProtectionCaptureService.ACTION_PREPARE),
            )
            permissionRequester.requestScreenCapture()
        } catch (error: Exception) {
            updateState { current ->
                current.copy(
                    message = "Could not open screen sharing: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun setWebsiteBlocking(enabled: Boolean, persistPreference: Boolean = false) {
        if (!enabled) {
            websiteFilterEnabled = false
            if (persistPreference) {
                saveSettings(settingsRepository.load().copy(blockAdultSites = false))
            }
            context.startService(
                Intent(context, AdultSiteVpnService::class.java)
                    .setAction(AdultSiteVpnService.ACTION_STOP),
            )
            return
        }

        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingWebsiteFilterEnable = true
            permissionRequester.requestVpnPermission(prepareIntent)
        } else {
            startWebsiteFilterService()
        }
    }

    private fun startWebsiteFilterService() {
        websiteFilterEnabled = true
        saveSettings(settingsRepository.load().copy(blockAdultSites = true))
        val intent = Intent(context, AdultSiteVpnService::class.java)
            .setAction(AdultSiteVpnService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (error: Exception) {
            websiteFilterEnabled = false
            saveSettings(settingsRepository.load().copy(blockAdultSites = false))
            pendingWebsiteProtectionSettings = null
            updateState { current ->
                current.copy(
                    message = "Website protection could not start: ${error.message ?: error.javaClass.simpleName}",
                )
            }
            return
        }
        val pendingProtectionSettings = pendingWebsiteProtectionSettings
        pendingWebsiteProtectionSettings = null
        if (pendingProtectionSettings != null) {
            continueStartingCaptureAfterWebsiteFilter(pendingProtectionSettings)
        }
    }

    private fun startIsolationService(
        settings: BlurSettings,
        localUri: String?,
        remoteUrl: String?,
    ) {
        val intent = Intent(context, AudioIsolationService::class.java).apply {
            action = AudioIsolationService.ACTION_START
            putExtra(AudioIsolationService.EXTRA_LOCAL_URI, localUri)
            putExtra(AudioIsolationService.EXTRA_REMOTE_URL, remoteUrl)
            putExtra(
                AudioIsolationService.EXTRA_DISPLAY_NAME,
                settings.musicSourceFileName.ifBlank { "media" },
            )
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (error: Exception) {
            updateState { current ->
                current.copy(
                    message = "Could not start media isolation.",
                    audioStatus = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun publishMessage(message: String) {
        updateState { current -> current.copy(message = message) }
    }

    private fun updateState(transform: (CaptureUiState) -> CaptureUiState) {
        CaptureSessionStore.updateState(transform)
    }
}

/** Activity-owned permission and system-dialog entry points. */
internal interface PermissionRequester {
    fun requestAudioPermission()
    fun requestOverlayPermission(intent: Intent)
    fun requestAccessibilityService()
    fun requestScreenCapture()
    fun requestVpnPermission(intent: Intent)
}
