package com.batman110391.warpfiretv.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.batman110391.warpfiretv.R
import com.batman110391.warpfiretv.warp.WarpConfig
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around the official WireGuard [GoBackend] (userspace wireguard-go, no root).
 *
 * Full tunnel only: `AllowedIPs = 0.0.0.0/0, ::/0`, MTU 1280, Cloudflare DNS.
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
    suspend fun up(warpConfig: WarpConfig) = withContext(Dispatchers.IO) {
        val newState = backend.setState(this@WireGuardTunnel, Tunnel.State.UP, buildConfig(warpConfig))
        onStateChange(newState)
    }

    suspend fun down() = withContext(Dispatchers.IO) {
        val newState = backend.setState(this@WireGuardTunnel, Tunnel.State.DOWN, null)
        onStateChange(newState)
    }

    /** Reads the live backend state, e.g. after the system started us as an always-on VPN. */
    suspend fun refreshState() = withContext(Dispatchers.IO) {
        onStateChange(backend.getState(this@WireGuardTunnel))
    }

    private fun buildConfig(warpConfig: WarpConfig): Config {
        val iface = Interface.Builder()
            .parsePrivateKey(warpConfig.privateKey)
            .parseAddresses("${warpConfig.addressV4}, ${warpConfig.addressV6}")
            .parseDnsServers(DNS_SERVERS)
            .setMtu(MTU)
            .build()
        val peer = Peer.Builder()
            .parsePublicKey(warpConfig.peerPublicKey)
            .parseEndpoint(warpConfig.endpoint)
            .parseAllowedIPs(ALLOWED_IPS)
            .setPersistentKeepalive(PERSISTENT_KEEPALIVE)
            .build()
        return Config.Builder().setInterface(iface).addPeer(peer).build()
    }

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
        private const val MTU = 1280
        private const val PERSISTENT_KEEPALIVE = 25
        private const val ALLOWED_IPS = "0.0.0.0/0, ::/0"
        private const val DNS_SERVERS = "1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001"

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
