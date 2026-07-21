package com.batman110391.warpfiretv.warp

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion

/**
 * Minimal client for the Cloudflare WARP consumer registration API.
 *
 * Endpoint, API version string, client headers and the request payload are ported from the current
 * `wgcf` sources (github.com/ViRb3/wgcf, `cloudflare/api.go` + `openapi/`), not guessed:
 *
 *   base        https://api.cloudflareclient.com
 *   version     v0a1922
 *   register    POST   /{version}/reg
 *   device      PATCH  /{version}/reg/{id}
 *   headers     User-Agent: okhttp/3.12.1, CF-Client-Version: a-6.3-1922
 *
 * The TLS version is pinned to 1.2 for the same reason wgcf does it: the API answers HTTP 403
 * (error 1020) to handshakes that do not look like the official Android client.
 */
class WarpApi {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build(),
            ),
        )
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Registers a fresh WARP device for [publicKey]. */
    fun register(publicKey: String, deviceModel: String): RegisterResponse {
        val payload = RegisterRequest(
            fcmToken = "",
            installId = "",
            key = publicKey,
            locale = "en_US",
            model = deviceModel,
            tos = timestamp(),
            type = "Android",
        )
        val request = Request.Builder()
            .url("$BASE_URL/$API_VERSION/reg")
            .headers(defaultHeaders())
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    /** Flips `warp_enabled` on for a freshly registered device. */
    fun enableWarp(deviceId: String, accessToken: String): RegisterResponse {
        val request = Request.Builder()
            .url("$BASE_URL/$API_VERSION/reg/$deviceId")
            .headers(defaultHeaders())
            .header("Authorization", "Bearer $accessToken")
            .patch("""{"warp_enabled":true}""".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(execute(request))
    }

    /**
     * Fetches `https://www.cloudflare.com/cdn-cgi/trace` and returns the value of the `warp=` line
     * (`on`, `off`, `plus`), or null when the check could not be performed.
     */
    fun fetchWarpStatus(): String? = try {
        val request = Request.Builder().url(TRACE_URL).get().build()
        client.newCall(request).execute().use { response ->
            response.body.string()
                .lineSequence()
                .firstOrNull { it.startsWith("warp=") }
                ?.substringAfter('=')
                ?.trim()
        }
    } catch (_: Exception) {
        null
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                // Deliberately does not include the body: it can carry the access token.
                throw IOException("WARP API returned HTTP ${response.code}")
            }
            body
        }

    private fun defaultHeaders() = okhttp3.Headers.Builder()
        .set("User-Agent", USER_AGENT)
        .set("CF-Client-Version", CLIENT_VERSION)
        .set("Content-Type", "application/json")
        .set("Accept", "application/json")
        .build()

    /** RFC 3339 UTC timestamp, as `wgcf` sends for the `tos` field. */
    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    companion object {
        const val BASE_URL = "https://api.cloudflareclient.com"
        const val API_VERSION = "v0a1922"
        const val USER_AGENT = "okhttp/3.12.1"
        const val CLIENT_VERSION = "a-6.3-1922"
        const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
data class RegisterRequest(
    @SerialName("fcm_token") val fcmToken: String,
    @SerialName("install_id") val installId: String,
    val key: String,
    val locale: String,
    val model: String,
    val tos: String,
    val type: String,
)

@Serializable
data class RegisterResponse(
    val id: String = "",
    val token: String = "",
    @SerialName("warp_enabled") val warpEnabled: Boolean = false,
    val config: WarpApiConfig = WarpApiConfig(),
)

@Serializable
data class WarpApiConfig(
    @SerialName("interface") val iface: WarpApiInterface = WarpApiInterface(),
    val peers: List<WarpApiPeer> = emptyList(),
)

@Serializable
data class WarpApiInterface(
    val addresses: WarpApiAddresses = WarpApiAddresses(),
)

@Serializable
data class WarpApiAddresses(
    val v4: String = "",
    val v6: String = "",
)

@Serializable
data class WarpApiPeer(
    @SerialName("public_key") val publicKey: String = "",
    val endpoint: WarpApiEndpoint = WarpApiEndpoint(),
)

@Serializable
data class WarpApiEndpoint(
    val host: String = "",
    val v4: String = "",
    val v6: String = "",
)
