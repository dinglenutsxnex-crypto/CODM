package com.nexora.hammerscale

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object BattleConfig {

    private const val DATA_URL =
        "https://raw.githubusercontent.com/dinglenutsxnex-crypto/THE/main/data/battles.json"

    private val map = ConcurrentHashMap<String, Int>()

    @Volatile var loadedVersion: String = ""
        private set

    @Volatile var isLoaded: Boolean = false
        private set

    fun roundsFor(battleId: String): Int? = map[battleId]

    /**
     * Kicks off a background fetch of the battles table.
     * [onLoaded] — called on main thread with (battleCount, version) on success.
     * [onError]  — called on main thread with the error message on failure.
     */
    fun fetchAsync(
        onLoaded: ((battleCount: Int, version: String) -> Unit)? = null,
        onError:  ((errorMsg: String) -> Unit)? = null
    ) {
        Thread {
            try {
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

                onLoaded?.let { cb ->
                    Handler(Looper.getMainLooper()).post { cb(map.size, version) }
                }
            } catch (e: Exception) {
                Log.w("BattleConfig", "Failed to load battle data: ${e.message}")
                onError?.let { cb ->
                    Handler(Looper.getMainLooper()).post { cb(e.message ?: "unknown error") }
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
