package com.halalify.kotlin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.halalify.kotlin.capture.AudioCaptureService
import com.halalify.kotlin.capture.CaptureSessionStore
import com.halalify.kotlin.settings.BlurSettingsRepository
import com.halalify.kotlin.ui.HalalifyApp

class MainActivity : ComponentActivity() {
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            CaptureSessionStore.update(message = "Screen and audio capture permission was not granted.")
            return@registerForActivityResult
        }
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(AudioCaptureService.EXTRA_PROJECTION_DATA, result.data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
    }
    private val recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestProjection() else {
            CaptureSessionStore.update(message = "Audio permission was denied.")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepository = BlurSettingsRepository(applicationContext)
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

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            CaptureSessionStore.update(message = "This feature needs Android 10 or newer.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection()
        else recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestProjection() {
        projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
