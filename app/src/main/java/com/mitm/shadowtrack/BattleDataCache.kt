package com.mitm.shadowtrack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object BattleDataCache {

    private const val TAG = "BattleDataCache"
    private const val CACHE_FILE = "battle_data.json"

    // From sf3_battlewin.py — these decrypt the Nekki CDN config archive
    private val AES_KEY = "08050674cc9ab867197f0cad55a770ca"
    private val AES_IV  = "653e0715236e0f734f1ebf64228b322d"

    const val DEFAULT_URL =
        "https://sf3assets.nekki.com/prod/configs/1.45.0.3.16663-prod_all.zip"

    data class BattleEntry(
        val id:      Long,
        val name:    String,
        val rounds:  Int,
        val type:    String,
        val visible: String
    )

    private val roundsMap   = ConcurrentHashMap<Long, Int>()
    private val entriesList = mutableListOf<BattleEntry>()

    // ── Public API ─────────────────────────────────────────────────────────────

    fun count(): Int = roundsMap.size

    /** Max rounds for this battle, or 0 if not in cache. */
    fun getRounds(battleId: Long): Int = roundsMap[battleId] ?: 0

    /** Snapshot of the sorted battle list for display. */
    fun getList(): List<BattleEntry> = synchronized(entriesList) { entriesList.toList() }

    /** Load the on-disk cache into memory. Call once at startup. */
    fun load(context: Context) {
        try {
            val file = File(context.cacheDir, CACHE_FILE)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val arr  = json.optJSONArray("battles") ?: return
            roundsMap.clear()
            synchronized(entriesList) { entriesList.clear() }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val entry = BattleEntry(
                    id      = o.getLong("id"),
                    name    = o.optString("name", "?"),
                    rounds  = o.optInt("rounds", 3),
                    type    = o.optString("type", "?"),
                    visible = o.optString("visible", "?")
                )
                roundsMap[entry.id] = entry.rounds
                synchronized(entriesList) { entriesList.add(entry) }
            }
            Log.i(TAG, "Loaded ${roundsMap.size} battles from cache")
        } catch (e: Exception) {
            Log.e(TAG, "load failed: $e")
        }
    }

    // ── Download + parse pipeline ──────────────────────────────────────────────

    /**
     * Download the Nekki config archive from [url], AES-decrypt the .enc file inside,
     * parse all event battle JS files, build the battleId→rounds table, and persist.
     * Calls [onProgress] on the IO thread with status strings.
     */
    suspend fun downloadAndParse(
        url: String,
        context: Context,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Download outer zip
            onProgress("Downloading config archive…")
            val conn = java.net.URL(url).openConnection().apply {
                connectTimeout = 15_000; readTimeout = 120_000
            }
            val outerBytes = conn.getInputStream().use { it.readBytes() }
            onProgress("Downloaded ${outerBytes.size / 1024} KB — extracting .enc…")

            // 2. Find the .enc file (shortest filename wins, matching Python logic)
            val encCandidates = mutableListOf<Pair<String, ByteArray>>()
            ZipInputStream(outerBytes.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".enc")) {
                        encCandidates.add(e.name to zis.readBytes())
                    } else { zis.closeEntry() }
                    e = zis.nextEntry
                }
            }
            val encBytes = encCandidates
                .minByOrNull { it.first.substringAfterLast('/').length }?.second
                ?: throw IllegalStateException("No .enc file found in archive")

            onProgress("Decrypting ${encBytes.size / 1024} KB…")

            // 3. AES-128-CBC decrypt → inner zip bytes
            val innerZip = aesDecrypt(encBytes)
            onProgress("Decrypted — parsing battles…")

            // 4. Parse inner zip JS files for battle definitions
            val battles = parseBattlesFromInnerZip(innerZip)
            if (battles.isEmpty()) throw IllegalStateException("No battles parsed from JS files")

            onProgress("Parsed ${battles.size} battles — saving…")

            // 5. Persist to disk
            save(context, battles)

            // 6. Update in-memory cache
            roundsMap.clear()
            synchronized(entriesList) {
                entriesList.clear()
                entriesList.addAll(battles)
            }
            battles.forEach { roundsMap[it.id] = it.rounds }

            onProgress("Done — ${battles.size} battles loaded")

        } catch (e: Exception) {
            Log.e(TAG, "downloadAndParse failed", e)
            onProgress("FAILED: ${e.message}")
        }
    }

    // ── AES ───────────────────────────────────────────────────────────────────

    private fun aesDecrypt(data: ByteArray): ByteArray {
        val key    = SecretKeySpec(hexToBytes(AES_KEY), "AES")
        val iv     = IvParameterSpec(hexToBytes(AES_IV))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        return cipher.doFinal(data)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        return ByteArray(len) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // ── Inner zip JS parser ───────────────────────────────────────────────────

    private val KNOWN_TEMPLATES = mapOf(
        "BattleEventDefault.ASCENSION" to "ASCENSION",
        "BattleEventDefault.SURVIVAL"  to "SURVIVAL",
        "BattleEventDefault.HUB"       to "HUB"
    )

    private val VISIBLE_SHORT = mapOf(
        "VisibleBattleType.NORMAL"      to "NORMAL",
        "VisibleBattleType.HARD"        to "HARD",
        "VisibleBattleType.EVENT"       to "EVENT",
        "VisibleBattleType.STANDART"    to "STANDARD",
        "VisibleBattleType.GRAND"       to "GRAND",
        "VisibleBattleType.TOURNAMENT"  to "TOURNAMENT",
        "VisibleBattleType.SINGLE"      to "SINGLE",
        "VisibleBattleType.DUEL"        to "DUEL",
        "VisibleBattleType.BOSS"        to "BOSS",
        "VisibleBattleType.GIG"         to "GIG",
        "VisibleBattleType.CHOICE"      to "CHOICE",
        "VisibleBattleType.BIRTHDAY"    to "BIRTHDAY",
        "VisibleBattleType.MEMORY"      to "MEMORY"
    )

    private fun parseBattlesFromInnerZip(zipBytes: ByteArray): List<BattleEntry> {
        val raw      = mutableListOf<BattleEntry>()
        val seen     = mutableSetOf<Long>()

        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory &&
                    name.startsWith("scripts/features/events") &&
                    name.endsWith(".js")) {
                    try {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        parseBattlesFromJs(text).forEach { b ->
                            if (seen.add(b.id)) raw.add(b)
                        }
                    } catch (_: Exception) {}
                } else {
                    zis.closeEntry()
                }
                entry = zis.nextEntry
            }
        }

        return raw.sortedBy { it.id }
    }

    private val reId       = Regex("""\bID\s*:\s*(\d{5,})""")
    private val reName     = Regex("""([A-Z][A-Z0-9_]{3,})\s*:\s*\{[^{]*$""")
    private val reTemplate = Regex("""DefaultTemplate\s*:\s*(BattleEventDefault\.\w+)""")
    private val reRounds   = Regex("""RoundsToWin\s*:\s*(\d+)""")
    private val reVisible  = Regex("""VisibleType\s*:\s*(VisibleBattleType\.\w+)""")

    private fun parseBattlesFromJs(text: String): List<BattleEntry> {
        val results = mutableListOf<BattleEntry>()
        for (m in reId.findAll(text)) {
            val battleId = m.groupValues[1].toLongOrNull() ?: continue
            val before   = text.substring(maxOf(0, m.range.first - 300), m.range.first)
            val after    = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 1500))

            val template = reTemplate.find(after)?.groupValues?.get(1) ?: continue
            val rounds   = reRounds.find(after)?.groupValues?.get(1)?.toIntOrNull() ?: 3
            val visible  = VISIBLE_SHORT[reVisible.find(after)?.groupValues?.get(1) ?: ""] ?: "?"
            val name     = reName.find(before)?.groupValues?.get(1) ?: "?"
            val tShort   = KNOWN_TEMPLATES[template] ?: template.removePrefix("BattleEventDefault.")

            results.add(BattleEntry(id = battleId, name = name,
                rounds = rounds, type = tShort, visible = visible))
        }
        return results
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun save(context: Context, battles: List<BattleEntry>) {
        val arr = JSONArray()
        battles.forEach { b ->
            arr.put(JSONObject().apply {
                put("id",      b.id)
                put("name",    b.name)
                put("rounds",  b.rounds)
                put("type",    b.type)
                put("visible", b.visible)
            })
        }
        val json = JSONObject().apply { put("battles", arr) }
        File(context.cacheDir, CACHE_FILE).writeText(json.toString())
    }
}
