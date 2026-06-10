package com.mitm.shadowtrack.net

import com.mitm.shadowtrack.model.GameEvent
import com.mitm.shadowtrack.model.LiveMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Parses the SF3 custom binary protocol.
 *
 * Small packet:  [0x01][1B length][raw protobuf payload]
 * Large packet:  [0x02][4B LE length][raw-deflate compressed protobuf payload]
 *
 * Outer protobuf envelope:
 *   field[1] varint  = controller  (1=system, 3=extension)
 *   field[2] string  = command     ("HANDSHAKE", "LOGIN", "ping", etc.)
 *   field[3] bytes   = params (nested protobuf)
 */
object GameProtocolParser {

    private val BATTLE_COMMANDS = setOf(
        "brawler_start", "brawler_finish", "finish_fight",
        "refresh_battles", "cheat_generate_battle",
        "clan_refresh_battles", "start_fight", "get_battles"
    )

    fun parse(data: ByteArray, direction: LiveMessage.Direction): GameEvent? {
        if (data.size < 3) return null
        val proto = extractPayload(data) ?: return null
        return try { parseEnvelope(proto, direction) } catch (_: Exception) { null }
    }

    // ── Framing ───────────────────────────────────────────────────────────

    private fun extractPayload(data: ByteArray): ByteArray? {
        return when (data[0].toInt() and 0xFF) {
            0x01 -> {
                val len = data[1].toInt() and 0xFF
                if (data.size < 2 + len) null else data.copyOfRange(2, 2 + len)
            }
            0x02 -> {
                if (data.size < 5) null
                else {
                    val len = ByteBuffer.wrap(data, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (len <= 0 || data.size < 5 + len) null
                    else rawDeflate(data.copyOfRange(5, 5 + len))
                }
            }
            else -> null
        }
    }

    // ── Envelope dispatch ─────────────────────────────────────────────────

    private fun parseEnvelope(proto: ByteArray, dir: LiveMessage.Direction): GameEvent? {
        val fields  = readProtoFields(proto)
        val command = (fields[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
        val params  = fields[3] as? ByteArray
        val isOut   = dir == LiveMessage.Direction.OUTBOUND

        return when {
            command == "HANDSHAKE" && isOut -> {
                // field[3] → field[1] = server name string ("SFA-NEBU-1")
                val name = params?.let { p ->
                    (readProtoFields(p)[1] as? ByteArray)?.toString(Charsets.UTF_8)
                } ?: "SFA-NEBU-1"
                GameEvent.HandshakeOut(name)
            }

            command == "HANDSHAKE" && !isOut -> {
                // field[3] → field[2] = session token bytes
                // (or nested one more level: field[3] → field[2] → field[2])
                val token = params?.let { p ->
                    val top = (readProtoFields(p)[2] as? ByteArray)
                    // Try one level deeper first
                    top?.let { readProtoFields(it)[2] as? ByteArray }
                        ?.toString(Charsets.UTF_8)
                        ?: top?.toString(Charsets.UTF_8)
                } ?: "?"
                GameEvent.HandshakeIn(token)
            }

            command == "LOGIN" && isOut -> {
                val (guid, pass) = extractLoginCredentials(params)
                GameEvent.LoginOut(guid, pass)
            }

            command == "LOGIN" && !isOut -> GameEvent.LoginIn()

            command in BATTLE_COMMANDS -> {
                val battleId = params?.let { extractBattleId(it) }
                GameEvent.BattleCommand(command, battleId, isOut)
            }

            else -> GameEvent.Command(command, isOut)
        }
    }

    // ── Login extraction ──────────────────────────────────────────────────

    /**
     * Confirmed structure from real capture (user_2.bin decompressed):
     *   outer.field[3] = params
     *   params.field[2] = auth_wrapper   (tag 12, length 98)
     *   auth_wrapper.field[2] = json     (tag 12, length 94)
     *   json = '{"login":"<guid>","password":"<md5>"}'
     *
     * That's exactly 2 levels of field[2] nesting — NOT 3.
     */
    private fun extractLoginCredentials(params: ByteArray?): Pair<String, String> {
        if (params == null) return "?" to "?"
        return try {
            // Level 1: params.field[2] = auth_wrapper
            val authWrapper = (readProtoFields(params)[2] as? ByteArray) ?: return scanRaw(params)
            // Level 2: auth_wrapper.field[2] = JSON bytes
            val jsonBytes   = (readProtoFields(authWrapper)[2] as? ByteArray) ?: return scanRaw(params)
            val json = jsonBytes.toString(Charsets.UTF_8)
            val guid = extractJsonValue(json, "login")    ?: return scanRaw(params)
            val pass = extractJsonValue(json, "password") ?: return scanRaw(params)
            guid to pass
        } catch (_: Exception) {
            scanRaw(params)
        }
    }

    private fun scanRaw(data: ByteArray): Pair<String, String> {
        val text = data.toString(Charsets.ISO_8859_1)
        val guid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .find(text)?.value ?: "?"
        val pass = Regex("[0-9a-f]{32}").find(text)?.value ?: "?"
        return guid to pass
    }

    private fun extractBattleId(params: ByteArray): String? {
        return try {
            val fields = readProtoFields(params)
            for ((_, v) in fields) {
                when (v) {
                    is Long -> if (v in 1_000_000L..99_999_999L) return v.toString()
                    is ByteArray -> {
                        val s = v.toString(Charsets.UTF_8)
                        if (s.matches(Regex("[0-9]{6,8}"))) return s
                        // Recurse one level into nested proto
                        val nested = readProtoFields(v)
                        for ((_, nv) in nested) {
                            if (nv is Long && nv in 1_000_000L..99_999_999L)
                                return nv.toString()
                        }
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    // ── Minimal protobuf reader ───────────────────────────────────────────

    /**
     * Returns Map<fieldNumber, value> where value is:
     *   Long      for varint (wire type 0)
     *   ByteArray for length-delimited (wire type 2)
     * 64-bit and 32-bit fixed fields are skipped.
     */
    fun readProtoFields(data: ByteArray): Map<Int, Any> {
        val result = LinkedHashMap<Int, Any>()
        var pos = 0
        while (pos < data.size) {
            val (tag, tagLen) = readVarint(data, pos) ?: break
            pos += tagLen
            val fieldNum  = (tag shr 3).toInt()
            val wireType  = (tag and 7L).toInt()
            when (wireType) {
                0 -> {
                    val (v, len) = readVarint(data, pos) ?: break
                    result[fieldNum] = v
                    pos += len
                }
                2 -> {
                    val (len, lenLen) = readVarint(data, pos) ?: break
                    pos += lenLen
                    val bytes = len.toInt()
                    if (pos + bytes > data.size) break
                    result[fieldNum] = data.copyOfRange(pos, pos + bytes)
                    pos += bytes
                }
                1 -> pos += 8  // 64-bit fixed — skip
                5 -> pos += 4  // 32-bit fixed — skip
                else -> break
            }
        }
        return result
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i++].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value to (i - start)
            shift += 7
            if (shift >= 64) break
        }
        return null
    }

    // ── Raw deflate (wbits = -15, no zlib header) ─────────────────────────

    private fun rawDeflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater(true)   // true = nowrap = raw deflate
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(8192)
        while (!inflater.finished() && !inflater.needsInput()) {
            val n = inflater.inflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    // ── JSON helper ───────────────────────────────────────────────────────

    private fun extractJsonValue(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
}
