package com.batman110391.warpfiretv.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.batman110391.warpfiretv.R
import com.batman110391.warpfiretv.warp.WarpConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Brings the tunnel up from a foreground context.
 *
 * A [BootReceiver] runs in the background, and on API 30+ a background start of the backend's
 * VpnService is refused ("Background start not allowed ... startFg?=false"). Starting *this* service
 * with `startForegroundService` and calling [Service.startForeground] immediately gives the tunnel
 * bring-up a foreground context, so the backend's own `startService` is allowed.
 *
 * It stops itself once the tunnel is up; from then on the backend's VpnService keeps it alive.
 */
class WarpConnectService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val store = WarpConfigStore(this)
        val config = store.load()
        if (config == null) {
            stopSelfCompat()
            return START_NOT_STICKY
        }
        val settings = store.tunnelSettings

        scope.launch {
            try {
                Log.i(TAG, "foreground bring-up starting")
                WireGuardTunnel.getInstance(this@WarpConnectService).up(config, settings)
                Log.i(TAG, "foreground bring-up done")
            } catch (e: Exception) {
                Log.w(TAG, "foreground bring-up failed: $e")
            } finally {
                stopSelfCompat()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_connecting))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WarpConnectSvc"
        private const val CHANNEL_ID = "warp_tunnel"
        private const val NOTIFICATION_ID = 2

        /** Starts the tunnel bring-up from a background caller (e.g. the boot receiver). */
        fun start(context: Context) {
            val intent = Intent(context, WarpConnectService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
