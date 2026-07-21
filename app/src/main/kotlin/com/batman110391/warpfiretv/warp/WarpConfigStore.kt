package com.batman110391.warpfiretv.warp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.batman110391.warpfiretv.vpn.TunnelMode

/**
 * Persisted WARP device configuration.
 *
 * [privateKey] is a secret: it is only ever read back into the WireGuard config and must never be
 * logged or exported.
 */
data class WarpConfig(
    val privateKey: String,
    val addressV4: String,
    val addressV6: String,
    val peerPublicKey: String,
    val endpoint: String,
    val deviceId: String,
    val accessToken: String,
)

/**
 * Encrypted storage for the WARP registration.
 *
 * Uses [EncryptedSharedPreferences] where available (API 23+, which covers every Fire TV running
 * Fire OS 6 or newer). On API 22 the AndroidKeyStore cannot back a master key, so we fall back to
 * regular app-private SharedPreferences — still unreadable by other apps on a non-rooted device.
 */
class WarpConfigStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    val isRegistered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)

    fun load(): WarpConfig? {
        if (!isRegistered) return null
        val privateKey = prefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val addressV4 = prefs.getString(KEY_ADDRESS_V4, null) ?: return null
        val addressV6 = prefs.getString(KEY_ADDRESS_V6, null) ?: return null
        val peerPublicKey = prefs.getString(KEY_PEER_PUBLIC_KEY, null) ?: return null
        val endpoint = prefs.getString(KEY_ENDPOINT, null) ?: return null
        return WarpConfig(
            privateKey = privateKey,
            addressV4 = addressV4,
            addressV6 = addressV6,
            peerPublicKey = peerPublicKey,
            endpoint = endpoint,
            deviceId = prefs.getString(KEY_DEVICE_ID, "").orEmpty(),
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty(),
        )
    }

    fun save(config: WarpConfig) {
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, config.privateKey)
            .putString(KEY_ADDRESS_V4, config.addressV4)
            .putString(KEY_ADDRESS_V6, config.addressV6)
            .putString(KEY_PEER_PUBLIC_KEY, config.peerPublicKey)
            .putString(KEY_ENDPOINT, config.endpoint)
            .putString(KEY_DEVICE_ID, config.deviceId)
            .putString(KEY_ACCESS_TOKEN, config.accessToken)
            .putBoolean(KEY_REGISTERED, true)
            .apply()
    }

    /**
     * Selected tunnel mode, as the [TunnelMode] name. Survives [clear] deliberately: wiping the
     * WARP registration should not silently switch the user back to full tunnel.
     */
    var tunnelMode: TunnelMode
        get() = runCatching { TunnelMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(TunnelMode.WARP)
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    /** Wipes the stored registration so the next launch registers a brand new WARP device. */
    fun clear() {
        val mode = tunnelMode
        prefs.edit().clear().apply()
        tunnelMode = mode
    }

    private fun createPrefs(context: Context): SharedPreferences {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    ENCRYPTED_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (_: Exception) {
                // Corrupted keystore entry or an OEM keystore that misbehaves: fall through.
            }
        }
        return context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
    }

    private companion object {
        const val ENCRYPTED_FILE = "warp_config_secure"
        const val PLAIN_FILE = "warp_config"

        const val KEY_REGISTERED = "registered"
        const val KEY_PRIVATE_KEY = "private_key"
        const val KEY_ADDRESS_V4 = "address_v4"
        const val KEY_ADDRESS_V6 = "address_v6"
        const val KEY_PEER_PUBLIC_KEY = "peer_public_key"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_MODE = "tunnel_mode"
    }
}
