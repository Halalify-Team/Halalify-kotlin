package com.halalify.kotlin.network

import androidx.activity.ComponentActivity
import com.halalify.kotlin.model.FileResult
import com.halalify.kotlin.model.QuotaState
import com.halalify.kotlin.model.UploadStart
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal suspend fun loginWithBackendDevAccount(
    backendUrl: String,
    email: String,
): Pair<String, String?> = withContext(Dispatchers.IO) {
    val result = loginWithBackendDevAccountDetailed(backendUrl, email)
    result.message to result.sessionToken
}

internal data class DevLoginResult(
    val message: String,
    val sessionToken: String?,
    val quota: QuotaState? = null,
)

internal suspend fun loginWithBackendDevAccountDetailed(
    backendUrl: String,
    email: String,
): DevLoginResult = withContext(Dispatchers.IO) {
    try {
        val baseUrl = backendUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            error("Backend URL is required.")
        }
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) {
            error("Dev email is required.")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val json = JSONObject()
            .put("email", cleanEmail)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/auth/mobile-dev-login")
            .header("Content-Type", "application/json")
            .header("X-Platform", "android-debug")
            .post(json)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(httpErrorMessage("Sign in failed", response.code, body))
            }
            val payload = JSONObject(body)
            if (!payload.optBoolean("success")) {
                error("Dev login rejected: ${body.take(1200)}")
            }
            val token = payload.optString("sessionToken")
            if (token.isBlank()) {
                error("Dev login response has no sessionToken: ${body.take(1200)}")
            }
            val quota = payload.optJSONObject("quota")
            val subscription = payload.optJSONObject("subscription")
            val quotaState = QuotaState(
                userId = payload.optString("userId"),
                email = payload.optString("email", cleanEmail),
                plan = payload.optString("plan", "unknown"),
                accountStatus = payload.optString("status", "unknown"),
                minutesRemaining = quota?.optDoubleOrNull("minutesRemaining"),
                minutesTotal = quota?.optDoubleOrNull("minutesTotal"),
                customerPortalUrl = subscription?.optString("customerPortalUrl")?.takeIf { it.isNotBlank() && it != "null" },
                statusMessage = "Quota loaded from login.",
            )
            DevLoginResult(
                message = "SUCCESS: dev session ready.\n" +
                "email: ${payload.optString("email", cleanEmail)}\n" +
                "plan: ${payload.optString("plan", "unknown")}\n" +
                "minutesRemaining: ${quota?.opt("minutesRemaining") ?: "unknown"}\n" +
                    "token: ${token.take(10)}...",
                sessionToken = token,
                quota = quotaState,
            )
        }
    } catch (error: Throwable) {
        DevLoginResult(
            message = "FAILED: ${error.toBackendUserMessage("sign in")}",
            sessionToken = null,
        )
    }
}

internal suspend fun loginWithGoogleIdTokenDetailed(
    backendUrl: String,
    idToken: String,
): DevLoginResult = withContext(Dispatchers.IO) {
    try {
        val baseUrl = backendUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            error("Backend URL is required.")
        }
        val cleanIdToken = idToken.trim()
        if (cleanIdToken.isBlank()) {
            error("Google sign-in did not return an ID token.")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val json = JSONObject()
            .put("idToken", cleanIdToken)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/auth/google")
            .header("Content-Type", "application/json")
            .header("X-Platform", "android")
            .post(json)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(httpErrorMessage("Google sign-in failed", response.code, body))
            }
            val payload = JSONObject(body)
            if (!payload.optBoolean("success")) {
                error("Google sign-in rejected: ${body.take(1200)}")
            }
            val token = payload.optString("sessionToken")
            if (token.isBlank()) {
                error("Google sign-in response has no sessionToken: ${body.take(1200)}")
            }
            val quota = payload.optJSONObject("quota")
            val subscription = payload.optJSONObject("subscription")
            val email = payload.optString("email", "Google account")
            val quotaState = QuotaState(
                userId = payload.optString("userId"),
                email = email,
                plan = payload.optString("plan", "unknown"),
                accountStatus = payload.optString("status", "unknown"),
                minutesRemaining = quota?.optDoubleOrNull("minutesRemaining"),
                minutesTotal = quota?.optDoubleOrNull("minutesTotal"),
                customerPortalUrl = subscription?.optString("customerPortalUrl")?.takeIf { it.isNotBlank() && it != "null" },
                statusMessage = "Quota loaded from Google sign-in.",
            )
            DevLoginResult(
                message = "SUCCESS: signed in with Google.\n" +
                    "email: $email\n" +
                    "plan: ${payload.optString("plan", "unknown")}\n" +
                    "minutesRemaining: ${quota?.opt("minutesRemaining") ?: "unknown"}",
                sessionToken = token,
                quota = quotaState,
            )
        }
    } catch (error: Throwable) {
        DevLoginResult(
            message = "FAILED: ${error.toBackendUserMessage("sign in with Google")}",
            sessionToken = null,
        )
    }
}

internal suspend fun fetchQuotaState(
    backendUrl: String,
    sessionToken: String,
): QuotaState = withContext(Dispatchers.IO) {
    val baseUrl = backendUrl.trim().trimEnd('/')
    if (baseUrl.isBlank()) {
        error("Backend URL is required.")
    }
    val token = sessionToken.trim()
    if (token.isBlank()) {
        error("Session token is required.")
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    val validateRequest = Request.Builder()
        .url("$baseUrl/auth/validate")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    val identity = client.newCall(validateRequest).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error(httpErrorMessage("Session validation failed", response.code, body))
        }
        val json = JSONObject(body)
        if (!json.optBoolean("valid")) {
            error("Session is invalid or expired.")
        }
        json
    }

    val userId = identity.optString("userId")
    if (userId.isBlank()) {
        error("Session validation response has no userId.")
    }

    val statusRequest = Request.Builder()
        .url("$baseUrl/user/status?user_id=$userId")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    client.newCall(statusRequest).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error(httpErrorMessage("Quota refresh failed", response.code, body))
        }
        val json = JSONObject(body)
        if (json.optString("status") != "success") {
            error("Quota refresh rejected: ${body.take(1200)}")
        }
        val data = json.getJSONObject("data")
        val usage = data.getJSONObject("usage")
        val subscription = data.optJSONObject("subscription")
        QuotaState(
            userId = userId,
            email = data.optString("email", identity.optString("email")),
            plan = data.optString("plan", "unknown"),
            accountStatus = data.optString("status", "unknown"),
            minutesRemaining = usage.optDoubleOrNull("minutesRemaining"),
            minutesTotal = usage.optDoubleOrNull("minutesTotal"),
            minutesUsed = usage.optDoubleOrNull("minutesUsed"),
            usagePercent = usage.optIntOrNull("usagePercent"),
            resetDate = subscription?.optString("resetDate")?.takeIf { it.isNotBlank() && it != "null" },
            customerPortalUrl = subscription?.optString("customerPortalUrl")?.takeIf { it.isNotBlank() && it != "null" },
            statusMessage = "Quota refreshed.",
        )
    }
}

internal suspend fun cleanAudioWithBackend(
    activity: ComponentActivity,
    inputPath: String?,
    backendUrl: String,
    sessionToken: String,
    chunkIndex: Int = 0,
    durationSeconds: Int = 10,
): FileResult = withContext(Dispatchers.IO) {
    try {
        val source = inputPath?.let(::File)
            ?: error("Extract an audio chunk before sending it to the backend.")
        if (!source.isFile || source.length() <= 0L) {
            error("Input audio file is missing or empty: ${source.absolutePath}")
        }

        val baseUrl = backendUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            error("Backend URL is required.")
        }
        val token = sessionToken.trim()
        if (token.isBlank()) {
            error("Session token is required because /upload_chunk is protected.")
        }

        val outputDir = File(activity.filesDir, "halalify-clean-audio-backend")
        outputDir.mkdirs()

        val startedAt = System.currentTimeMillis()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val uploadStart = uploadAudioChunk(
            client = client,
            baseUrl = baseUrl,
            token = token,
            source = source,
            chunkIndex = chunkIndex,
            durationSeconds = durationSeconds,
        )
        val cleanUrl = pollCleanAudioUrl(
            client = client,
            baseUrl = baseUrl,
            chunkKey = uploadStart.chunkKey,
        )
        val outputFile = File(outputDir, "clean_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.mp3")
        downloadFile(client, cleanUrl, outputFile)

        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("Backend returned a URL but no clean audio was downloaded.")
        }

        val elapsedMs = System.currentTimeMillis() - startedAt
        FileResult(
            message = "SUCCESS: backend returned clean audio.\n" +
                "chunk_key: ${uploadStart.chunkKey}\n" +
                "minutesRemaining: ${uploadStart.minutesRemaining ?: "unknown"}\n" +
                "size: ${outputFile.length()} bytes\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
            minutesRemaining = uploadStart.minutesRemaining?.toDoubleOrNull(),
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.toBackendUserMessage("remove music")}",
            path = null,
        )
    }
}

private fun uploadAudioChunk(
    client: OkHttpClient,
    baseUrl: String,
    token: String,
    source: File,
    chunkIndex: Int,
    durationSeconds: Int,
): UploadStart {
    val audioType = when (source.extension.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }.toMediaType()
    val multipart = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            "file",
            "chunk_$chunkIndex.${source.extension.ifBlank { "m4a" }}",
            source.asRequestBody(audioType),
        )
        .addFormDataPart("chunk_index", chunkIndex.toString())
        .addFormDataPart("duration", durationSeconds.toString())
        .build()

    val request = Request.Builder()
        .url("$baseUrl/upload_chunk")
        .header("Authorization", "Bearer $token")
        .post(multipart)
        .build()

    client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error(httpErrorMessage("Audio upload failed", response.code, body))
        }
        val json = JSONObject(body)
        if (json.optString("status") != "processing") {
            error("Unexpected upload response: ${body.take(1200)}")
        }
        val chunkKey = json.optString("chunk_key")
        if (chunkKey.isBlank()) {
            error("Backend did not return chunk_key: ${body.take(1200)}")
        }
        return UploadStart(
            chunkKey = chunkKey,
            minutesRemaining = json.opt("minutesRemaining")?.toString(),
        )
    }
}

private suspend fun pollCleanAudioUrl(
    client: OkHttpClient,
    baseUrl: String,
    chunkKey: String,
): String {
    repeat(45) { attempt ->
        val request = Request.Builder()
            .url("$baseUrl/chunk_status/$chunkKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(httpErrorMessage("Clean audio status failed", response.code, body))
            }
            val json = JSONObject(body)
            when (val status = json.optString("status")) {
                "ready" -> {
                    val url = json.optString("url")
                    if (url.isBlank()) {
                        error("Chunk is ready but response has no url: ${body.take(1200)}")
                    }
                    return url
                }
                "error" -> error(json.optString("error", "Backend processing failed."))
                "processing" -> Unit
                else -> error("Unknown chunk status '$status': ${body.take(1200)}")
            }
        }
        delay(3_000L)
    }
    error("Timed out waiting for backend clean audio.")
}

private fun downloadFile(client: OkHttpClient, url: String, outputFile: File) {
    val request = Request.Builder()
        .url(url)
        .get()
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            error(httpErrorMessage("Clean audio download failed", response.code, ""))
        }
        val body = response.body ?: error("Clean audio download returned an empty body.")
        FileOutputStream(outputFile).use { output ->
            body.byteStream().copyTo(output)
        }
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return runCatching { optDouble(name) }
        .getOrNull()
        ?.takeIf { !it.isNaN() }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return runCatching { optInt(name) }.getOrNull()
}

private fun httpErrorMessage(prefix: String, code: Int, body: String): String {
    val serverMessage = body.extractServerMessage()
    return when (code) {
        401 -> "$prefix: please sign in again."
        403 -> serverMessage?.let { "$prefix: $it" } ?: "$prefix: your account cannot perform this action."
        404 -> "$prefix: backend route was not found."
        413 -> "$prefix: this chunk is too large to upload."
        429 -> "$prefix: too many requests. Please wait a moment and try again."
        in 500..599 -> "$prefix: backend service is unavailable right now."
        else -> serverMessage?.let { "$prefix: $it" } ?: "$prefix: HTTP $code."
    }
}

private fun String.extractServerMessage(): String? {
    if (isBlank()) return null
    return runCatching {
        val json = JSONObject(this)
        json.optString("message")
            .ifBlank { json.optString("error") }
            .ifBlank { null }
    }.getOrNull() ?: take(240).ifBlank { null }
}

private fun Throwable.toBackendUserMessage(action: String): String {
    val rawMessage = message.orEmpty()
    return when (this) {
        is SocketTimeoutException -> "The backend took too long to $action. Check the backend/database connection and try again."
        is UnknownHostException -> "Cannot find the backend host. Check the backend URL."
        is ConnectException -> "Cannot reach the backend. Make sure your phone and backend are on the same network."
        is IOException -> "Network connection failed while trying to $action. Check Wi-Fi and backend status."
        else -> rawMessage.ifBlank { javaClass.simpleName }.take(500)
    }
}
