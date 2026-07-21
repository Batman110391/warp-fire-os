package com.batman110391.warpfiretv

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.batman110391.warpfiretv.vpn.WireGuardTunnel
import com.batman110391.warpfiretv.warp.WarpApi
import com.batman110391.warpfiretv.warp.WarpConfig
import com.batman110391.warpfiretv.warp.WarpConfigStore
import com.batman110391.warpfiretv.warp.WarpRegistration
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-screen, D-pad-only UI: one status line, one detail line, one big button.
 */
class MainActivity : ComponentActivity() {

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var actionButton: Button

    private lateinit var store: WarpConfigStore
    private lateinit var registration: WarpRegistration
    private lateinit var tunnel: WireGuardTunnel
    private val api = WarpApi()

    private var config: WarpConfig? = null
    private var busy = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                connect()
            } else {
                busy = false
                showState(UiState.ERROR, getString(R.string.error_vpn_permission))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleView = findViewById(R.id.title)
        statusView = findViewById(R.id.status)
        detailView = findViewById(R.id.detail)
        actionButton = findViewById(R.id.action_button)

        store = WarpConfigStore(this)
        registration = WarpRegistration(store)
        tunnel = WireGuardTunnel.getInstance(this)

        actionButton.setOnClickListener { onActionPressed() }
        actionButton.requestFocus()

        // Hidden reset: long-press the title to wipe the registration and get a new WARP device.
        titleView.setOnLongClickListener {
            resetRegistration()
            true
        }

        lifecycleScope.launch {
            tunnel.state.collect { state -> onTunnelStateChanged(state) }
        }

        requestNotificationPermissionIfNeeded()
        ensureRegistration()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { tunnel.refreshState() }
    }

    private fun ensureRegistration() {
        busy = true
        showState(UiState.REGISTERING)
        lifecycleScope.launch {
            runCatching { registration.ensureRegistered() }
                .onSuccess {
                    config = it
                    busy = false
                    if (tunnel.state.value != Tunnel.State.UP) showState(UiState.DISCONNECTED)
                }
                .onFailure {
                    busy = false
                    showState(UiState.ERROR, getString(R.string.error_registration))
                }
        }
    }

    private fun resetRegistration() {
        if (busy) return
        lifecycleScope.launch {
            if (tunnel.state.value == Tunnel.State.UP) {
                runCatching { tunnel.down() }
            }
            store.clear()
            config = null
            ensureRegistration()
        }
    }

    private fun onActionPressed() {
        if (busy) return
        when {
            tunnel.state.value == Tunnel.State.UP -> disconnect()
            config == null -> ensureRegistration()
            else -> prepareVpnThenConnect()
        }
    }

    private fun prepareVpnThenConnect() {
        busy = true
        showState(UiState.CONNECTING)
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) {
            // Reachable with the remote: it is a normal system dialog, focusable by D-pad.
            vpnPermissionLauncher.launch(intent)
        } else {
            connect()
        }
    }

    private fun connect() {
        val current = config ?: run {
            busy = false
            ensureRegistration()
            return
        }
        showState(UiState.CONNECTING)
        lifecycleScope.launch {
            runCatching { tunnel.up(current) }
                .onSuccess {
                    busy = false
                    checkWarpStatus()
                }
                .onFailure {
                    busy = false
                    showState(UiState.ERROR, getString(R.string.error_connect))
                }
        }
    }

    private fun disconnect() {
        busy = true
        lifecycleScope.launch {
            runCatching { tunnel.down() }
            busy = false
            showState(UiState.DISCONNECTED)
        }
    }

    private fun checkWarpStatus(allowEnableRetry: Boolean = true) {
        val current = config ?: return
        lifecycleScope.launch {
            val trace = withContext(Dispatchers.IO) { api.fetchTrace() }
            if (trace == null) {
                showState(UiState.CONNECTED, getString(R.string.detail_no_trace))
                return@launch
            }

            val warp = trace["warp"].orEmpty()
            val egressIp = trace["ip"].orEmpty()

            // warp=off with a working tunnel means the device was never enrolled: retry the PATCH
            // once, then re-check.
            if (warp == "off" && allowEnableRetry && registration.enableWarp(current)) {
                checkWarpStatus(allowEnableRetry = false)
                return@launch
            }

            showState(UiState.CONNECTED, getString(R.string.detail_connected, egressIp, warp))
        }
    }

    private fun onTunnelStateChanged(state: Tunnel.State) {
        if (busy) return
        when (state) {
            Tunnel.State.UP -> if (statusView.tag != UiState.CONNECTED) checkWarpStatus()
            else -> if (statusView.tag == UiState.CONNECTED) showState(UiState.DISCONNECTED)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showState(state: UiState, detail: String? = null) {
        statusView.tag = state
        statusView.setText(state.labelRes)
        actionButton.setText(if (state == UiState.CONNECTED) R.string.action_disconnect else R.string.action_connect)
        actionButton.isEnabled = state != UiState.REGISTERING && state != UiState.CONNECTING
        detailView.text = detail.orEmpty()
        detailView.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private enum class UiState(val labelRes: Int) {
        DISCONNECTED(R.string.status_disconnected),
        REGISTERING(R.string.status_registering),
        CONNECTING(R.string.status_connecting),
        CONNECTED(R.string.status_connected),
        ERROR(R.string.status_error),
    }
}
