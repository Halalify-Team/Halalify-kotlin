package com.halalify.kotlin.capture

import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSessionStoreTest {
    @Test
    fun `concurrent state transformations do not lose updates`() {
        try {
            CaptureSessionStore.updateState {
                CaptureUiState(message = "0", audioStatus = "0")
            }
            val start = CountDownLatch(1)
            val messageThread = Thread {
                start.await()
                repeat(500) {
                    CaptureSessionStore.updateState { current ->
                        current.copy(message = (current.message.toInt() + 1).toString())
                    }
                }
            }
            val audioThread = Thread {
                start.await()
                repeat(500) {
                    CaptureSessionStore.updateState { current ->
                        current.copy(
                            audioStatus = ((current.audioStatus?.toInt() ?: 0) + 1).toString(),
                        )
                    }
                }
            }

            messageThread.start()
            audioThread.start()
            start.countDown()
            messageThread.join()
            audioThread.join()

            assertEquals("500", CaptureSessionStore.state.value.message)
            assertEquals("500", CaptureSessionStore.state.value.audioStatus)
        } finally {
            CaptureSessionStore.updateState { CaptureUiState() }
        }
    }
}
