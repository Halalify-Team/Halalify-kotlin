package com.halalify.kotlin.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class CaptureUiState(
    val isCapturing: Boolean = false,
    val targetLabel: String? = null,
    val message: String = "Tap the circle to start protection.",
    val audioStatus: String? = null,
    val previewJpeg: ByteArray? = null,
)

internal interface CaptureStatePublisher {
    val isPreviewRequested: Boolean
    fun updateState(transform: (CaptureUiState) -> CaptureUiState)
}

internal object CaptureSessionStore : CaptureStatePublisher {
    private val mutableState = MutableStateFlow(CaptureUiState())
    val state = mutableState.asStateFlow()
    @Volatile override var isPreviewRequested: Boolean = false
        private set

    fun setPreviewRequested(requested: Boolean) {
        isPreviewRequested = requested
        if (!requested) {
            updateState { current -> current.copy(previewJpeg = null) }
        }
    }

    override fun updateState(transform: (CaptureUiState) -> CaptureUiState) {
        mutableState.update(transform)
    }
}
