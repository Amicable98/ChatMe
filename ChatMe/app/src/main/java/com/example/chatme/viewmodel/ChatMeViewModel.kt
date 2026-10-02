package com.example.chatme.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatme.model.ChatMeUiState
import com.example.chatme.model.ChatMessage
import com.example.chatme.model.ConnectionState
import com.example.chatme.model.Peer
import com.example.chatme.network.NetworkUtils
import com.example.chatme.network.TcpChatClient
import com.example.chatme.network.TcpChatServer
import com.example.chatme.network.UdpDiscoveryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Main ViewModel managing UI state via [StateFlow] and coordinating background networking:
 * - [UdpDiscoveryManager] for UDP LAN broadcast & peer discovery (port 8889)
 * - [TcpChatServer] for accepting incoming TCP chat connections (port 8888)
 * - [TcpChatClient] for initiating outgoing TCP chat connections and sending messages (port 8888)
 */
class ChatMeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        ChatMeUiState(
            ownDeviceName = "User-${Random.nextInt(1000, 9999)}"
        )
    )
    val uiState: StateFlow<ChatMeUiState> = _uiState.asStateFlow()

    private val udpDiscoveryManager = UdpDiscoveryManager(
        context = application.applicationContext,
        scope = viewModelScope,
        deviceNameProvider = { _uiState.value.ownDeviceName },
        localIpProvider = {
            _uiState.value.ownIpAddress.takeIf { it != "0.0.0.0" && it != "No Wi-Fi" }
        }
    )

    private val tcpChatClient = TcpChatClient(
        scope = viewModelScope,
        ownDeviceNameProvider = { _uiState.value.ownDeviceName },
        onConnected = { connectedPeer ->
            _uiState.update { state ->
                state.copy(
                    activePeer = connectedPeer,
                    connectionState = ConnectionState.Connected(connectedPeer),
                    statusBannerMessage = null
                )
            }
        },
        onMessageReceived = { senderName, text ->
            appendMessage(senderName = senderName, text = text, isFromMe = false)
        },
        onDisconnected = { peer, reason ->
            _uiState.update { state ->
                state.copy(
                    connectionState = ConnectionState.Disconnected(peer.deviceName, reason),
                    statusBannerMessage = reason
                )
            }
        },
        onConnectionError = { _, errorMessage ->
            _uiState.update { state ->
                state.copy(
                    connectionState = ConnectionState.Error(errorMessage),
                    statusBannerMessage = errorMessage
                )
            }
        }
    )

    private val tcpChatServer = TcpChatServer(
        scope = viewModelScope,
        ownDeviceNameProvider = { _uiState.value.ownDeviceName },
        isBusyInAnotherChat = { incomingIp ->
            val currentState = _uiState.value.connectionState
            currentState is ConnectionState.Connected &&
                    currentState.peer.ipAddress != incomingIp
        },
        onIncomingChatAccepted = { peer, writer, socket ->
            tcpChatClient.attachIncomingSession(peer, writer, socket)
            _uiState.update { state ->
                val resetMessages = if (state.activePeer?.ipAddress != peer.ipAddress) {
                    emptyList()
                } else {
                    state.messages
                }
                state.copy(
                    activePeer = peer,
                    connectionState = ConnectionState.Connected(peer),
                    messages = resetMessages,
                    statusBannerMessage = "${peer.deviceName} connected to chat with you."
                )
            }
        },
        onBusyConnectionAttempt = { peerName, peerIp ->
            _uiState.update { state ->
                state.copy(
                    statusBannerMessage = "$peerName ($peerIp) tried to connect while you are in an active chat."
                )
            }
        },
        onMessageReceived = { senderName, text ->
            appendMessage(senderName = senderName, text = text, isFromMe = false)
        },
        onPeerDisconnected = { peer ->
            val currentPeerName = _uiState.value.activePeer?.deviceName ?: peer.deviceName
            _uiState.update { state ->
                state.copy(
                    connectionState = ConnectionState.Disconnected(
                        currentPeerName,
                        "$currentPeerName disconnected from the chat."
                    ),
                    statusBannerMessage = "$currentPeerName disconnected."
                )
            }
        }
    )

    init {
        viewModelScope.launch {
            udpDiscoveryManager.discoveredPeers.collect { peers ->
                _uiState.update { it.copy(discoveredPeers = peers) }
            }
        }
        startNetworkingServices()
    }

    fun startNetworkingServices() {
        refreshNetworkStatus()
        tcpChatServer.startServer()
        udpDiscoveryManager.startDiscovery()

        _uiState.update {
            it.copy(
                isServerRunning = true,
                isDiscoveryActive = true
            )
        }

        viewModelScope.launch {
            while (isActive) {
                refreshNetworkStatus()
                delay(3000L)
            }
        }
    }

    fun refreshNetworkStatus() {
        val context = getApplication<Application>().applicationContext
        val wifiConnected = NetworkUtils.isWifiConnected(context)
        val localIp = NetworkUtils.getLocalIpv4Address()

        if (!wifiConnected || localIp == null) {
            _uiState.update { state ->
                state.copy(
                    isWifiConnected = false,
                    ownIpAddress = "No Wi-Fi",
                    statusBannerMessage = "No Wi-Fi connection detected. Connect to a local Wi-Fi network to discover peers."
                )
            }
        } else {
            _uiState.update { state ->
                val clearedBanner = if (state.statusBannerMessage?.startsWith("No Wi-Fi") == true) {
                    null
                } else {
                    state.statusBannerMessage
                }
                state.copy(
                    isWifiConnected = true,
                    ownIpAddress = localIp,
                    statusBannerMessage = clearedBanner
                )
            }
        }
    }

    fun updateDeviceName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(ownDeviceName = trimmed) }
        udpDiscoveryManager.broadcastPresenceNow()
    }

    fun startChatWithPeer(peer: Peer) {
        if (!_uiState.value.isWifiConnected) {
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Error(
                        "No Wi-Fi connection. Please connect to Wi-Fi before starting a chat."
                    ),
                    statusBannerMessage = "No Wi-Fi connection."
                )
            }
            return
        }

        _uiState.update { state ->
            val keepMessages = state.activePeer?.ipAddress == peer.ipAddress
            state.copy(
                activePeer = peer,
                connectionState = ConnectionState.Connecting(peer),
                messages = if (keepMessages) state.messages else emptyList(),
                statusBannerMessage = null
            )
        }
        tcpChatClient.connectToPeer(peer)
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val state = _uiState.value
        if (state.connectionState !is ConnectionState.Connected) {
            _uiState.update {
                it.copy(statusBannerMessage = "Cannot send message: Not connected to peer.")
            }
            return
        }

        viewModelScope.launch {
            val sent = tcpChatClient.sendMessage(trimmed)
            if (sent) {
                appendMessage(
                    senderName = state.ownDeviceName,
                    text = trimmed,
                    isFromMe = true
                )
            } else {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.Error("Failed to send message. Peer disconnected."),
                        statusBannerMessage = "Message delivery failed. Peer disconnected."
                    )
                }
            }
        }
    }

    fun leaveCurrentChat() {
        tcpChatClient.disconnect()
        tcpChatServer.closeActiveSession()
        _uiState.update {
            it.copy(
                activePeer = null,
                connectionState = ConnectionState.Idle
            )
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    private fun appendMessage(senderName: String, text: String, isFromMe: Boolean) {
        val newMessage = ChatMessage(
            senderName = senderName,
            text = text,
            isFromMe = isFromMe
        )
        _uiState.update { state ->
            state.copy(messages = state.messages + newMessage)
        }
    }

    override fun onCleared() {
        super.onCleared()
        udpDiscoveryManager.stopDiscovery()
        tcpChatClient.disconnect()
        tcpChatServer.stopServer()
    }
}
