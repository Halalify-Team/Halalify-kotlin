package com.halalify.kotlin.network

import java.nio.charset.StandardCharsets

internal object DnsPacket {
    private const val HEADER_SIZE = 12
    private const val QUESTION_ENTRY_SIZE = 4
    private const val RESOURCE_RECORD_FIXED_SIZE = 10

    fun queryName(packet: ByteArray): String? {
        val end = questionEnd(packet) ?: return null
        var position = HEADER_SIZE
        val labels = mutableListOf<String>()
        while (position < end) {
            val length = packet[position].toInt() and 0xff
            position += 1
            if (length == 0) break
            if (length > 63 || position + length > end) return null
            labels += String(packet, position, length, StandardCharsets.US_ASCII)
            position += length
        }
        return labels.joinToString(".").takeIf { it.isNotBlank() }
    }

    fun blockedResponse(packet: ByteArray): ByteArray? {
        val end = questionEnd(packet) ?: return null
        val response = packet.copyOf(end)
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xf0) or 0x03).toByte() // NXDOMAIN
        writeU16(response, 6, 0)
        writeU16(response, 8, 0)
        writeU16(response, 10, 0)
        return response
    }

    fun cacheKey(packet: ByteArray): ByteArray? {
        if (packet.size < HEADER_SIZE || questionEnd(packet) == null) return null
        // The transaction ID changes on every DNS retry; all remaining query
        // fields identify the request that can safely share a cached response.
        return packet.copyOfRange(2, packet.size)
    }

    fun hasTransactionId(query: ByteArray, response: ByteArray, responseLength: Int): Boolean =
        query.size >= 2 && responseLength >= 2 &&
            query[0] == response[0] && query[1] == response[1]

    fun withTransactionId(response: ByteArray, query: ByteArray): ByteArray? {
        if (response.size < 2 || query.size < 2) return null
        return response.copyOf().also {
            it[0] = query[0]
            it[1] = query[1]
        }
    }

    fun cacheTtlMillis(response: ByteArray): Long? {
        if (response.size < HEADER_SIZE) return null
        val flags = readU16(response, 2)
        if ((flags and 0x8000) == 0) return null // Not a response.

        val questionCount = readU16(response, 4)
        val answerCount = readU16(response, 6)
        val authorityCount = readU16(response, 8)
        var position = HEADER_SIZE
        repeat(questionCount) {
            position = skipName(response, position) ?: return null
            if (position + QUESTION_ENTRY_SIZE > response.size) return null
            position += QUESTION_ENTRY_SIZE
        }

        var minimumTtl = Int.MAX_VALUE
        repeat(answerCount + authorityCount) {
            position = skipName(response, position) ?: return null
            if (position + RESOURCE_RECORD_FIXED_SIZE > response.size) return null
            position += 4 // TYPE + CLASS
            val ttl = readU32(response, position)
            position += 4
            val dataLength = readU16(response, position)
            position += 2
            if (position + dataLength > response.size) return null
            position += dataLength
            minimumTtl = minOf(minimumTtl, ttl)
        }

        val responseCode = flags and 0x000f
        val ttlSeconds = when {
            responseCode != 0 && responseCode != 3 -> return null
            minimumTtl != Int.MAX_VALUE -> minimumTtl
            responseCode == 3 -> NEGATIVE_CACHE_TTL_SECONDS
            else -> NO_DATA_CACHE_TTL_SECONDS
        }
        if (ttlSeconds <= 0) return null
        return ttlSeconds.coerceAtMost(MAX_CACHE_TTL_SECONDS).toLong() * 1_000L
    }

    private fun skipName(packet: ByteArray, start: Int): Int? {
        var position = start
        while (position < packet.size) {
            val length = packet[position].toInt() and 0xff
            position += 1
            when {
                length == 0 -> return position
                length and 0xc0 == 0xc0 ->
                    return if (position < packet.size) position + 1 else null
                length > 63 || position + length > packet.size -> return null
                else -> position += length
            }
        }
        return null
    }

    private fun readU32(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 24) or
            ((packet[offset + 1].toInt() and 0xff) shl 16) or
            ((packet[offset + 2].toInt() and 0xff) shl 8) or
            (packet[offset + 3].toInt() and 0xff)

    private fun questionEnd(packet: ByteArray): Int? {
        if (packet.size < HEADER_SIZE) return null
        val questionCount = readU16(packet, 4)
        if (questionCount <= 0) return null

        var position = HEADER_SIZE
        repeat(questionCount) {
            while (true) {
                if (position >= packet.size) return null
                val length = packet[position].toInt() and 0xff
                position += 1
                if (length == 0) break
                // Compression pointers are not expected in a DNS question name.
                if (length > 63 || position + length > packet.size) return null
                position += length
            }
            if (position + 4 > packet.size) return null
            position += 4
        }
        return position
    }

    private fun readU16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

    private fun writeU16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private const val NEGATIVE_CACHE_TTL_SECONDS = 10
    private const val NO_DATA_CACHE_TTL_SECONDS = 5
    private const val MAX_CACHE_TTL_SECONDS = 300
}
