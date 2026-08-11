package com.halalify.kotlin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import com.google.android.gms.tflite.java.TfLiteNative
import com.halalify.kotlin.capture.CaptureSessionStore
import com.halalify.kotlin.capture.ProtectionCaptureService
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurSettingsRepository
import com.halalify.kotlin.ui.HalalifyApp

// Halalify uses ComponentActivity directly and does not include FragmentActivity; the Fragment
// compatibility warning attached to Activity Result registration therefore does not apply.
@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: BlurSettingsRepository
    private var pendingCaptureSettings: BlurSettings? = null
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = BlurSettingsRepository(applicationContext)
        setContent {
            val captureState by CaptureSessionStore.state.collectAsState()
            HalalifyApp(
                initialSettings = settingsRepository.load(),
                captureState = captureState,
                onSave = settingsRepository::save,
                onStartCapture = ::startCapture,
                onStopCapture = {
                    startService(
                        Intent(this, ProtectionCaptureService::class.java)
                            .setAction(ProtectionCaptureService.ACTION_STOP),
                    )
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        CaptureSessionStore.setPreviewRequested(true)
    }

    override fun onStop() {
        CaptureSessionStore.setPreviewRequested(false)
        super.onStop()
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

    private fun continueStartingCapture(settings: BlurSettings) {
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
