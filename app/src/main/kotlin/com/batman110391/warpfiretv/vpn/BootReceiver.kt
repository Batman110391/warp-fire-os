package com.batman110391.warpfiretv.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.batman110391.warpfiretv.warp.WarpConfigStore

/**
 * Reconnects WARP after the device boots.
 *
 * Fire OS exposes no VPN settings UI (verified: even `android.settings.VPN_SETTINGS` does not
 * resolve), so the OS-level always-on VPN cannot be enabled without adb. This receiver is the
 * distributable stand-in: the VPN consent is granted once at first launch and stays valid, so from
 * boot we can bring the tunnel up with no Activity and no dialog.
 *
 * It is not a kill switch — there is no lockdown, so traffic flows normally until the tunnel is up.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return

        val store = WarpConfigStore(context)
        val settings = store.tunnelSettings
        if (!settings.autoConnect) return
        val config = store.load() ?: return

        // prepare() returns an Intent when consent is missing; from a receiver we cannot show it,
        // so we give up quietly and the user reconnects by opening the app once.
        if (VpnService.prepare(context) != null) {
            Log.i(TAG, "boot auto-connect skipped: VPN consent not granted")
            return
        }

        // A background service start is refused on API 30+; hand off to a foreground service that
        // brings the tunnel up from a foreground context.
        Log.i(TAG, "boot auto-connect: starting foreground bring-up")
        WarpConnectService.start(context)
    }

    private companion object {
        const val TAG = "WarpBoot"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
