package com.example.chatme.network

import com.example.chatme.model.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/**
 * Runs a persistent TCP ServerSocket listening on port [CHAT_PORT] (8888).
 *
 * How it works:
 * 1. Continuously accepts incoming TCP connections on a background coroutine (Dispatchers.IO).
 * 2. When a peer connects, reads an initial handshake line (CHATME_HANDSHAKE|<peerName>).
 * 3. Supports accepting multiple incoming connections on the server without blocking the accept loop:
 *    - If this device is not currently in an active 1:1 chat, the server promotes this socket
 *      as the active 1:1 chat session and notifies the ViewModel.
 *    - If this device is already busy in another 1:1 chat session, the server sends a polite
 *      CHATME_BUSY|<ownName> line and notifies the UI that another peer tried to connect.
 */
class TcpChatServer(
    private val scope: CoroutineScope,
    private val ownDeviceNameProvider: () -> String,
    private val isBusyInAnotherChat: (incomingIp: String) -> Boolean,
    private val onIncomingChatAccepted: (peer: Peer, writer: BufferedWriter, socket: Socket) -> Unit,
    private val onBusyConnectionAttempt: (peerName: String, peerIp: String) -> Unit,
    private val onMessageReceived: (senderName: String, text: String) -> Unit,
    private val onPeerDisconnected: (peer: Peer) -> Unit
) {
    companion object {
        const val CHAT_PORT = 8888
        const val HANDSHAKE_PREFIX = "CHATME_HANDSHAKE|"
        const val BUSY_PREFIX = "CHATME_BUSY|"
        const val MSG_PREFIX = "MSG|"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var activeSessionSocket: Socket? = null

    /**
     * Starts listening on TCP port 8888 for incoming peer connections.
     */
    fun startServer() {
        if (acceptJob?.isActive == true) return

        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(CHAT_PORT))
                }
                serverSocket = server

                while (isActive && !server.isClosed) {
                    // Accept incoming peer connection (can accept multiple connections concurrently)
                    val clientSocket = server.accept()
                    launch(Dispatchers.IO) {
                        handleIncomingClient(clientSocket)
                    }
                }
            } catch (_: SocketException) {
                // Server socket closed normally
            } catch (_: Exception) {
                // Ignore server startup/accept error
            }
        }
    }

    private fun handleIncomingClient(socket: Socket) {
        val remoteIp = socket.inetAddress?.hostAddress ?: "Unknown IP"
        try {
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            )
            val writer = BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            )

            // Read initial handshake line to identify peer name
            val firstLine = reader.readLine() ?: run {
                socket.close()
                return
            }

            val peerName = if (firstLine.startsWith(HANDSHAKE_PREFIX)) {
                firstLine.removePrefix(HANDSHAKE_PREFIX).trim().ifEmpty { remoteIp }
            } else {
                remoteIp
            }

            // Check if user is already in a 1:1 chat with a different peer
            if (isBusyInAnotherChat(remoteIp)) {
                writer.write("$BUSY_PREFIX${ownDeviceNameProvider()}\n")
                writer.flush()
                onBusyConnectionAttempt(peerName, remoteIp)
                socket.close()
                return
            }

            // Acknowledge handshake with our own device name
            writer.write("$HANDSHAKE_PREFIX${ownDeviceNameProvider()}\n")
            writer.flush()

            val peer = Peer(deviceName = peerName, ipAddress = remoteIp)
            activeSessionSocket = socket
            onIncomingChatAccepted(peer, writer, socket)

            // If the first line was already a plain text message instead of a handshake, deliver it
            if (!firstLine.startsWith(HANDSHAKE_PREFIX)) {
                val cleanText = firstLine.removePrefix(MSG_PREFIX)
                onMessageReceived(peerName, cleanText)
            }

            // Continuously read plain-text lines from the connected peer
            while (!socket.isClosed) {
                val rawLine = reader.readLine() ?: break
                val messageText = if (rawLine.startsWith(MSG_PREFIX)) {
                    rawLine.removePrefix(MSG_PREFIX)
                } else {
                    rawLine
                }
                if (messageText.isNotEmpty()) {
                    onMessageReceived(peer.deviceName, messageText)
                }
            }
        } catch (_: Exception) {
            // Connection dropped or closed
        } finally {
            val peer = Peer(deviceName = remoteIp, ipAddress = remoteIp)
            if (activeSessionSocket === socket) {
                activeSessionSocket = null
                onPeerDisconnected(peer)
            }
            runCatching { socket.close() }
        }
    }

    fun closeActiveSession() {
        runCatching { activeSessionSocket?.close() }
        activeSessionSocket = null
    }

    fun stopServer() {
        acceptJob?.cancel()
        closeActiveSession()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
