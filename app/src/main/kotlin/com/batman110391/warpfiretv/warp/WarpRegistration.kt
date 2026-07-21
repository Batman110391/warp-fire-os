package com.batman110391.warpfiretv.warp

import android.os.Build
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Turns "no config yet" into "a usable WARP config", exactly once per install.
 *
 * Everything runs on [Dispatchers.IO]; the caller keeps the UI thread free.
 */
class WarpRegistration(
    private val store: WarpConfigStore,
    private val api: WarpApi = WarpApi(),
) {

    /**
     * Returns the stored config, registering a new WARP device first if needed.
     *
     * Idempotent: a valid stored config short-circuits the network calls entirely.
     */
    suspend fun ensureRegistered(): WarpConfig = withContext(Dispatchers.IO) {
        store.load() ?: registerWithRetry()
    }

    private suspend fun registerWithRetry(): WarpConfig {
        var lastError: Exception? = null
        var backoffMillis = INITIAL_BACKOFF_MILLIS
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return register()
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(backoffMillis)
                    backoffMillis *= 2
                }
            }
        }
        throw lastError ?: IllegalStateException("WARP registration failed")
    }

    private fun register(): WarpConfig {
        val keyPair = KeyPair()
        val publicKey = keyPair.publicKey.toBase64()

        var response = api.register(publicKey, Build.MODEL ?: "Fire TV")
        if (!response.warpEnabled && response.id.isNotEmpty() && response.token.isNotEmpty()) {
            // The consumer app performs this PATCH right after registering; without it the peer
            // exists but WARP traffic is not enabled for the device.
            response = runCatching { api.enableWarp(response.id, response.token) }.getOrDefault(response)
        }

        val peer = response.config.peers.firstOrNull()
        val addresses = response.config.iface.addresses
        require(addresses.v4.isNotEmpty() && addresses.v6.isNotEmpty()) {
            "WARP API did not return interface addresses"
        }

        val config = WarpConfig(
            privateKey = keyPair.privateKey.toBase64(),
            addressV4 = "${addresses.v4}/32",
            addressV6 = "${addresses.v6}/128",
            peerPublicKey = peer?.publicKey?.takeIf { it.isNotEmpty() } ?: FALLBACK_PEER_PUBLIC_KEY,
            endpoint = peer?.endpoint?.host?.takeIf { it.contains(':') } ?: FALLBACK_ENDPOINT,
            deviceId = response.id,
            accessToken = response.token,
        )
        store.save(config)
        return config
    }

    companion object {
        /** Stable public key of the WARP peer, used if the API response omits it. */
        const val FALLBACK_PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        const val FALLBACK_ENDPOINT = "engage.cloudflarewarp.com:2408"

        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MILLIS = 1_500L
    }
}
