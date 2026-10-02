package com.example.chatme.network

import com.example.chatme.model.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Manages an outgoing or accepted 1:1 TCP chat connection on port 8888.
 *
 * How it works:
 * - Connects a TCP [Socket] to the selected peer's IP address on port [TcpChatServer.CHAT_PORT] (8888).
 * - Performs a lightweight plain-text handshake so both peers know each other's display name.
 * - Reads incoming plain-text lines on a background coroutine (Dispatchers.IO).
 * - Sends outgoing plain-text messages over the active TCP stream.
 */
class TcpChatClient(
    private val scope: CoroutineScope,
    private val ownDeviceNameProvider: () -> String,
    private val onConnected: (Peer) -> Unit,
    private val onMessageReceived: (senderName: String, text: String) -> Unit,
    private val onDisconnected: (Peer, reason: String) -> Unit,
    private val onConnectionError: (Peer, errorMessage: String) -> Unit
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    private var activeSocket: Socket? = null
    private var activeWriter: BufferedWriter? = null
    private var readJob: Job? = null
    private var currentPeer: Peer? = null

    /**
     * Opens a TCP client connection to [peer] on port 8888.
     */
    fun connectToPeer(peer: Peer) {
        disconnect()
        currentPeer = peer

        readJob = scope.launch(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(
                    InetSocketAddress(peer.ipAddress, TcpChatServer.CHAT_PORT),
                    CONNECT_TIMEOUT_MS
                )
                activeSocket = socket

                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                )
                val writer = BufferedWriter(
                    OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                )
                activeWriter = writer

                // Send our device name handshake
                writer.write("${TcpChatServer.HANDSHAKE_PREFIX}${ownDeviceNameProvider()}\n")
                writer.flush()

                // Read server response handshake or busy status
                val handshakeReply = reader.readLine()
                if (handshakeReply == null) {
                    onConnectionError(peer, "Peer closed the connection unexpectedly.")
                    disconnect()
                    return@launch
                }

                if (handshakeReply.startsWith(TcpChatServer.BUSY_PREFIX)) {
                    val busyPeerName = handshakeReply
                        .removePrefix(TcpChatServer.BUSY_PREFIX)
                        .ifEmpty { peer.deviceName }
                    onConnectionError(
                        peer,
                        "$busyPeerName is currently in another 1:1 chat. Please try again shortly."
                    )
                    disconnect()
                    return@launch
                }

                val resolvedName = if (handshakeReply.startsWith(TcpChatServer.HANDSHAKE_PREFIX)) {
                    handshakeReply.removePrefix(TcpChatServer.HANDSHAKE_PREFIX).trim()
                        .ifEmpty { peer.deviceName }
                } else {
                    peer.deviceName
                }

                val connectedPeer = peer.copy(deviceName = resolvedName)
                currentPeer = connectedPeer
                onConnected(connectedPeer)

                // Read incoming plain-text messages until the socket closes
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val text = if (line.startsWith(TcpChatServer.MSG_PREFIX)) {
                        line.removePrefix(TcpChatServer.MSG_PREFIX)
                    } else {
                        line
                    }
                    if (text.isNotEmpty()) {
                        onMessageReceived(connectedPeer.deviceName, text)
                    }
                }

                onDisconnected(connectedPeer, "${connectedPeer.deviceName} disconnected.")
            } catch (e: Exception) {
                if (activeSocket != null && activeSocket?.isClosed == true) {
                    onDisconnected(peer, "Chat session ended.")
                } else {
                    onConnectionError(
                        peer,
                        "Failed to connect to ${peer.deviceName} (${peer.ipAddress}:${TcpChatServer.CHAT_PORT}). Check that both devices are on the same Wi-Fi."
                    )
                }
            } finally {
                cleanupSocket()
            }
        }
    }

    /**
     * Attaches an already-accepted incoming socket from [TcpChatServer] so this client
     * can send outgoing messages over the exact same 1:1 TCP connection.
     */
    fun attachIncomingSession(peer: Peer, writer: BufferedWriter, socket: Socket) {
        cleanupSocket()
        currentPeer = peer
        activeSocket = socket
        activeWriter = writer
    }

    /**
     * Sends a plain-text chat message over the active TCP connection on Dispatchers.IO.
     */
    suspend fun sendMessage(plainText: String): Boolean = withContext(Dispatchers.IO) {
        val writer = activeWriter ?: return@withContext false
        val socket = activeSocket ?: return@withContext false
        if (socket.isClosed || !socket.isConnected) return@withContext false

        return@withContext try {
            val sanitized = plainText.replace("\n", " ").trim()
            writer.write("${TcpChatServer.MSG_PREFIX}$sanitized\n")
            writer.flush()
            true
        } catch (_: Exception) {
            currentPeer?.let { peer ->
                onDisconnected(peer, "Lost connection to ${peer.deviceName}.")
            }
            cleanupSocket()
            false
        }
    }

    /**
     * Closes the active TCP client connection.
     */
    fun disconnect() {
        readJob?.cancel()
        readJob = null
        cleanupSocket()
    }

    private fun cleanupSocket() {
        runCatching { activeWriter?.close() }
        runCatching { activeSocket?.close() }
        activeWriter = null
        activeSocket = null
    }
}
