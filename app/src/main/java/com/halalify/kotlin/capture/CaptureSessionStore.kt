package com.halalify.kotlin.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CaptureUiState(
    val isCapturing: Boolean = false,
    val targetLabel: String? = null,
    val message: String = "Choose an app to begin.",
    val previewJpeg: ByteArray? = null,
)

internal object CaptureSessionStore {
    private val mutableState = MutableStateFlow(CaptureUiState())
    val state = mutableState.asStateFlow()
    fun update(
        isCapturing: Boolean = mutableState.value.isCapturing,
        targetLabel: String? = mutableState.value.targetLabel,
        message: String = mutableState.value.message,
        previewJpeg: ByteArray? = mutableState.value.previewJpeg,
    ) { mutableState.value = CaptureUiState(isCapturing, targetLabel, message, previewJpeg) }
}
