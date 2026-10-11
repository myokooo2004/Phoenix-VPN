package com.phoenix.phoenixvpn.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class EndpointInfo(
    val ip: String,
    val port: Int,
    val ms: Long?,
    val jitterMs: Long?
) {
    val id: String get() = "$ip:$port"
}

data class FetchedEndpoints(
    /** updated_at from the file, epoch millis. 0 if unparseable. */
    val updatedAt: Long,
    val endpoints: List<EndpointInfo>
)

/**
 * Fetches the published top-10 endpoint list.
 *
 * Schema: {"v":1,"updated_at":"<ISO8601>","isp":"MPT",
 *          "endpoints":[{"ip":..,"port":500,"ms":..,"jitter_ms":..}]}
 *
 * The file may 404 until the first publish — callers treat failure as
 * "keep cache / bundled", never as fatal.
 */
object EndpointService {
    const val URL = "https://raw.githubusercontent.com/myokooo2004/Warp-IP-Scanner/main/endpoints.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(): Result<FetchedEndpoints> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(URL)
                .header("User-Agent", "PhoenixVPN/1.0 (Android)")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}")
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body"))
                try {
                    Result.success(parse(body))
                } catch (e: Exception) {
                    Result.failure(IOException("Bad endpoints.json: ${e.message}", e))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Visible for tests. */
    fun parse(body: String): FetchedEndpoints {
        val o = JSONObject(body)
        val updatedAt = parseIso8601(o.optString("updated_at", ""))
        val arr = o.optJSONArray("endpoints") ?: throw IOException("missing endpoints array")
        val list = mutableListOf<EndpointInfo>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val ip = e.optString("ip", "").trim()
            if (ip.isEmpty()) continue
            list.add(
                EndpointInfo(
                    ip = ip,
                    port = e.optInt("port", 500),
                    ms = if (e.isNull("ms")) null else e.optLong("ms"),
                    jitterMs = if (e.isNull("jitter_ms")) null else e.optLong("jitter_ms")
                )
            )
        }
        if (list.isEmpty()) throw IOException("empty endpoints array")
        return FetchedEndpoints(updatedAt = updatedAt, endpoints = list)
    }

    private fun parseIso8601(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            // Accept "2026-10-04T14:30:00Z" and with fractional seconds.
            val norm = s.trim().replace("Z", "+00:00")
            java.time.OffsetDateTime.parse(norm).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
