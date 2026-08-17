package com.halalify.kotlin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.google.android.gms.tflite.java.TfLiteNative
import com.halalify.kotlin.capture.AudioIsolationService
import com.halalify.kotlin.capture.CaptureSessionStore
import com.halalify.kotlin.capture.ProtectionCaptureService
import com.halalify.kotlin.network.AdultSiteVpnService
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurSettingsRepository
import com.halalify.kotlin.ui.HalalifyApp

// Halalify uses ComponentActivity directly and does not include FragmentActivity; the Fragment
// compatibility warning attached to Activity Result registration therefore does not apply.
@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: BlurSettingsRepository
    private var pendingCaptureSettings: BlurSettings? = null
    private var pendingWebsiteFilterEnable = false
    private var pendingWebsiteProtectionSettings: BlurSettings? = null
    private var websiteFilterEnabled by mutableStateOf(false)
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val settings = pendingCaptureSettings.also { pendingCaptureSettings = null }
        if (granted && settings != null) {
            continueStartingCapture(settings)
        } else {
            CaptureSessionStore.updateState { current ->
                current.copy(
                    message = "Audio permission is required only when music isolation is enabled.",
                )
            }
        }
    }
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            initializeRuntimeAndRequestCapture()
        } else {
            CaptureSessionStore.updateState { current ->
                current.copy(
                    message = "Display-over-other-apps permission is required for device-level blur.",
                )
            }
        }
    }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            CaptureSessionStore.updateState { current ->
                current.copy(message = "Screen capture permission was not granted.")
            }
            startService(
                Intent(this, ProtectionCaptureService::class.java)
                    .setAction(ProtectionCaptureService.ACTION_STOP),
            )
            return@registerForActivityResult
        }
        val serviceIntent = Intent(this, ProtectionCaptureService::class.java).apply {
            action = ProtectionCaptureService.ACTION_START
            putExtra(ProtectionCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ProtectionCaptureService.EXTRA_PROJECTION_DATA, result.data)
        }
        try {
            // The service was started while this activity was visible, before Android's
            // app-selection screen moved Halalify to the background.
            startService(serviceIntent)
        } catch (error: Exception) {
            CaptureSessionStore.updateState { current ->
                current.copy(
                    message = "Could not start screen monitoring: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }
    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (pendingWebsiteFilterEnable && result.resultCode == Activity.RESULT_OK) {
            startWebsiteFilterService()
        } else if (pendingWebsiteFilterEnable) {
            val pendingProtectionSettings = pendingWebsiteProtectionSettings
            pendingWebsiteProtectionSettings = null
            websiteFilterEnabled = false
            settingsRepository.save(settingsRepository.load().copy(blockAdultSites = false))
            CaptureSessionStore.updateState { current ->
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = BlurSettingsRepository(applicationContext)
        websiteFilterEnabled = settingsRepository.load().blockAdultSites
        setContent {
            val captureState by CaptureSessionStore.state.collectAsState()
            HalalifyApp(
                initialSettings = settingsRepository.load(),
                captureState = captureState,
                onSave = settingsRepository::save,
                onStartCapture = ::startCapture,
                onStartIsolation = ::startIsolation,
                onStopCapture = {
                    stopProtection()
                },
                websiteFilterEnabled = websiteFilterEnabled,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        CaptureSessionStore.setPreviewRequested(true)
        if (websiteFilterEnabled && !pendingWebsiteFilterEnable) setWebsiteBlocking(true)
    }

    override fun onStop() {
        CaptureSessionStore.setPreviewRequested(false)
        // Website protection is intentionally tied to the visible app session.
        // onStart() starts it again when the user returns to Halalify.
        if (websiteFilterEnabled) {
            stopService(Intent(this, AdultSiteVpnService::class.java))
        }
        super.onStop()
    }

    override fun onDestroy() {
        // Stop the VPN when the root activity is explicitly closed (for example
        // with Back). Configuration changes do not satisfy isFinishing, so they
        // do not interrupt website protection.
        if (isFinishing && isTaskRoot) {
            stopService(Intent(this, AdultSiteVpnService::class.java))
        }
        super.onDestroy()
    }

    private fun startCapture(settings: BlurSettings) {
        settingsRepository.save(settings)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            CaptureSessionStore.updateState { current ->
                current.copy(message = "This feature needs Android 10 or newer.")
            }
            return
        }
        if (!settings.hasEnabledProtection) {
            CaptureSessionStore.updateState { current ->
                current.copy(message = "Enable visual protection or music isolation before starting.")
            }
            return
        }
        if (settings.isolateMusic &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCaptureSettings = settings
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        continueStartingCapture(settings)
    }

    private fun startIsolation(settings: BlurSettings) {
        settingsRepository.save(settings)
        val remoteUrl = settings.musicSourceUrl.trim().takeIf { it.isNotEmpty() }
        val localUri = settings.musicSourceUri.trim().takeIf {
            it.isNotEmpty() && remoteUrl == null
        }
        if (remoteUrl == null && localUri == null) {
            CaptureSessionStore.updateState { current ->
                current.copy(audioStatus = "Choose a media file or enter a direct media URL first.")
            }
            return
        }
        CaptureSessionStore.updateState { current ->
            current.copy(
                message = "Loading the on-device audio model...",
                audioStatus = "Isolation is preparing...",
            )
        }
        TfLiteNative.initialize(applicationContext)
            .addOnSuccessListener {
                startIsolationService(settings, localUri, remoteUrl)
            }
            .addOnFailureListener { error ->
                CaptureSessionStore.updateState { current ->
                    current.copy(
                        message = "Could not initialize the audio model.",
                        audioStatus = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
    }

    private fun startIsolationService(
        settings: BlurSettings,
        localUri: String?,
        remoteUrl: String?,
    ) {
        val intent = Intent(this, AudioIsolationService::class.java).apply {
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
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (error: Exception) {
            CaptureSessionStore.updateState { current ->
                current.copy(
                    message = "Could not start media isolation.",
                    audioStatus = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun setWebsiteBlocking(enabled: Boolean) {
        if (!enabled) {
            websiteFilterEnabled = false
            settingsRepository.save(settingsRepository.load().copy(blockAdultSites = false))
            startService(
                Intent(this, AdultSiteVpnService::class.java)
                    .setAction(AdultSiteVpnService.ACTION_STOP),
            )
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingWebsiteFilterEnable = true
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startWebsiteFilterService()
        }
    }

    private fun startWebsiteFilterService() {
        websiteFilterEnabled = true
        settingsRepository.save(settingsRepository.load().copy(blockAdultSites = true))
        val intent = Intent(this, AdultSiteVpnService::class.java)
            .setAction(AdultSiteVpnService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (error: Exception) {
            websiteFilterEnabled = false
            settingsRepository.save(settingsRepository.load().copy(blockAdultSites = false))
            pendingWebsiteProtectionSettings = null
            CaptureSessionStore.updateState { current ->
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

    private fun stopProtection() {
        startService(
            Intent(this, ProtectionCaptureService::class.java)
                .setAction(ProtectionCaptureService.ACTION_STOP),
        )
        if (websiteFilterEnabled) setWebsiteBlocking(false)
    }

    private fun continueStartingCapture(settings: BlurSettings) {
        settingsRepository.save(settings)
        if (settings.hasVisualProtection && !websiteFilterEnabled) {
            val protectedSettings = settings.copy(blockAdultSites = true)
            settingsRepository.save(protectedSettings)
            pendingWebsiteProtectionSettings = protectedSettings
            setWebsiteBlocking(true)
            return
        }
        continueStartingCaptureAfterWebsiteFilter(settings)
    }

    private fun continueStartingCaptureAfterWebsiteFilter(settings: BlurSettings) {
        settingsRepository.save(settings)
        if (settings.hasVisualProtection && !Settings.canDrawOverlays(this)) {
            CaptureSessionStore.updateState { current ->
                current.copy(
                    message = "Allow Halalify to display over other apps, then return here.",
                )
            }
            try {
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri(),
                    ),
                )
            } catch (error: Exception) {
                CaptureSessionStore.updateState { current ->
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
        CaptureSessionStore.updateState { current ->
            current.copy(message = "Loading the on-device protection models...")
        }
        TfLiteNative.initialize(applicationContext)
            .addOnSuccessListener { requestProjection() }
            .addOnFailureListener { error ->
                CaptureSessionStore.updateState { current ->
                    current.copy(
                        message = "LiteRT could not start: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
    }

    private fun requestProjection() {
        try {
            // Starting a lightweight service now preserves a legal launch path when the
            // user chooses one app and Android backgrounds this activity before returning.
            startService(
                Intent(this, ProtectionCaptureService::class.java)
                    .setAction(ProtectionCaptureService.ACTION_PREPARE),
            )
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    // Let Android show both choices: the entire display or one app.
                    // Requesting the default-display config disables the app option.
                    MediaProjectionConfig.createConfigForUserChoice(),
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            projectionLauncher.launch(captureIntent)
        } catch (error: Exception) {
            CaptureSessionStore.updateState { current ->
                current.copy(
                    message = "Could not open screen sharing: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }
}