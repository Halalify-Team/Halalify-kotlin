package com.halalify.kotlin.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.halalify.kotlin.capture.CaptureSessionStore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * DNS-only local VPN. It intentionally does not install a default route, so
 * ordinary traffic keeps using the device network while DNS queries go through
 * this filter. This is a filtering layer, not a full traffic-forwarding VPN.
 */
internal class AdultSiteVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var siteFilter: NativeSiteFilterEngine? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val dnsCache = DnsResponseCache()
    private val tunnelOutputLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            ACTION_START, null -> startVpn()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        publishMessage("Android revoked the VPN permission. Website blocking is off.")
        stopSelf()
    }

    private fun startVpn() {
        if (running) return
        stopVpn()
        if (VpnService.prepare(this) != null) {
            publishMessage("VPN permission is missing. Approve it before starting protection.")
            stopSelf()
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())

        val filter = try {
            NativeSiteFilterEngine(loadBlocklistBytes())
        } catch (error: Exception) {
            Log.e(TAG, "Could not load the site blocklist.", error)
            publishMessage("Could not load site_blocklist.txt: ${error.message ?: error.javaClass.simpleName}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        siteFilter = filter

        val established = Builder()
            .setSession("Halalify adult-site protection")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 24)
            // Only the private DNS endpoint is routed into the TUN interface.
            .addRoute(DNS_NETWORK, 24)
            .addDnsServer(DNS_ADDRESS)
            .establish()

        if (established == null) {
            Log.e(TAG, "Android did not establish the DNS VPN interface.")
            publishMessage("Android could not establish the DNS VPN interface. Check VPN permission.")
            filter.close()
            siteFilter = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        vpnInterface = established
        running = true
        worker = thread(name = "halalify-dns-vpn", start = true) { runLoop(established) }
    }

    private fun runLoop(interfaceDescriptor: ParcelFileDescriptor) {
        var input: FileInputStream? = null
        var output: FileOutputStream? = null
        val activeSockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
        val resolverExecutor = ThreadPoolExecutor(
            DNS_WORKER_COUNT,
            DNS_WORKER_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(DNS_QUEUE_CAPACITY),
            ThreadPoolExecutor.DiscardPolicy(),
        )
        try {
            input = FileInputStream(interfaceDescriptor.fileDescriptor)
            output = FileOutputStream(interfaceDescriptor.fileDescriptor)
            val tunnelInput = checkNotNull(input)
            val tunnelOutput = checkNotNull(output)
            val filter = checkNotNull(siteFilter) { "Site filter engine was not initialized." }
            val buffer = ByteArray(MAX_PACKET_SIZE)
            while (running) {
                val length = tunnelInput.read(buffer)
                if (length <= 0) continue
                val request = parseDnsRequest(buffer, length) ?: continue
                val domain = DnsPacket.queryName(request.query)
                if (domain != null && filter.isBlocked(domain)) {
                    DnsPacket.blockedResponse(request.query)?.let { response ->
                        writeDnsResponse(tunnelOutput, request, response)
                    }
                    continue
                }

                val cachedResponse = dnsCache.get(request.query)
                if (cachedResponse != null) {
                    writeDnsResponse(tunnelOutput, request, cachedResponse)
                    continue
                }

                resolverExecutor.execute {
                    val response = forwardToFamilyDns(request.query, activeSockets)
                    if (response != null && running) {
                        dnsCache.put(request.query, response)
                        writeDnsResponse(tunnelOutput, request, response)
                    }
                }
            }
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "DNS VPN loop stopped unexpectedly.", error)
                publishMessage("Website blocking stopped: ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            running = false
            resolverExecutor.shutdownNow()
            activeSockets.forEach { socket -> runCatching { socket.close() } }
            runCatching { resolverExecutor.awaitTermination(2, TimeUnit.SECONDS) }
            runCatching { input?.close() }
            runCatching { output?.close() }
            if (vpnInterface === interfaceDescriptor) vpnInterface = null
        }
    }

    private fun writeDnsResponse(
        tunnelOutput: FileOutputStream,
        request: DnsRequest,
        response: ByteArray,
    ) {
        if (!running) return
        val reply = buildIpv4UdpPacket(
            sourceAddress = DNS_ADDRESS_BYTES,
            destinationAddress = request.sourceAddress,
            sourcePort = DNS_PORT,
            destinationPort = request.sourcePort,
            payload = response,
        )
        synchronized(tunnelOutputLock) {
            if (!running) return
            tunnelOutput.write(reply)
            tunnelOutput.flush()
        }
    }

    private fun publishMessage(message: String) {
        CaptureSessionStore.updateState { current -> current.copy(message = message) }
    }

    private fun forwardToFamilyDns(
        query: ByteArray,
        activeSockets: MutableSet<DatagramSocket>,
    ): ByteArray? {
        val socket = DatagramSocket()
        activeSockets += socket
        return try {
            check(protect(socket)) { "Could not protect the DNS upstream socket." }
            FAMILY_DNS_SERVERS.forEach { server ->
                socket.send(DatagramPacket(query, query.size, server.address, server.port))
            }
            val responseBuffer = ByteArray(MAX_DNS_SIZE)
            val deadline = System.currentTimeMillis() + UPSTREAM_TIMEOUT_MS
            var result: ByteArray? = null
            while (result == null) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) break
                socket.soTimeout = remaining
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                val sourceIsExpected = FAMILY_DNS_SERVERS.any { server ->
                    server.address == response.address && server.port == response.port
                }
                if (sourceIsExpected && DnsPacket.hasTransactionId(query, response.data, response.length)) {
                    result = response.data.copyOf(response.length)
                }
            }
            result
        } catch (_: IOException) {
            null
        } finally {
            activeSockets -= socket
            socket.close()
        }
    }

    private fun parseDnsRequest(packet: ByteArray, length: Int): DnsRequest? {
        if (length < IPV4_HEADER_SIZE + UDP_HEADER_SIZE) return null
        if ((packet[0].toInt() ushr 4) != 4) return null
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        if (headerLength < IPV4_HEADER_SIZE || headerLength + UDP_HEADER_SIZE > length) return null
        if ((packet[9].toInt() and 0xff) != UDP_PROTOCOL) return null

        val destination = packet.copyOfRange(16, 20)
        if (!destination.contentEquals(DNS_ADDRESS_BYTES)) return null
        val udpOffset = headerLength
        val destinationPort = readU16(packet, udpOffset + 2)
        if (destinationPort != DNS_PORT) return null
        val udpLength = readU16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_SIZE || udpOffset + udpLength > length) return null

        return DnsRequest(
            sourceAddress = packet.copyOfRange(12, 16),
            sourcePort = readU16(packet, udpOffset),
            query = packet.copyOfRange(udpOffset + UDP_HEADER_SIZE, udpOffset + udpLength),
        )
    }

    private fun buildIpv4UdpPacket(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = UDP_HEADER_SIZE + payload.size
        val packet = ByteArray(IPV4_HEADER_SIZE + udpLength)
        packet[0] = 0x45
        writeU16(packet, 2, packet.size)
        writeU16(packet, 4, 0)
        writeU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = UDP_PROTOCOL.toByte()
        sourceAddress.copyInto(packet, 12)
        destinationAddress.copyInto(packet, 16)
        writeU16(packet, 10, checksum(packet, 0, IPV4_HEADER_SIZE))

        val udpOffset = IPV4_HEADER_SIZE
        writeU16(packet, udpOffset, sourcePort)
        writeU16(packet, udpOffset + 2, destinationPort)
        writeU16(packet, udpOffset + 4, udpLength)
        payload.copyInto(packet, udpOffset + UDP_HEADER_SIZE)
        val udpChecksum = udpChecksum(packet, udpOffset, udpLength, sourceAddress, destinationAddress)
        writeU16(packet, udpOffset + 6, if (udpChecksum == 0) 0xffff else udpChecksum)
        return packet
    }

    private fun udpChecksum(
        packet: ByteArray,
        offset: Int,
        length: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
    ): Int {
        var sum = 0
        sum += word(sourceAddress, 0) + word(sourceAddress, 2)
        sum += word(destinationAddress, 0) + word(destinationAddress, 2)
        sum += UDP_PROTOCOL + length
        packet[offset + 6] = 0
        packet[offset + 7] = 0
        sum += onesComplementSum(packet, offset, length, 0)
        return foldChecksum(sum)
    }

    private fun checksum(packet: ByteArray, offset: Int, length: Int): Int =
        foldChecksum(onesComplementSum(packet, offset, length, 0))

    private fun onesComplementSum(packet: ByteArray, offset: Int, length: Int, initial: Int): Int {
        var sum = initial
        var position = offset
        val end = offset + length
        while (position + 1 < end) {
            sum += word(packet, position)
            position += 2
        }
        if (position < end) sum += (packet[position].toInt() and 0xff) shl 8
        return sum
    }

    private fun foldChecksum(sum: Int): Int {
        var folded = sum
        while ((folded ushr 16) != 0) folded = (folded and 0xffff) + (folded ushr 16)
        return folded.inv() and 0xffff
    }

    private fun word(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

    private fun readU16(packet: ByteArray, offset: Int): Int = word(packet, offset)

    private fun writeU16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun stopVpn() {
        running = false
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        runCatching { siteFilter?.close() }
        siteFilter = null
        worker?.interrupt()
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Website protection", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Halalify website protection")
            .setContentText("Adult-site DNS filtering is active")
            .setOngoing(true)
            .build()
    } else {
        Notification.Builder(this)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Halalify website protection")
            .setContentText("Adult-site DNS filtering is active")
            .setOngoing(true)
            .build()
    }

    private fun loadBlocklistBytes(): ByteArray {
        val overrideFile = File(filesDir, BLOCKLIST_FILE_NAME)
        return if (overrideFile.isFile) {
            overrideFile.readBytes()
        } else {
            assets.open(BUNDLED_BLOCKLIST_ASSET).use { it.readBytes() }
        }
    }

    private data class DnsRequest(
        val sourceAddress: ByteArray,
        val sourcePort: Int,
        val query: ByteArray,
    )

    internal companion object {
        const val TAG = "AdultSiteVpn"
        internal const val ACTION_START = "com.halalify.kotlin.network.START"
        internal const val ACTION_STOP = "com.halalify.kotlin.network.STOP"
        const val CHANNEL_ID = "halalify_website_protection"
        const val NOTIFICATION_ID = 42
        const val VPN_ADDRESS = "10.67.0.2"
        const val DNS_ADDRESS = "10.67.0.1"
        const val DNS_NETWORK = "10.67.0.0"
        val DNS_ADDRESS_BYTES = byteArrayOf(10, 67, 0, 1)
        val FAMILY_DNS_SERVERS = listOf(
            InetSocketAddress("1.1.1.3", 53),
            InetSocketAddress("1.0.0.3", 53),
        )
        const val DNS_PORT = 53
        const val UDP_PROTOCOL = 17
        const val IPV4_HEADER_SIZE = 20
        const val UDP_HEADER_SIZE = 8
        const val MAX_PACKET_SIZE = 32767
        const val MAX_DNS_SIZE = 4096
        const val UPSTREAM_TIMEOUT_MS = 1200
        const val DNS_WORKER_COUNT = 4
        const val DNS_QUEUE_CAPACITY = 64
        const val BLOCKLIST_FILE_NAME = "site_blocklist.txt"
        const val BUNDLED_BLOCKLIST_ASSET = "site_blocklist.txt"
    }
}
