package com.batman110391.warpfiretv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.batman110391.warpfiretv.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A release newer than the running build, with the URL of its `warp-firetv.apk` asset. */
data class AvailableUpdate(
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * Checks GitHub Releases for a newer APK and installs it in place.
 *
 * Deliberately silent on failure: a missing update check must never get in the way of the VPN,
 * which is what the app is actually for.
 */
class AppUpdater(context: Context) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Used for the version check, where the redirect target *is* the answer. */
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Returns the newer release, or null when up to date or the check failed.
     *
     * Reads the tag from the redirect that `/releases/latest` issues towards `/releases/tag/vX.Y.Z`
     * rather than calling the GitHub API: the API allows 60 unauthenticated requests per hour per
     * IP, and with the tunnel up every user of this app shares a handful of Cloudflare egress
     * addresses, so that quota would be exhausted by strangers.
     */
    suspend fun checkForUpdate(): AvailableUpdate? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("User-Agent", "warp-firetv")
                .head()
                .build()
            val location = noRedirectClient.newCall(request).execute().use { response ->
                response.header("Location")
            } ?: return@withContext null

            val latest = location.substringAfterLast("/tag/", "").removePrefix("v")
            if (latest.isEmpty() || !isNewer(latest, BuildConfig.VERSION_NAME)) return@withContext null

            AvailableUpdate(latest, DOWNLOAD_URL, 0)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads the APK, reporting progress as 0..100, and returns the file.
     *
     * [onProgress] is invoked on the main thread, so callers can touch views from it.
     *
     * Below API 24 the installer reads the file directly, so it has to live somewhere
     * world-readable; from API 24 on it arrives through a [FileProvider] grant instead.
     */
    suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                File(appContext.cacheDir, "updates")
            } else {
                File(appContext.externalCacheDir ?: appContext.cacheDir, "updates")
            }
            directory.mkdirs()
            val target = File(directory, ASSET_NAME)
            if (target.exists()) target.delete()

            val request = Request.Builder().url(update.downloadUrl).header("User-Agent", "warp-firetv").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val total = response.body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastReported) {
                                    lastReported = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                target.setReadable(true, false)
            }
            target
        }

    /**
     * Hands the APK to the system package installer.
     *
     * The system asks for confirmation; the app cannot install silently. Returns false when the
     * installer could not be launched at all.
     */
    fun install(apk: File): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val uri = FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.updates", apk)
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                @Suppress("DEPRECATION")
                setDataAndType(Uri.fromFile(apk), APK_MIME_TYPE)
            }
        }
        appContext.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * True when the system will let us launch an install. On API 26+ the user has to grant
     * "install unknown apps" to this app first.
     */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user grants the install permission, or null if unavailable. */
    fun installPermissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
            )
        } else {
            null
        }

    /** Compares dotted numeric versions; non-numeric suffixes are ignored. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = parse(candidate)
        val currentParts = parse(current)
        for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
            val a = candidateParts.getOrElse(index) { 0 }
            val b = currentParts.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private companion object {
        const val REPO_URL = "https://github.com/Batman110391/warp-fire-os"
        const val LATEST_RELEASE_URL = "$REPO_URL/releases/latest"

        /** Stable asset name, so the download URL never changes between releases. */
        const val ASSET_NAME = "warp-firetv.apk"
        const val DOWNLOAD_URL = "$REPO_URL/releases/latest/download/$ASSET_NAME"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
