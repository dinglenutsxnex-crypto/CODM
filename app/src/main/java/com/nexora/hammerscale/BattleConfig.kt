package com.nexora.hammerscale

import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object BattleConfig {

    /**
     * Stores either Int (single-fight) or List<Int> (multi-fight / skeleton battles).
     *
     * Single-fight:  "3002601": 3        → map["3002601"] = 3
     * Multi-fight:   "3101111": [2,2,3]  → map["3101111"] = listOf(2, 2, 3)
     *
     * Each element in the list is the RoundsToWin for sub-battle at that index,
     * matching the server's field[3] value in the inbound event_battle_start_fight
     * response (0-indexed; absent = 0).
     */
    private val map = ConcurrentHashMap<String, Any>()

    @Volatile var loadedVersion: String = ""
        private set

    @Volatile var isLoaded: Boolean = false
        private set

    /**
     * Returns the rounds-to-win for the given battle and sub-battle index.
     *
     * @param battleId     The battle template ID string (e.g. "3101111").
     * @param subBattleIdx 0-indexed sub-battle number from server field[3].
     *                     Defaults to 0 (first fight / single-fight battles).
     *
     * For single-fight battles (Int stored): returns the same value regardless of idx.
     * For multi-fight battles (List stored): returns list[idx], or list.last() if idx
     * is out of bounds (defensive fallback).
     * Returns null if battleId is not in the map.
     */
    fun roundsFor(battleId: String, subBattleIdx: Int = 0): Int? {
        return when (val v = map[battleId]) {
            is Int  -> v
            is List<*> -> {
                val list = v.filterIsInstance<Int>()
                list.getOrNull(subBattleIdx) ?: list.lastOrNull()
            }
            else -> null
        }
    }

    /**
     * Returns true if this battle is a multi-fight sequence (skeleton battle)
     * with a separate RoundsToWin per sub-battle.
     *
     * When false, the same round count applies to every fight and the server's
     * field[3] sub-battle index can be ignored.
     */
    fun isMultiFight(battleId: String): Boolean = map[battleId] is List<*>

    /**
     * Returns the total number of sub-battles in the sequence.
     * 1 for single-fight battles (or unknown IDs), list size for multi-fight.
     */
    fun totalFightsFor(battleId: String): Int {
        return when (val v = map[battleId]) {
            is List<*> -> v.size.coerceAtLeast(1)
            else       -> 1
        }
    }

    /**
     * Loads and decrypts battles.json from the encrypted APK resource (res/raw/battles.enc).
     * [onLoaded] — called on main thread with (battleCount, version) on success.
     * [onError]  — called on main thread with the error message on failure.
     */
    fun loadAsync(
        resources: Resources,
        onLoaded: ((battleCount: Int, version: String) -> Unit)? = null,
        onError:  ((errorMsg: String) -> Unit)? = null
    ) {
        Thread {
            try {
                Log.d("BattleConfig", "Loading battle data from encrypted resource")
                val encrypted = resources.openRawResource(R.raw.battles).readBytes()
                val text = AES.decrypt(encrypted).toString(Charsets.UTF_8)
                val root    = JSONObject(text)
                val version = root.optString("version", "")
                val battles = root.optJSONObject("battles") ?: JSONObject()

                val newMap = HashMap<String, Any>(battles.length())
                battles.keys().forEach { key ->
                    val raw = battles.get(key)
                    newMap[key] = when (raw) {
                        is JSONArray -> {
                            // Multi-fight skeleton battle: array of per-sub-battle round counts
                            val list = ArrayList<Int>(raw.length())
                            for (i in 0 until raw.length()) list.add(raw.getInt(i))
                            list as List<Int>
                        }
                        is Int    -> raw
                        is Number -> raw.toInt()
                        else      -> {
                            // Fallback: try parsing as int
                            try { battles.getInt(key) } catch (_: Exception) { 3 }
                        }
                    }
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

object AES {
    private val KEY = byteArrayOf(
        0x08.toByte(), 0x05.toByte(), 0x06.toByte(), 0x74.toByte(),
        0xcc.toByte(), 0x9a.toByte(), 0xb8.toByte(), 0x67.toByte(),
        0x19.toByte(), 0x7f.toByte(), 0x0c.toByte(), 0xad.toByte(),
        0x55.toByte(), 0xa7.toByte(), 0x70.toByte(), 0xca.toByte()
    )
    private val NONCE = byteArrayOf(
        0x65.toByte(), 0x3e.toByte(), 0x07.toByte(), 0x15.toByte(),
        0x23.toByte(), 0x6e.toByte(), 0x0f.toByte(), 0x73.toByte()
    )

    fun decrypt(data: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(KEY, "AES")
        val ivSpec = IvParameterSpec(NONCE)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data)
    }
}
