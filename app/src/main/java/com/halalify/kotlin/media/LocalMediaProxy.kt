package com.halalify.kotlin.media

import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

internal class LocalMediaProxy(
    private val media: DirectMediaResource,
) : Closeable {
    private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val url: String = "http://127.0.0.1:${serverSocket.localPort}/media"

    @Volatile
    private var closed = false

    /**
     * Set true if any proxied upstream request returned 403. The downloader
     * inspects this after FFmpeg finishes so it can trigger one lazy refresh
     * of the signed YouTube URL instead of failing the whole pipeline.
     */
    @Volatile
    var forbidden: Boolean = false
        private set

    private val acceptThread = Thread({
        while (!closed) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
            clientSockets += socket
            Thread({
                try {
                    handle(socket)
                } catch (error: Throwable) {
                    val expectedDisconnect = error.message.equals("Connection reset", true) ||
                        error.message.equals("Socket closed", true) ||
                        error.message.equals("Broken pipe", true)
                    if (expectedDisconnect) {
                        Log.d("HalalifyRange", "FFmpeg completed its requested media range.")
                    } else {
                        Log.w("HalalifyRange", "proxy request failed: ${error.message}")
                    }
                } finally {
                    clientSockets -= socket
                    runCatching { socket.close() }
                }
            }, "halalify-media-proxy-client").apply {
                isDaemon = true
                start()
            }
        }
    }, "halalify-media-proxy").apply {
        isDaemon = true
        start()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)
        )
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ').uppercase()
        val requestHeaders = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                requestHeaders[line.substring(0, separator).trim()] =
                    line.substring(separator + 1).trim()
            }
        }

        val range = requestHeaders.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value
        Log.i("HalalifyRange", "format=${media.formatId} method=$method range=${range ?: "none"}")

        val upstreamBuilder = Request.Builder().url(media.url)
        Log.i("HalalifyRange", "Media URL: ${media.url}")
        Log.i("HalalifyRange", "Original media.headers: ${media.headers}")
        media.headers.forEach { (name, value) ->
            if (!name.equals("Host", ignoreCase = true) &&
                !name.equals("Range", ignoreCase = true)
            ) {
                upstreamBuilder.header(name, value)
            }
        }
        if (!range.isNullOrBlank()) {
            upstreamBuilder.header("Range", range)
        }
        if (method == "HEAD") {
            upstreamBuilder.head()
        } else {
            upstreamBuilder.get()
        }

        var transferredBytes = 0L
        var upstreamStatus = 0
        try {
            client.newCall(upstreamBuilder.build()).execute().use { response ->
                upstreamStatus = response.code
                val output = socket.getOutputStream()
                val reason = response.message.ifBlank {
                    if (response.isSuccessful) "OK" else "Error"
                }
                
                if (upstreamStatus == 403) {
                    forbidden = true
                    val bodyString = response.peekBody(1024).string()
                    Log.e("HalalifyRange", "403 Forbidden! Response body snippet: $bodyString")
                    Log.e("HalalifyRange", "403 Forbidden! Response headers: ${response.headers}")
                }
                
                output.write("HTTP/1.1 ${response.code} $reason\r\n".toByteArray())
                listOf(
                    "Content-Type",
                    "Content-Length",
                    "Content-Range",
                    "Accept-Ranges",
                    "Last-Modified",
                    "ETag",
                ).forEach { name ->
                    response.header(name)?.let { value ->
                        output.write("$name: $value\r\n".toByteArray())
                    }
                }
                output.write("Connection: close\r\n\r\n".toByteArray())
                if (method != "HEAD") {
                    response.body?.byteStream()?.use { input ->
                        val countingOutput = object : FilterOutputStream(output) {
                            override fun write(value: Int) {
                                out.write(value)
                                transferredBytes += 1
                            }

                            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                out.write(buffer, offset, length)
                                transferredBytes += length
                            }
                        }
                        input.copyTo(countingOutput)
                    }
                }
                output.flush()
            }
        } finally {
            Log.i(
                "HalalifyRange",
                "format=${media.formatId} range=${range ?: "none"} " +
                    "status=$upstreamStatus transferred=$transferredBytes"
            )
        }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        clientSockets.forEach { socket -> runCatching { socket.close() } }
        runCatching { acceptThread.join(1_000) }
    }
}
