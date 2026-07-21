package com.batman110391.warpfiretv.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.batman110391.warpfiretv.R
import com.batman110391.warpfiretv.warp.WarpConfig
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around the official WireGuard [GoBackend] (userspace wireguard-go, no root).
 *
 * MTU 1280, Cloudflare DNS; the routed range depends on the [TunnelMode].
 */
class WireGuardTunnel private constructor(context: Context) : Tunnel {

    private val appContext = context.applicationContext
    private val backend: Backend = GoBackend(appContext)

    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state: StateFlow<Tunnel.State> = _state.asStateFlow()

    override fun getName(): String = TUNNEL_NAME

    override fun onStateChange(newState: Tunnel.State) {
        _state.value = newState
        if (newState == Tunnel.State.UP) showNotification() else hideNotification()
    }

    /** Brings the tunnel up. Requires [android.net.VpnService.prepare] to have succeeded first. */
    suspend fun up(warpConfig: WarpConfig, settings: TunnelSettings) = withContext(Dispatchers.IO) {
        val newState = backend.setState(this@WireGuardTunnel, Tunnel.State.UP, buildConfig(warpConfig, settings))
        onStateChange(newState)
    }

    suspend fun down() = withContext(Dispatchers.IO) {
        val newState = backend.setState(this@WireGuardTunnel, Tunnel.State.DOWN, null)
        onStateChange(newState)
    }

    /**
     * Rebuilds the tunnel with a different [mode].
     *
     * Bringing the tunnel down calls `stopSelf()` on the backend's VpnService, which is
     * asynchronous. Calling [up] straight afterwards races that teardown: the backend still sees a
     * completed `vpnService` future, hands back the dying service, establishes the new tunnel on
     * it, and then the pending `onDestroy` runs `wgTurnOff` on the handle of the tunnel that was
     * just created — a native call against a tun fd that is already gone, which takes the process
     * with it.
     *
     * So we wait for the teardown to finish before coming back up, and retry a few times in case
     * the service outlives [TEARDOWN_DELAY_MILLIS] on a slow device.
     */
    suspend fun reconnect(warpConfig: WarpConfig, settings: TunnelSettings) {
        Log.i(TAG, "reconnect(warp=${settings.warpEnabled}, apps=${settings.includedApps.size}): down")
        down()
        Log.i(TAG, "reconnect: down done, waiting for service teardown")
        delay(TEARDOWN_DELAY_MILLIS)
        var lastError: Exception? = null
        repeat(UP_ATTEMPTS) { attempt ->
            try {
                Log.i(TAG, "reconnect: up attempt ${attempt + 1}")
                up(warpConfig, settings)
                Log.i(TAG, "reconnect: up succeeded")
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "reconnect: up attempt ${attempt + 1} failed: $e")
                if (attempt < UP_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
            }
        }
        throw lastError ?: IllegalStateException("Could not bring the tunnel back up")
    }

    /** Reads the live backend state, e.g. after the system started us as an always-on VPN. */
    suspend fun refreshState() = withContext(Dispatchers.IO) {
        onStateChange(backend.getState(this@WireGuardTunnel))
    }

    private fun buildConfig(warpConfig: WarpConfig, settings: TunnelSettings): Config {
        val ifaceBuilder = Interface.Builder()
            .parsePrivateKey(warpConfig.privateKey)
            .parseAddresses("${warpConfig.addressV4}, ${warpConfig.addressV6}")
            .parseDnsServers(DNS_SERVERS)
            .setMtu(MTU)

        if (settings.isPerApp) {
            // Our own package goes in too: the cdn-cgi/trace check runs in this process, and from
            // outside the tunnel it would report warp=off while the tunnel is working perfectly.
            // Uninstalled packages are dropped, because addAllowedApplication throws on them and
            // that would stop the tunnel from coming up at all.
            val apps = (settings.includedApps + appContext.packageName).filter { isInstalled(it) }
            Log.i(TAG, "per-app tunnel for ${apps.size} package(s)")
            ifaceBuilder.includeApplications(apps)
        }

        val peer = Peer.Builder()
            .parsePublicKey(warpConfig.peerPublicKey)
            .parseEndpoint(warpConfig.endpoint)
            .parseAllowedIPs(if (settings.warpEnabled) ALLOWED_IPS_FULL else ALLOWED_IPS_DNS_ONLY)
            .setPersistentKeepalive(PERSISTENT_KEEPALIVE)
            .build()
        return Config.Builder().setInterface(ifaceBuilder.build()).addPeer(peer).build()
    }

    /**
     * Checked one package at a time rather than against `getInstalledApplications()`, whose result
     * is subject to API 30 package-visibility filtering and would report selected apps as missing.
     */
    private fun isInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun notificationManager() =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun showNotification() {
        val manager = notificationManager()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
        val notification = builder
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(appContext.getString(R.string.notification_connected))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied on API 33+; the system VPN indicator still shows.
        }
    }

    private fun hideNotification() {
        notificationManager().cancel(NOTIFICATION_ID)
    }

    companion object {
        const val TUNNEL_NAME = "warp"

        /** Never used for anything carrying a key, an address or a token. */
        private const val TAG = "WarpTunnel"
        private const val MTU = 1280
        private const val PERSISTENT_KEEPALIVE = 25
        private const val ALLOWED_IPS_FULL = "0.0.0.0/0, ::/0"

        /** Just the resolver addresses, so only DNS traffic is routed into the tunnel. */
        private const val ALLOWED_IPS_DNS_ONLY =
            "1.1.1.1/32, 1.0.0.1/32, 2606:4700:4700::1111/128, 2606:4700:4700::1001/128"

        private const val DNS_SERVERS = "1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001"

        /** Long enough for the VpnService to reach onDestroy and reset the backend's future. */
        private const val TEARDOWN_DELAY_MILLIS = 1_500L
        private const val UP_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 1_000L

        private const val CHANNEL_ID = "warp_tunnel"
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: WireGuardTunnel? = null

        fun getInstance(context: Context): WireGuardTunnel =
            instance ?: synchronized(this) {
                instance ?: WireGuardTunnel(context).also { instance = it }
            }
    }
}
