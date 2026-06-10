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
        "clan_refresh_battles", "start_fight", "get_battles",
        "event_battle_start_fight"
    )

    private val BATTLE_START_COMMANDS = setOf("start_fight", "event_battle_start_fight")
    private val BATTLE_END_COMMANDS   = setOf("finish_fight", "brawler_finish")

    fun parse(data: ByteArray, direction: LiveMessage.Direction): GameEvent? {
        if (data.size < 3) return null

        // ── Proto parsing first (most accurate — resolves varints properly) ────
        val proto = extractPayload(data)
        if (proto != null) {
            val protoResult = try { parseEnvelope(proto, direction) } catch (_: Exception) { null }
            if (protoResult != null) return protoResult
        }

        // ── Raw-text fallback — catches commands in non-standard framing ────
        val rawText = data.toString(Charsets.ISO_8859_1)
        return rawTextScan(rawText, direction)
    }

    // ── Raw-text fast scan (skims every byte for command strings) ─────────

    private fun rawTextScan(text: String, dir: LiveMessage.Direction): GameEvent? {
        val isOut = dir == LiveMessage.Direction.OUTBOUND
        for (cmd in BATTLE_START_COMMANDS) {
            if (text.contains(cmd)) {
                val id = extractIdFromRawText(text)
                return GameEvent.BattleStarted(id ?: "?")
            }
        }
        for (cmd in BATTLE_END_COMMANDS) {
            if (text.contains(cmd)) {
                val id = extractIdFromRawText(text)
                return GameEvent.BattleCommand(cmd, id, isOut)
            }
        }
        return null
    }

    /**
     * Skims raw ISO-8859-1 text for the most plausible battle-ID digit sequence.
     * Prioritises longer runs first (more digits = less likely to be a flag/enum).
     */
    private fun extractIdFromRawText(text: String): String? {
        // Only accept 5-10 digit sequences; reject timestamps (13 digits, ~1.78T in 2026)
        return Regex("[0-9]{5,10}").findAll(text)
            .map { it.value }
            .firstOrNull { it.toLongOrNull()?.let { v -> v in 10_000L..9_999_999_999L } == true }
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
                val name = params?.let { p ->
                    (readProtoFields(p)[1] as? ByteArray)?.toString(Charsets.UTF_8)
                } ?: "SFA-NEBU-1"
                GameEvent.HandshakeOut(name)
            }

            command == "HANDSHAKE" && !isOut -> {
                val token = params?.let { p ->
                    val top = (readProtoFields(p)[2] as? ByteArray)
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

            command in BATTLE_START_COMMANDS -> {
                val battleId = params?.let { extractBattleId(it) } ?: "?"
                GameEvent.BattleStarted(battleId)
            }

            command == "finish_fight" && !isOut -> {
                // Server confirming the fight is finished = win confirmed
                val battleId = params?.let { extractBattleId(it) }
                GameEvent.WinConfirmed(battleId ?: "?")
            }

            command in BATTLE_COMMANDS -> {
                val battleId = params?.let { extractBattleId(it) }
                GameEvent.BattleCommand(command, battleId, isOut)
            }

            else -> GameEvent.Command(command, isOut)
        }
    }

    // ── Login extraction ──────────────────────────────────────────────────

    private fun extractLoginCredentials(params: ByteArray?): Pair<String, String> {
        if (params == null) return "?" to "?"
        return try {
            val authWrapper = (readProtoFields(params)[2] as? ByteArray) ?: return scanRaw(params)
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

    // ── Battle ID extraction (multi-strategy) ─────────────────────────────

    private fun extractBattleId(params: ByteArray): String? {
        // Strategy 1: proto field scan (multiple depths)
        val fromProto = extractIdFromProto(params, 0)
        if (fromProto != null) return fromProto

        // Strategy 2: raw text scan of param bytes
        return extractIdFromRawText(params.toString(Charsets.ISO_8859_1))
    }

    private fun extractIdFromProto(data: ByteArray, depth: Int): String? {
        if (depth > 4) return null
        return try {
            val fields = readProtoFields(data)
            // Collect candidates, rejecting timestamps (>=10^12 = ms-epoch in 2026)
            // and tiny flags/enums (<10_000). Prefer first match over largest.
            val candidates = mutableListOf<Long>()
            for ((_, v) in fields) {
                when (v) {
                    is Long -> {
                        // Game IDs are plausibly 5-10 digits; timestamps are 13+ digits
                        if (v in 10_000L..9_999_999_999L) candidates.add(v)
                    }
                    is ByteArray -> {
                        // Pure digit string field
                        val s = v.toString(Charsets.UTF_8)
                        if (s.isNotEmpty() && s.all { it.isDigit() } && s.length in 5..10) {
                            val n = s.toLongOrNull()
                            if (n != null && n in 10_000L..9_999_999_999L) return s
                        }
                        // Recurse into nested proto
                        val nested = extractIdFromProto(v, depth + 1)
                        if (nested != null) return nested
                    }
                }
            }
            // Prefer first (lowest field number = most likely primary ID)
            candidates.firstOrNull()?.toString()
        } catch (_: Exception) { null }
    }

    // ── Minimal protobuf reader ───────────────────────────────────────────

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
                1 -> pos += 8
                5 -> pos += 4
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

    // ── Raw deflate ───────────────────────────────────────────────────────

    private fun rawDeflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater(true)
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
