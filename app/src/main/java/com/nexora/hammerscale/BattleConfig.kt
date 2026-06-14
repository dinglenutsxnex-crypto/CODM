package com.nexora.hammerscale

import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object BattleConfig {

    private val map = ConcurrentHashMap<String, Int>()

    @Volatile var loadedVersion: String = ""
        private set

    @Volatile var isLoaded: Boolean = false
        private set

    fun roundsFor(battleId: String): Int? = map[battleId]

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
