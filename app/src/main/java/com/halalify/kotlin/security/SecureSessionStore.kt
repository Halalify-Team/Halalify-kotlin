package com.halalify.kotlin.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal class SecureSessionStore(context: Context) {
    private val legacyPrefs = context.getSharedPreferences("halalify_session", 0)
    private val securePrefs = createSecurePrefs(context) ?: legacyPrefs

    init {
        migrateLegacySession()
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return securePrefs.getString(key, null)
            ?: legacyPrefs.getString(key, defaultValue)
            ?: defaultValue
    }

    fun putSession(sessionToken: String, backendUrl: String, devEmail: String) {
        securePrefs.edit()
            .putString(KEY_SESSION_TOKEN, sessionToken)
            .putString(KEY_BACKEND_URL, backendUrl)
            .putString(KEY_DEV_EMAIL, devEmail)
            .apply()
        legacyPrefs.edit().remove(KEY_SESSION_TOKEN).apply()
    }

    fun removeSessionToken() {
        securePrefs.edit().remove(KEY_SESSION_TOKEN).apply()
        legacyPrefs.edit().remove(KEY_SESSION_TOKEN).apply()
    }

    private fun migrateLegacySession() {
        val legacyToken = legacyPrefs.getString(KEY_SESSION_TOKEN, null)
        val legacyBackendUrl = legacyPrefs.getString(KEY_BACKEND_URL, null)
        val legacyDevEmail = legacyPrefs.getString(KEY_DEV_EMAIL, null)
        if (legacyToken.isNullOrBlank() && legacyBackendUrl.isNullOrBlank() && legacyDevEmail.isNullOrBlank()) {
            return
        }
        securePrefs.edit()
            .apply {
                legacyToken?.let { putString(KEY_SESSION_TOKEN, it) }
                legacyBackendUrl?.let { putString(KEY_BACKEND_URL, it) }
                legacyDevEmail?.let { putString(KEY_DEV_EMAIL, it) }
            }
            .apply()
        legacyPrefs.edit().remove(KEY_SESSION_TOKEN).apply()
    }

    private fun createSecurePrefs(context: Context): SharedPreferences? {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "halalify_secure_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    companion object {
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_DEV_EMAIL = "dev_email"
    }
}
