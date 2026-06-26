package com.halalify.kotlin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.halalify.kotlin.ui.navigation.AppNavigation
import com.halalify.kotlin.ui.theme.HalalifyTheme
import com.halalify.kotlin.viewmodel.HalalifyViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: HalalifyViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HalalifyTheme {
                AppNavigation(
                    activity = this,
                    viewModel = viewModel,
                )
            }
        }
        viewModel.warmUpLocalTools(this)
        requestNotificationPermissionIfNeeded()
        handleIntent(intent)


    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                val youtubeUrl = extractYoutubeUrl(sharedText)
                if (youtubeUrl != null) {
                    viewModel.acceptSharedYoutubeUrl(youtubeUrl)
                }
            }
        }
    }

    private fun extractYoutubeUrl(text: String): String? {
        val regex = """https?://[^\s]+""".toRegex()
        return regex.find(text)?.value
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(permission)
    }
}
