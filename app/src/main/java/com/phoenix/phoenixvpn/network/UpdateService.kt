package com.phoenix.phoenixvpn.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * In-app update check + download (v1.46, user spec 2026-10-24).
 *
 * The browser is NEVER involved: some phones' browsers block GitHub
 * APK downloads, so the version check reads the GitHub "latest
 * release" redirect with the app's own HTTP client, the APK is
 * downloaded by the app itself, and the local file is handed to the
 * system installer. Both requests are bound to the underlying
 * physical network (the [EndpointService] rule) so a dead tunnel
 * can never strangle an update.
 */
object UpdateService {

    private const val LATEST_URL =
        "https://github.com/myokooo2004/Phoenix-VPN/releases/latest"
    private const val DOWNLOAD_BASE =
        "https://github.com/myokooo2004/Phoenix-VPN/releases/download"

    data class ReleaseInfo(val tag: String, val version: String) {
        val apkUrl: String get() = "$DOWNLOAD_BASE/$tag/PhoenixVPN-v$version.apk"
        val apkName: String get() = "PhoenixVPN-v$version.apk"
    }

    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** The latest release published on GitHub. Never throws. */
    suspend fun fetchLatest(context: Context): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            val bound = boundClient(context, checkClient)
            val r = attemptLatest(bound ?: checkClient)
            if (r.isSuccess || bound == null) r else attemptLatest(checkClient)
        }

    private fun attemptLatest(client: OkHttpClient): Result<ReleaseInfo> {
        return try {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("User-Agent", "PhoenixVPN (Android update check)")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                    ?: return Result.failure(
                        IOException("HTTP ${resp.code}, no redirect")
                    )
                // Location: .../releases/tag/v1.46-202610240200
                val tag = loc.substringAfter("/releases/tag/", "")
                    .substringBefore("?")
                    .trimEnd('/')
                if (tag.isEmpty()) {
                    return Result.failure(IOException("Bad redirect"))
                }
                val version = tag.removePrefix("v").substringBefore("-")
                if (version.isEmpty() || version == tag) {
                    return Result.failure(IOException("Bad tag: $tag"))
                }
                Result.success(ReleaseInfo(tag, version))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download the release APK into [destDir], reporting progress
     * 0..100 via [onProgress]. Returns the downloaded file.
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        destDir: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            destDir.mkdirs()
            val dest = File(destDir, info.apkName)
            val client = boundClient(context, downloadClient) ?: downloadClient
            val req = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", "PhoenixVPN (Android update)")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${resp.code}")
                    )
                }
                val body = resp.body
                    ?: return@withContext Result.failure(IOException("Empty body"))
                val total = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                onProgress((read * 100 / total).toInt())
                            }
                        }
                    }
                }
            }
            if (dest.length() == 0L) {
                Result.failure(IOException("Empty APK"))
            } else {
                onProgress(100)
                Result.success(dest)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Dotted numeric compare: "1.46" > "1.45"; missing parts = 0. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun boundClient(context: Context, base: OkHttpClient): OkHttpClient? {
        val net = underlyingNetwork(context) ?: return null
        return try {
            base.newBuilder()
                .socketFactory(net.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        net.getAllByName(hostname).toList()
                })
                .build()
        } catch (_: Exception) {
            null
        }
    }

    /** The physical network underneath any VPN, or null. */
    private fun underlyingNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }
}
