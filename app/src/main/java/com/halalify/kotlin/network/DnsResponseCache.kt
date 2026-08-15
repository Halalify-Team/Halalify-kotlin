package com.halalify.kotlin.network

import java.util.LinkedHashMap

/** Small in-memory DNS cache to avoid repeating the same upstream lookup. */
internal class DnsResponseCache(
    private val maxEntries: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val response: ByteArray,
        val expiresAt: Long,
    )

    private class CacheKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is CacheKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val entries = LinkedHashMap<CacheKey, Entry>(maxEntries, 0.75f, true)

    @Synchronized
    fun get(query: ByteArray): ByteArray? {
        val key = DnsPacket.cacheKey(query)?.let(::CacheKey) ?: return null
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= clock()) {
            entries.remove(key)
            return null
        }
        return DnsPacket.withTransactionId(entry.response, query)
    }

    @Synchronized
    fun put(query: ByteArray, response: ByteArray) {
        val key = DnsPacket.cacheKey(query)?.let(::CacheKey) ?: return
        val ttlMillis = DnsPacket.cacheTtlMillis(response) ?: return
        entries[key] = Entry(response.copyOf(), clock() + ttlMillis)
        while (entries.size > maxEntries) entries.remove(entries.entries.first().key)
    }
}
