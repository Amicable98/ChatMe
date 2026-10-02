package com.example.chatme.network

import android.content.Context
import android.net.wifi.WifiManager
import com.example.chatme.model.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles local LAN peer discovery using UDP broadcast packets on port [DISCOVERY_PORT].
 *
 * Discovery Protocol:
 * - Periodically broadcasts a small packet formatted as: "CHATME_HELLO|<deviceName>|<ipAddress>"
 * - Listens on [DISCOVERY_PORT] for packets from other devices on the same Wi-Fi network.
 * - Filters out packets originating from this device's own IP address.
 * - Automatically prunes peers that haven't announced presence within [PEER_TIMEOUT_MS].
 */
class UdpDiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceNameProvider: () -> String,
    private val localIpProvider: () -> String?
) {
    companion object {
        const val DISCOVERY_PORT = 8889
        private const val PACKET_PREFIX = "CHATME_HELLO"
        private const val BROADCAST_INTERVAL_MS = 2500L
        private const val PEER_TIMEOUT_MS = 10000L
    }

    private val peersMap = ConcurrentHashMap<String, Peer>()
    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    private var listenSocket: DatagramSocket? = null
    private var listenerJob: Job? = null
    private var broadcasterJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Starts both the UDP broadcast listener and the periodic UDP presence broadcaster
     * on background IO coroutines.
     */
    fun startDiscovery() {
        if (listenerJob?.isActive == true) return

        multicastLock = NetworkUtils.acquireMulticastLock(context)

        // 1. Background coroutine that listens for incoming UDP "CHATME_HELLO" packets
        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                listenSocket = socket

                val buffer = ByteArray(1024)
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet) // Blocking receive on Dispatchers.IO

                    val payload = String(
                        packet.data,
                        packet.offset,
                        packet.length,
                        StandardCharsets.UTF_8
                    ).trim()

                    val senderIp = packet.address?.hostAddress ?: continue
                    parseAndRegisterPeer(payload, senderIp)
                }
            } catch (_: SocketException) {
                // Socket closed cleanly when stopping discovery
            } catch (_: Exception) {
                // Ignore transient UDP receive errors
            }
        }

        // 2. Background coroutine that periodically broadcasts own presence and prunes stale peers
        broadcasterJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                broadcastPresenceOnce()
                pruneStalePeers()
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    /**
     * Immediately sends a single UDP broadcast announcement so peers see name changes right away.
     */
    fun broadcastPresenceNow() {
        scope.launch(Dispatchers.IO) {
            broadcastPresenceOnce()
        }
    }

    private fun broadcastPresenceOnce() {
        val ownIp = localIpProvider() ?: return
        if (ownIp == "0.0.0.0") return

        val sanitizedName = deviceNameProvider().replace("|", "-").trim()
        val message = "$PACKET_PREFIX|$sanitizedName|$ownIp"
        val bytes = message.toByteArray(StandardCharsets.UTF_8)

        try {
            DatagramSocket().use { sendSocket ->
                sendSocket.broadcast = true
                for (broadcastAddress in NetworkUtils.getBroadcastAddresses()) {
                    val packet = DatagramPacket(
                        bytes,
                        bytes.size,
                        broadcastAddress,
                        DISCOVERY_PORT
                    )
                    runCatching { sendSocket.send(packet) }
                }
            }
        } catch (_: Exception) {
            // Ignore broadcast error if Wi-Fi is temporarily unavailable
        }
    }

    /**
     * Parses a received UDP packet ("CHATME_HELLO|<deviceName>|<ipAddress>") and updates the peer list.
     */
    private fun parseAndRegisterPeer(payload: String, packetSenderIp: String) {
        if (!payload.startsWith("$PACKET_PREFIX|")) return
        val parts = payload.split("|")
        if (parts.size < 2) return

        val peerName = parts[1].trim().ifEmpty { "Unknown Peer" }
        val reportedIp = parts.getOrNull(2)?.trim().takeUnless { it.isNullOrEmpty() } ?: packetSenderIp

        val ownIp = localIpProvider()
        // Ignore our own broadcast packets
        if (reportedIp == ownIp || packetSenderIp == ownIp) return

        val peer = Peer(
            deviceName = peerName,
            ipAddress = reportedIp,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        peersMap[reportedIp] = peer
        emitPeerList()
    }

    private fun pruneStalePeers() {
        val now = System.currentTimeMillis()
        var changed = false
        val iterator = peersMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeenTimestamp > PEER_TIMEOUT_MS) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            emitPeerList()
        }
    }

    private fun emitPeerList() {
        _discoveredPeers.value = peersMap.values.sortedBy { it.deviceName.lowercase() }
    }

    /**
     * Stops UDP discovery, closes the DatagramSocket, and releases the Wi-Fi MulticastLock.
     */
    fun stopDiscovery() {
        broadcasterJob?.cancel()
        listenerJob?.cancel()
        runCatching { listenSocket?.close() }
        listenSocket = null
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
        multicastLock = null
    }
}
