package com.halalify.kotlin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.tflite.java.TfLiteNative
import com.halalify.kotlin.capture.AudioCaptureService
import com.halalify.kotlin.capture.CaptureSessionStore
import com.halalify.kotlin.settings.BlurSettings
import com.halalify.kotlin.settings.BlurSettingsRepository
import com.halalify.kotlin.ui.HalalifyApp

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: BlurSettingsRepository
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            initializeVisionAndRequestCapture()
        } else {
            CaptureSessionStore.update(
                message = "Display-over-other-apps permission is required for device-level blur.",
            )
        }
    }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            CaptureSessionStore.update(message = "Screen and audio capture permission was not granted.")
            startService(
                Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP),
            )
            return@registerForActivityResult
        }
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(AudioCaptureService.EXTRA_PROJECTION_DATA, result.data)
        }
        try {
            // The service was started while this activity was visible, before Android's
            // app-selection screen moved Halalify to the background.
            startService(serviceIntent)
        } catch (error: Throwable) {
            CaptureSessionStore.update(
                message = "Could not start screen monitoring: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }
    private val recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestProjection() else {
            CaptureSessionStore.update(message = "Audio permission was denied.")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = BlurSettingsRepository(applicationContext)
        setContent {
            HalalifyApp(
                initialSettings = settingsRepository.load(),
                onSave = settingsRepository::save,
                onStartCapture = ::startCapture,
                onStopCapture = {
                    startService(Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
                },
            )
        }
    }

    private fun startCapture(settings: BlurSettings) {
        settingsRepository.save(settings)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            CaptureSessionStore.update(message = "This feature needs Android 10 or newer.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            CaptureSessionStore.update(
                message = "Allow Halalify to display over other apps, then return here.",
            )
            try {
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (error: Throwable) {
                CaptureSessionStore.update(
                    message = "Could not open overlay settings: ${error.message ?: error.javaClass.simpleName}",
                )
            }
            return
        }
        initializeVisionAndRequestCapture()
    }

    private fun initializeVisionAndRequestCapture() {
        CaptureSessionStore.update(message = "Loading the on-device gender detection model...")
        TfLiteNative.initialize(applicationContext)
            .addOnSuccessListener { requestAudioThenProjection() }
            .addOnFailureListener { error ->
                CaptureSessionStore.update(
                    message = "LiteRT could not start: ${error.message ?: error.javaClass.simpleName}",
                )
            }
    }

    private fun requestAudioThenProjection() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestProjection()
        } else {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestProjection() {
        try {
            // Starting a lightweight service now preserves a legal launch path when the
            // user chooses one app and Android backgrounds this activity before returning.
            startService(
                Intent(this, AudioCaptureService::class.java)
                    .setAction(AudioCaptureService.ACTION_PREPARE),
            )
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay(),
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            projectionLauncher.launch(captureIntent)
        } catch (error: Throwable) {
            CaptureSessionStore.update(
                message = "Could not open screen sharing: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }
}
