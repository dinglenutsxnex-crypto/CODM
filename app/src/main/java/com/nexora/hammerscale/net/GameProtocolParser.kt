package com.nexora.hammerscale.net

import com.nexora.hammerscale.model.GameEvent
import com.nexora.hammerscale.model.LiveMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

object GameProtocolParser {

    private val BATTLE_COMMANDS = setOf(
        "brawler_start", "brawler_finish", "finish_fight",
        "refresh_battles", "cheat_generate_battle",
        "clan_refresh_battles", "start_fight", "get_battles",
        "event_battle_start_fight", "event_battle_finish_fight",
        "clan_start_fight", "clan_finish_fight"
    )

    private val BATTLE_START_COMMANDS = setOf("start_fight", "event_battle_start_fight", "clan_start_fight")
    private val BATTLE_END_COMMANDS   = setOf("finish_fight", "brawler_finish", "event_battle_finish_fight", "clan_finish_fight")

    fun parse(data: ByteArray, direction: LiveMessage.Direction): GameEvent? {
        if (data.size < 3) return null

        val proto = extractPayload(data)
        if (proto != null) {
            val protoResult = try { parseEnvelope(proto, direction) } catch (_: Exception) { null }
            if (protoResult != null) return protoResult
        }

        val rawText = data.toString(Charsets.ISO_8859_1)
        return rawTextScan(rawText, direction)
    }

    private fun rawTextScan(text: String, dir: LiveMessage.Direction): GameEvent? {
        val isOut = dir == LiveMessage.Direction.OUTBOUND

        if (isOut) {
            val cmd = when {
                text.contains("event_battle_start_fight") -> "event_battle_start_fight"
                text.contains("clan_start_fight")         -> "clan_start_fight"
                text.contains("start_fight")              -> "start_fight"
                else                                      -> null
            }
            if (cmd != null) {
                val id = extractIdFromRawText(text)
                return GameEvent.BattleStarted(id ?: "?", cmd)
            }
        }

        val endCmdOrdered = listOf("clan_finish_fight", "event_battle_finish_fight", "brawler_finish", "finish_fight")
        for (cmd in endCmdOrdered) {
            if (text.contains(cmd)) {
                val id = extractIdFromRawText(text)
                return if (!isOut) GameEvent.WinConfirmed(id ?: "?")
                else GameEvent.BattleCommand(cmd, id, isOut)
            }
        }
        return null
    }

    private fun isBattleIdCandidate(v: Long): Boolean =
        v in 10_000L..999_999_999L

    private fun extractIdFromRawText(text: String): String? {
        return Regex("[0-9]{5,9}").findAll(text)
            .mapNotNull { m -> m.value.toLongOrNull()?.let { v -> if (isBattleIdCandidate(v)) m.value else null } }
            .minByOrNull { it.toLong() }
    }

    fun extractCounter(data: ByteArray): Long? {
        val payload = extractPayload(data) ?: return null
        return (readProtoFields(payload)[1] as? Long)?.takeIf { it > 0 }
    }

    fun tryExtractFinishFight(data: ByteArray): Pair<Long, Long>? {
        val payload = extractPayload(data) ?: return null
        val fields  = readProtoFields(payload)
        val counter = (fields[1] as? Long)?.takeIf { it > 0 } ?: return null
        val cmd     = (fields[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
        if (cmd != "event_battle_finish_fight") return null
        val params  = fields[3] as? ByteArray ?: return null
        val battleId = (readProtoFields(params)[1] as? Long)
            ?.takeIf { isBattleIdCandidate(it) } ?: return null
        return battleId to counter
    }

    fun tryExtractRaidFightFinish(data: ByteArray): Boolean {
        val payload = extractPayload(data) ?: return false
        val fields  = readProtoFields(payload)
        val cmd     = (fields[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return false
        return cmd == "raid_fight_finish"
    }

    fun extractBattleSeqFromServerStart(data: ByteArray): Int? {
        return try {
            val payload = extractPayload(data) ?: return null
            val outer   = readProtoFields(payload)
            val cmd     = (outer[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
            if (cmd != "event_battle_start_fight") return null
            val params   = outer[3] as? ByteArray ?: return null
            val f3       = readProtoFields(params)
            val bigBlob  = f3[2]    as? ByteArray ?: return null
            val bcFields = readProtoFields(bigBlob)
            val bc       = bcFields[1] as? ByteArray ?: return null
            val subIdx   = (readProtoFields(bc)[3] as? Long)?.toInt() ?: 0
            subIdx
        } catch (_: Exception) { null }
    }

    fun tryExtractBrawlerFinish(data: ByteArray): Boolean {
        var pos = 0
        while (pos < data.size) {
            val t = data[pos].toInt() and 0xFF
            when (t) {
                0x01 -> {
                    if (pos + 2 > data.size) break
                    val len = data[pos + 1].toInt() and 0xFF
                    if (pos + 2 + len > data.size) break
                    val payload = data.copyOfRange(pos + 2, pos + 2 + len)
                    val cmd = (readProtoFields(payload)[2] as? ByteArray)?.toString(Charsets.UTF_8)
                    if (cmd == "brawler_finish") return true
                    pos += 2 + len
                }
                0x02 -> {
                    if (pos + 5 > data.size) break
                    val compLen = ByteBuffer.wrap(data, pos + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (compLen <= 0 || pos + 5 + compLen > data.size) break
                    val payload = rawDeflate(data.copyOfRange(pos + 5, pos + 5 + compLen)) ?: break
                    val cmd = (readProtoFields(payload)[2] as? ByteArray)?.toString(Charsets.UTF_8)
                    if (cmd == "brawler_finish") return true
                    pos += 5 + compLen
                }
                else -> pos++
            }
        }
        return false
    }

    fun tryExtractClanFinishFight(data: ByteArray): Pair<Long, Long>? {
        val payload = extractPayload(data) ?: return null
        val fields  = readProtoFields(payload)
        val counter = (fields[1] as? Long)?.takeIf { it > 0 } ?: return null
        val cmd     = (fields[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
        if (cmd != "clan_finish_fight") return null
        val params  = fields[3] as? ByteArray ?: return null
        val battleId = (readProtoFields(params)[1] as? Long)
            ?.takeIf { isBattleIdCandidate(it) } ?: return null
        return battleId to counter
    }

    fun extractClanRoundsFromStartResponse(data: ByteArray): Int? {
        return try {
            val payload = extractPayload(data) ?: return null
            val outer   = readProtoFields(payload)
            val cmd = (outer[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
            if (cmd != "clan_start_fight") return null
            val params   = outer[3]  as? ByteArray ?: return null
            val f3       = readProtoFields(params)
            val bigBlob  = f3[2]     as? ByteArray ?: return null
            val f2       = readProtoFields(bigBlob)
            val cfgOuter = f2[1]     as? ByteArray ?: return null
            val f1a      = readProtoFields(cfgOuter)
            val cfgInner = f1a[1]    as? ByteArray ?: return null
            val f1b      = readProtoFields(cfgInner)
            (f1b[10] as? Long)?.toInt()?.takeIf { it in 1..10 }
        } catch (_: Exception) { null }
    }

    fun extractPayload(data: ByteArray): ByteArray? {
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

            command in BATTLE_START_COMMANDS && isOut -> {
                val battleId = params?.let { extractBattleIdDirect(it) } ?: "?"
                GameEvent.BattleStarted(battleId, command)
            }

            command in BATTLE_START_COMMANDS && !isOut -> {
                val detail = buildString {
                    if (params != null) {
                        try {
                            val f3      = readProtoFields(params)
                            val bigBlob = f3[2] as? ByteArray
                            val bc      = bigBlob?.let { readProtoFields(it)[1] as? ByteArray }
                            val bcFields = bc?.let { readProtoFields(it) } ?: emptyMap()
                            val seq = (bcFields[3] as? Long)?.toInt()
                            append("seq=${seq ?: 0}")
                            if (seq == null) append("  (field[3] absent → first fight)")
                            else             append("  (fight ${seq + 1})")
                            bcFields.forEach { (fn, v) ->
                                if (fn == 3) return@forEach
                                when (v) {
                                    is Long      -> append("\nfield[$fn]=$v")
                                    is ByteArray -> {
                                        val s = v.toString(Charsets.UTF_8)
                                        if (s.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' })
                                            append("\nfield[$fn]=\"$s\"")
                                        else
                                            append("\nfield[$fn]=bytes(${v.size})")
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            append("(parse error)")
                        }
                    } else {
                        append("(no params)")
                    }
                }
                GameEvent.Command(command, false, detail)
            }

            command == "brawler_finish" && isOut -> {
                parseBrawlerFinish(params)
            }

            command in BATTLE_END_COMMANDS && isOut -> {
                val battleId = params?.let { extractBattleIdDirect(it) }
                GameEvent.BattleCommand(command, battleId, true)
            }

            command in BATTLE_END_COMMANDS && !isOut -> {
                if (command == "event_battle_finish_fight" && params == null) {
                    return GameEvent.BattleCommand(command, null, false)
                }
                val battleId = if (command == "event_battle_finish_fight") null
                               else params?.let { extractBattleIdDirect(it) }
                GameEvent.WinConfirmed(battleId ?: "?")
            }

            command in BATTLE_COMMANDS -> {
                val battleId = params?.let { extractBattleIdDirect(it) }
                GameEvent.BattleCommand(command, battleId, isOut)
            }

            else -> GameEvent.Command(command, isOut)
        }
    }

    private fun parseBrawlerFinish(params: ByteArray?): GameEvent {
        if (params == null) return GameEvent.BattleCommand("brawler_finish", null, true)
        return try {
            val inner = readProtoFields(params)
            val resultCode  = (inner[2] as? Long)?.toInt() ?: -1
            val wonRounds   = (inner[3] as? Long)?.toInt() ?: 0
            val totalRounds = (inner[5] as? Long)?.toInt() ?: 0
            val result = when (resultCode) {
                1    -> "WIN"
                3    -> "LOSS"
                else -> "RESULT_$resultCode"
            }
            GameEvent.BrawlerFinished(result, wonRounds, totalRounds)
        } catch (_: Exception) {
            GameEvent.BattleCommand("brawler_finish", null, true)
        }
    }

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

    private fun extractBattleIdDirect(params: ByteArray): String? {
        return try {
            val v = readProtoFields(params)[1]
            if (v is Long && isBattleIdCandidate(v)) v.toString() else null
        } catch (_: Exception) { null }
    }

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

    private fun rawDeflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n > 0) out.write(buf, 0, n)
            else if (inflater.needsInput()) break
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun extractJsonValue(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
}
//skibidi
