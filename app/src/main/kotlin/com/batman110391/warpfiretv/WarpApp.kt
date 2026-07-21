package com.batman110391.warpfiretv

import android.app.Application
import com.batman110391.warpfiretv.vpn.WireGuardTunnel
import com.batman110391.warpfiretv.warp.WarpConfigStore
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WarpApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Fired when Android starts the VPN service because the user selected this app as the
        // system "always-on VPN": no Activity is involved, so bring the tunnel up ourselves.
        GoBackend.setAlwaysOnCallback {
            scope.launch {
                val config = WarpConfigStore(this@WarpApp).load() ?: return@launch
                runCatching { WireGuardTunnel.getInstance(this@WarpApp).up(config) }
            }
        }
    }
}
