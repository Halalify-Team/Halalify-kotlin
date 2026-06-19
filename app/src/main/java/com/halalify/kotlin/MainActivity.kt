package com.halalify.kotlin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.halalify.kotlin.ui.navigation.AppNavigation
import com.halalify.kotlin.ui.theme.HalalifyTheme
import com.halalify.kotlin.viewmodel.HalalifyViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HalalifyTheme {
                val viewModel: HalalifyViewModel = viewModel()
                AppNavigation(
                    activity = this,
                    viewModel = viewModel,
                )
            }
        }
    }
}
