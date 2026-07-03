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

    // Stores either Int (single-fight) or List<Int> (multi-fight / skeleton battles).
    // Each element in the list is the RoundsToWin for the sub-battle at that index,
    // matching the server's field[3] value in the inbound event_battle_start_fight
    // response (0-indexed; absent = 0).
    private val map = ConcurrentHashMap<String, Any>()

    @Volatile var loadedVersion: String = ""
        private set

    @Volatile var isLoaded: Boolean = false
        private set

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

    fun isMultiFight(battleId: String): Boolean = map[battleId] is List<*>

    fun totalFightsFor(battleId: String): Int {
        return when (val v = map[battleId]) {
            is List<*> -> v.size.coerceAtLeast(1)
            else       -> 1
        }
    }

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
                            val list = ArrayList<Int>(raw.length())
                            for (i in 0 until raw.length()) list.add(raw.getInt(i))
                            list as List<Int>
                        }
                        is Int    -> raw
                        is Number -> raw.toInt()
                        else      -> try { battles.getInt(key) } catch (_: Exception) { 3 }
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
