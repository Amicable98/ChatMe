package com.example.chatme

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.chatme.model.ConnectionState
import com.example.chatme.ui.ChatScreen
import com.example.chatme.ui.PeerListScreen
import com.example.chatme.ui.theme.ChatMeTheme
import com.example.chatme.viewmodel.ChatMeViewModel

/**
 * MainActivity for ChatMe:
 * - Requests required runtime permissions for local Wi-Fi discovery (NEARBY_WIFI_DEVICES on API 33+,
 *   or ACCESS_FINE_LOCATION on API <= 32).
 * - Hosts Jetpack Compose navigation between [PeerListScreen] and [ChatScreen].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ChatMeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ChatMeTheme {
                RequestLanPermissionsIfNeeded(
                    onPermissionsResult = {
                        viewModel.refreshNetworkStatus()
                    }
                )

                val uiState by viewModel.uiState.collectAsState()
                val activePeer = uiState.activePeer

                // Navigate between PeerListScreen and 1:1 ChatScreen
                if (activePeer != null && uiState.connectionState !is ConnectionState.Idle) {
                    BackHandler {
                        viewModel.leaveCurrentChat()
                    }
                    ChatScreen(
                        peer = activePeer,
                        connectionState = uiState.connectionState,
                        messages = uiState.messages,
                        onSendMessage = { text -> viewModel.sendMessage(text) },
                        onRetryConnect = { viewModel.startChatWithPeer(activePeer) },
                        onBackToPeers = { viewModel.leaveCurrentChat() }
                    )
                } else {
                    PeerListScreen(
                        uiState = uiState,
                        onUpdateDeviceName = { name -> viewModel.updateDeviceName(name) },
                        onStartChat = { peer -> viewModel.startChatWithPeer(peer) },
                        onRefreshNetwork = { viewModel.refreshNetworkStatus() },
                        onDismissBanner = { viewModel.dismissBanner() }
                    )
                }
            }
        }
    }

    @Composable
    private fun RequestLanPermissionsIfNeeded(onPermissionsResult: () -> Unit) {
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) {
            onPermissionsResult()
        }

        LaunchedEffect(Unit) {
            val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }

            val missing = requiredPermissions.filter { perm ->
                ContextCompat.checkSelfPermission(this@MainActivity, perm) !=
                        PackageManager.PERMISSION_GRANTED
            }

            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
            } else {
                onPermissionsResult()
            }
        }
    }
}
