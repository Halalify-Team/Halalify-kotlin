package com.halalify.kotlin.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
<<<<<<< HEAD
=======
import kotlinx.coroutines.flow.update
>>>>>>> origin/master

internal data class CaptureUiState(
    val isCapturing: Boolean = false,
    val targetLabel: String? = null,
<<<<<<< HEAD
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
=======
    val message: String = "Choose your preferences, then start protection.",
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
>>>>>>> origin/master
}
