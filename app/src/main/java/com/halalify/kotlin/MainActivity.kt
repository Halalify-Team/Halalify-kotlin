package com.halalify.kotlin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.halalify.kotlin.app.HalalifyAppCoordinator
import com.halalify.kotlin.app.PermissionRequester
import com.halalify.kotlin.ui.HalalifyApp

/** Android entry point: compose the UI and delegate application work to the coordinator. */
@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {
    private lateinit var appCoordinator: HalalifyAppCoordinator

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> appCoordinator.onAudioPermissionResult(granted) }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { appCoordinator.onOverlayPermissionResult() }

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { appCoordinator.onAccessibilitySettingsResult() }

    private val projectionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> appCoordinator.onProjectionPermissionResult(result.resultCode, result.data) }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> appCoordinator.onVpnPermissionResult(result.resultCode) }

    private val permissionRequester = object : PermissionRequester {
        override fun requestAudioPermission() {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        override fun requestOverlayPermission(intent: Intent) {
            overlayPermissionLauncher.launch(intent)
        }

        override fun requestAccessibilityService() {
            accessibilitySettingsLauncher.launch(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            )
        }

        override fun requestScreenCapture() {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForUserChoice(),
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            projectionPermissionLauncher.launch(captureIntent)
        }

        override fun requestVpnPermission(intent: Intent) {
            vpnPermissionLauncher.launch(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appCoordinator = HalalifyAppCoordinator(
            context = applicationContext,
            permissionRequester = permissionRequester,
        )
        setContent {
            val captureState by appCoordinator.captureState.collectAsState()
            HalalifyApp(
                initialSettings = appCoordinator.initialSettings,
                captureState = captureState,
                onSave = appCoordinator::saveSettings,
                onStartCapture = appCoordinator::startCapture,
                onStopCapture = appCoordinator::stopProtection,
                onStartIsolation = appCoordinator::startIsolation,
                onWebsiteProtectionChange = appCoordinator::setWebsiteProtection,
                websiteFilterEnabled = appCoordinator.websiteFilterEnabled,
                accessibilityGuideVisible = appCoordinator.accessibilityGuideVisible,
                onAccessibilityGuideContinue = appCoordinator::continueAccessibilitySetup,
                onAccessibilityGuideDismiss = appCoordinator::dismissAccessibilitySetup,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        appCoordinator.onStart()
    }

    override fun onStop() {
        appCoordinator.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        appCoordinator.onDestroy(isFinishing = isFinishing, isTaskRoot = isTaskRoot)
        super.onDestroy()
    }
}
