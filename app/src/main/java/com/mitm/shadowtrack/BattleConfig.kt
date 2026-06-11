package com.mitm.shadowtrack

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads and caches the battle-id → max-rounds table published by the
 * GitHub Actions workflow.
 *
 * After pushing to GitHub, set DATA_URL to:
 *   https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/data/battles.json
 *
 * If the URL is empty or the download fails the map stays empty and the
 * overlay falls back to the manual rounds counter — no crash, no blocking.
 */
object BattleConfig {

    // ── UPDATE THIS after pushing to GitHub ──────────────────────────────────
    private const val DATA_URL =
        "https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/data/battles.json"
    // ─────────────────────────────────────────────────────────────────────────

    private val map = ConcurrentHashMap<String, Int>()

    @Volatile var loadedVersion: String = ""
        private set

    /** True once at least one successful fetch has populated the map. */
    @Volatile var isLoaded: Boolean = false
        private set

    /**
     * Returns the known RoundsToWin for [battleId], or null if the table
     * hasn't loaded yet or this battle ID isn't in it.
     * [battleId] may be the numeric string ("3002601") or a decimal Long.
     */
    fun roundsFor(battleId: String): Int? = map[battleId]

    /**
     * Kicks off a background fetch of the battles table.
     * Safe to call multiple times — a fresh fetch replaces the previous data.
     * Must NOT be called on the main thread (it does network I/O).
     */
    fun fetchAsync() {
        Thread {
            try {
                if (DATA_URL.contains("YOUR_USER")) {
                    Log.w("BattleConfig", "DATA_URL not configured — skipping fetch")
                    return@Thread
                }
                Log.d("BattleConfig", "Fetching battle data from $DATA_URL")
                val conn = URL(DATA_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout    = 15_000
                conn.setRequestProperty("User-Agent", "HAMMERSCALE/1.0")
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val root    = JSONObject(text)
                val version = root.optString("version", "")
                val battles = root.optJSONObject("battles") ?: JSONObject()

                val newMap = HashMap<String, Int>(battles.length())
                battles.keys().forEach { key ->
                    newMap[key] = battles.getInt(key)
                }
                map.clear()
                map.putAll(newMap)
                loadedVersion = version
                isLoaded = true
                Log.d("BattleConfig", "Loaded ${map.size} battles  version=$version")
            } catch (e: Exception) {
                Log.w("BattleConfig", "Failed to load battle data: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }
}
