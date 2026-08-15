package com.halalify.kotlin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DnsPacketTest {
    @Test
    fun `reads a question and creates an NXDOMAIN response`() {
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x03, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
            0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0x00,
            0x00, 0x01, 0x00, 0x01,
        )

        assertEquals("www.example", DnsPacket.queryName(query))
        val response = DnsPacket.blockedResponse(query)
        assertNotNull(response)
        assertEquals(0x80, response!![2].toInt() and 0x80)
        assertEquals(0x03, response[3].toInt() and 0x0f)
    }

    @Test
    fun `cache respects ttl and restores the request transaction id`() {
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x03, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
            0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0x00,
            0x00, 0x01, 0x00, 0x01,
        )
        val response = query.copyOf() + byteArrayOf(
            0xc0.toByte(), 0x0c, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3c, 0x00, 0x04,
            0x5d, 0xb8.toByte(), 0xd8.toByte(), 0x22,
        )
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        response[6] = 0x00
        response[7] = 0x01

        var now = 1_000L
        val cache = DnsResponseCache(clock = { now })
        cache.put(query, response)

        val retry = query.copyOf().also {
            it[0] = 0x56
            it[1] = 0x78
        }
        val cached = cache.get(retry)
        assertNotNull(cached)
        assertArrayEquals(byteArrayOf(0x56, 0x78), cached!!.copyOfRange(0, 2))

        now += 60_000L
        assertNull(cache.get(retry))
    }
}
