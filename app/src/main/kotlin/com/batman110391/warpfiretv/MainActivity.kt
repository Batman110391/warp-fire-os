package com.batman110391.warpfiretv

import android.Manifest
import android.app.AlertDialog
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
import com.batman110391.warpfiretv.update.AppUpdater
import com.batman110391.warpfiretv.update.AvailableUpdate
import com.batman110391.warpfiretv.vpn.TunnelMode
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
    private lateinit var modeButton: Button

    private lateinit var store: WarpConfigStore
    private lateinit var registration: WarpRegistration
    private lateinit var tunnel: WireGuardTunnel
    private val api = WarpApi()

    private lateinit var updater: AppUpdater

    private var config: WarpConfig? = null
    private var busy = false
    private var mode = TunnelMode.WARP

    /** Ask about a given update at most once per app session. */
    private var pendingUpdate: AvailableUpdate? = null
    private var updatePrompted = false
    private var awaitingInstallPermission = false

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
        modeButton = findViewById(R.id.mode_button)

        store = WarpConfigStore(this)
        registration = WarpRegistration(store)
        tunnel = WireGuardTunnel.getInstance(this)
        updater = AppUpdater(this)

        actionButton.setOnClickListener { onActionPressed() }
        actionButton.requestFocus()

        mode = store.tunnelMode
        renderModeButton()
        modeButton.setOnClickListener { onModePressed() }

        // Hidden reset: long-press the title to wipe the registration and get a new WARP device.
        titleView.setOnLongClickListener {
            resetRegistration()
            true
        }

        lifecycleScope.launch {
            tunnel.state.collect { renderTunnelState() }
        }

        requestNotificationPermissionIfNeeded()
        ensureRegistration()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { tunnel.refreshState() }

        // Coming back from the "install unknown apps" settings screen: resume where we stopped
        // instead of making the user hunt for the prompt again.
        val update = pendingUpdate
        if (awaitingInstallPermission && update != null && updater.canInstall()) {
            awaitingInstallPermission = false
            startUpdate(update)
        }
    }

    private fun ensureRegistration() {
        busy = true
        showState(UiState.REGISTERING)
        lifecycleScope.launch {
            runCatching { registration.ensureRegistered() }
                .onSuccess {
                    config = it
                    busy = false
                    renderTunnelState()
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

    private fun renderModeButton() {
        modeButton.setText(
            when (mode) {
                TunnelMode.WARP -> R.string.mode_warp
                TunnelMode.DNS_ONLY -> R.string.mode_dns_only
            },
        )
    }

    /**
     * Switches routing mode. The mode is baked into the WireGuard config, so an active tunnel has
     * to be rebuilt for the change to take effect.
     */
    private fun onModePressed() {
        if (busy) return
        mode = if (mode == TunnelMode.WARP) TunnelMode.DNS_ONLY else TunnelMode.WARP
        store.tunnelMode = mode
        renderModeButton()

        val current = config
        if (tunnel.state.value == Tunnel.State.UP && current != null) {
            busy = true
            showState(UiState.CONNECTING)
            lifecycleScope.launch {
                val result = runCatching { tunnel.reconnect(current, mode) }
                busy = false
                if (result.isSuccess) {
                    checkWarpStatus()
                } else {
                    showState(UiState.ERROR, getString(R.string.error_connect))
                }
            }
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
            runCatching { tunnel.up(current, mode) }
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

            // In DNS-only mode the traffic deliberately bypasses WARP, so warp=off is the correct
            // outcome and there is nothing to enrol.
            if (mode == TunnelMode.DNS_ONLY) {
                showState(UiState.CONNECTED, getString(R.string.detail_connected_dns, egressIp))
                return@launch
            }

            // warp=off with a working tunnel means the device was never enrolled: retry the PATCH
            // once, then re-check.
            if (warp == "off" && allowEnableRetry && registration.enableWarp(current)) {
                checkWarpStatus(allowEnableRetry = false)
                return@launch
            }

            showState(UiState.CONNECTED, getString(R.string.detail_connected, egressIp, warp))
        }
    }

    /**
     * Single place that decides what the screen shows once no operation is in flight.
     *
     * Called both when the tunnel state changes and when a long-running step finishes, so
     * re-entering the Activity while the tunnel is already up cannot leave a stale label on screen.
     */
    private fun renderTunnelState() {
        if (busy) return
        if (tunnel.state.value == Tunnel.State.UP) {
            // Already showing CONNECTED means the trace was fetched for this session; a second
            // fetch would only cost a round trip.
            if (statusView.tag != UiState.CONNECTED) checkWarpStatus()
        } else {
            showState(UiState.DISCONNECTED)
        }
    }

    // ---------------------------------------------------------------- in-app update

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val update = updater.checkForUpdate() ?: return@launch
            pendingUpdate = update
            if (!updatePrompted) {
                updatePrompted = true
                showUpdateDialog(update)
            }
        }
    }

    private fun showUpdateDialog(update: AvailableUpdate) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_message, update.versionName, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_action) { _, _ -> startUpdate(update) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun startUpdate(update: AvailableUpdate) {
        if (!updater.canInstall()) {
            awaitingInstallPermission = true
            showInstallPermissionDialog()
            return
        }
        awaitingInstallPermission = false
        busy = true
        actionButton.isEnabled = false
        lifecycleScope.launch {
            val apk = runCatching {
                updater.download(update) { percent ->
                    detailView.visibility = View.VISIBLE
                    detailView.text = getString(R.string.update_downloading, percent)
                }
            }.getOrNull()

            busy = false
            if (apk == null || !updater.install(apk)) {
                detailView.text = getString(R.string.update_failed)
                actionButton.isEnabled = true
                return@launch
            }
            // The system installer is now in front; when it finishes this process is replaced.
            renderTunnelState()
        }
    }

    private fun showInstallPermissionDialog() {
        val intent = updater.installPermissionIntent()
        if (intent == null) {
            detailView.visibility = View.VISIBLE
            detailView.text = getString(R.string.update_permission_missing)
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_permission_action) { _, _ ->
                runCatching { startActivity(intent) }.onFailure {
                    detailView.visibility = View.VISIBLE
                    detailView.text = getString(R.string.update_permission_missing)
                }
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
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
        val idle = state != UiState.REGISTERING && state != UiState.CONNECTING
        actionButton.isEnabled = idle
        modeButton.isEnabled = idle
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
