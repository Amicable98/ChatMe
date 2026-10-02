package com.example.chatme.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Represents a discovered peer on the local Wi-Fi (LAN) network.
 */
data class Peer(
    val deviceName: String,
    val ipAddress: String,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

/**
 * Represents a single plain-text chat message exchanged over TCP.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean
) {
    fun formattedTime(): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }
}

/**
 * Connection states for the 1:1 TCP chat session.
 */
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data class Connecting(val peer: Peer) : ConnectionState()
    data class Connected(val peer: Peer) : ConnectionState()
    data class Disconnected(val peerName: String?, val reason: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Top-level UI state exposed by ChatMeViewModel via StateFlow.
 */
data class ChatMeUiState(
    val ownDeviceName: String = "",
    val ownIpAddress: String = "0.0.0.0",
    val isWifiConnected: Boolean = false,
    val discoveredPeers: List<Peer> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Idle,
    val activePeer: Peer? = null,
    val messages: List<ChatMessage> = emptyList(),
    val statusBannerMessage: String? = null,
    val isServerRunning: Boolean = false,
    val isDiscoveryActive: Boolean = false
)
