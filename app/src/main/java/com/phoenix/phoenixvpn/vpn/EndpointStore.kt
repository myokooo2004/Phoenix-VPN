package com.phoenix.phoenixvpn.vpn

import android.content.Context
import android.content.SharedPreferences
import com.phoenix.phoenixvpn.network.EndpointInfo
import com.phoenix.phoenixvpn.network.FetchedEndpoints
import org.json.JSONArray
import org.json.JSONObject

/**
 * All persisted endpoint/config state, SharedPreferences-backed.
 *
 * Fallback chain: published list (publisher order) → bundled defaults → manual.
 */
class EndpointStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseConf: String?
        get() = prefs.getString(KEY_CONF, null)
        set(v) = prefs.edit().putString(KEY_CONF, v).apply()

    /** Raw fetched endpoints.json cache. */
    var cachedEndpoints: List<EndpointInfo>
        get() = parseList(prefs.getString(KEY_CACHED, null))
        set(v) = prefs.edit().putString(KEY_CACHED, serialize(v)).apply()

    var cachedAt: Long
        get() = prefs.getLong(KEY_CACHED_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_CACHED_AT, v).apply()

    /** updated_at from the published file, epoch millis. */
    var publishedUpdatedAt: Long
        get() = prefs.getLong(KEY_PUBLISHED_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_PUBLISHED_AT, v).apply()

    /** Currently selected endpoint id "ip:port", or the manual endpoint. */
    var currentEndpoint: String?
        get() = prefs.getString(KEY_CURRENT, null)
        set(v) = prefs.edit().putString(KEY_CURRENT, v).apply()

    /** Single manual endpoint slot ("ip:port"), survives updates. */
    var manualEndpoint: String?
        get() = prefs.getString(KEY_MANUAL, null)
        set(v) = prefs.edit().putString(KEY_MANUAL, v).apply()

    var autoRun: Boolean
        get() = prefs.getBoolean(KEY_AUTORUN, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTORUN, v).apply()

    fun saveFetched(f: FetchedEndpoints, now: Long) {
        cachedEndpoints = f.endpoints
        cachedAt = now
        publishedUpdatedAt = f.updatedAt
    }

    /** True when the published file should be re-downloaded (>6h or never). */
    fun isCacheStale(now: Long): Boolean =
        cachedAt == 0L || now - cachedAt > CACHE_TTL_MS

    /** Effective endpoint list: cached file, else bundled defaults. */
    fun effectiveList(): List<EndpointInfo> =
        cachedEndpoints.ifEmpty { BUNDLED }

    private fun serialize(list: List<EndpointInfo>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("ip", e.ip)
                put("port", e.port)
                if (e.ms != null) put("ms", e.ms)
            })
        }
        return arr.toString()
    }

    private fun parseList(s: String?): List<EndpointInfo> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                EndpointInfo(
                    ip = o.getString("ip"),
                    port = o.optInt("port", 500),
                    ms = if (o.isNull("ms")) null else o.optLong("ms"),
                    jitterMs = null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS = "phoenix_endpoints"
        private const val KEY_CONF = "base_conf"
        private const val KEY_CACHED = "cached"
        private const val KEY_CACHED_AT = "cached_at"
        private const val KEY_PUBLISHED_AT = "published_at"
        private const val KEY_CURRENT = "current"
        private const val KEY_MANUAL = "manual"
        private const val KEY_AUTORUN = "auto_run"

        /** Re-download endpoints.json after this long. */
        const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        /** "updated X ago" goes amber after this long. */
        const val STALE_MS = 48 * 60 * 60 * 1000L

        /**
         * Bundled fallback endpoints (UDP 500). Field-verified 2026-10-04 on
         * the publisher's MPT line; WARP endpoints are usable cross-operator.
         */
        val BUNDLED: List<EndpointInfo> = listOf(
            EndpointInfo("8.34.70.118", 500, 136, 5),
            EndpointInfo("8.34.70.80", 500, 141, 8),
            EndpointInfo("8.34.70.106", 500, 147, 2)
        )
    }
}
