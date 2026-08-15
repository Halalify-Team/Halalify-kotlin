package com.halalify.kotlin.network

import java.io.Closeable

/** Shared native policy engine used by Android now and reusable by iOS later. */
internal class NativeSiteFilterEngine(blocklistBytes: ByteArray) : Closeable {
    private var nativeHandle = nativeCreate(blocklistBytes)

    @Synchronized
    fun isBlocked(domain: String): Boolean {
        check(nativeHandle != 0L) { "Site filter engine is closed." }
        return nativeIsBlocked(nativeHandle, domain)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(blocklistBytes: ByteArray): Long
    private external fun nativeIsBlocked(handle: Long, domain: String): Boolean
    private external fun nativeDestroy(handle: Long)

    private companion object {
        init {
            System.loadLibrary("halalify_android_jni")
        }
    }
}
