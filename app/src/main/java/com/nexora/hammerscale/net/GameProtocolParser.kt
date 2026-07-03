package com.nexora.hammerscale.net

import com.nexora.hammerscale.model.GameEvent
import com.nexora.hammerscale.model.LiveMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

// Parses the SF3 custom binary protocol.
//
// Small packet:  [0x01][1B length][raw protobuf payload]
// Large packet:  [0x02][4B LE length][raw-deflate compressed protobuf payload]
//
// Outer protobuf envelope:
//   field[1] varint  = controller  (23=battle-start server, 34=battle-finish client, etc.)
//   field[2] string  = command     ("HANDSHAKE", "LOGIN", "event_battle_start_fight", etc.)
//   field[3] bytes   = params (nested protobuf)
//
// Battle ID is always params.field[1] varint in outbound packets. The server's inbound
// event_battle_start_fight has different params (field[1] isn't a battle ID), so
// BattleStarted only fires for outbound packets.
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

        // Only fire BattleStarted for outbound packets — the server echoes the same
        // command with different params that don't contain the battle ID. Check
        // event_battle_start_fight before start_fight since the latter is a substring.
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

        // Check clan_finish_fight before finish_fight since the latter is a substring.
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

    // SF3 battle IDs are 5-9 digit incrementing counters (e.g. 3001602). Values
    // >= 1,000,000,000 are timestamps/session IDs; values < 10,000 are flags/enums.
    private fun isBattleIdCandidate(v: Long): Boolean =
        v in 10_000L..999_999_999L

    private fun extractIdFromRawText(text: String): String? {
        return Regex("[0-9]{5,9}").findAll(text)
            .mapNotNull { m -> m.value.toLongOrNull()?.let { v -> if (isBattleIdCandidate(v)) m.value else null } }
            .minByOrNull { it.toLong() }
    }

    // The counter increments with every packet the client sends. Injected packets
    // must use counter = (last seen outbound counter) + 1 so the server doesn't
    // treat them as duplicates.
    fun extractCounter(data: ByteArray): Long? {
        val payload = extractPayload(data) ?: return null
        return (readProtoFields(payload)[1] as? Long)?.takeIf { it > 0 }
    }

    // If [data] is a complete outbound event_battle_finish_fight frame, returns
    // Pair(battleId, counter) so TcpHandler can build a replacement WIN packet using
    // the same counter — the server then responds on the connection the game is
    // already waiting on, and the game client shows the win screen normally.
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

    // Parses the server's inbound event_battle_start_fight response and returns the
    // 0-indexed sub-battle sequence number from params field[3] (absent = 0, i.e. the
    // first fight in a sequence). Returns null if the frame isn't a matching command
    // or can't be parsed.
    fun extractBattleSeqFromServerStart(data: ByteArray): Int? {
        return try {
            val payload = extractPayload(data) ?: return null
            val outer   = readProtoFields(payload)
            val cmd     = (outer[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
            if (cmd != "event_battle_start_fight") return null
            // The sub-battle index lives at outer[3] -> [2] -> [1] inside the nested
            // battle config object, not directly in the params blob.
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

    // Same as tryExtractFinishFight but for clan_finish_fight (identical field layout).
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

    // Parses the server's inbound clan_start_fight response and extracts the round
    // count by walking outer[3] -> [2] -> [1] -> [1] -> field[10]. Returns null if
    // this isn't a clan_start_fight response, isn't fully parseable, or the round
    // count falls outside the sane range 1-10.
    fun extractClanRoundsFromStartResponse(data: ByteArray): Int? {
        return try {
            val payload = extractPayload(data) ?: return null
            val outer   = readProtoFields(payload)
            // Only fire on inbound clan_start_fight (server echo / response)
            val cmd = (outer[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
            if (cmd != "clan_start_fight") return null
            // Navigate: outer[3] → [2] → [1] → [1] → field[10]
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
                // The server echoes the same command name with different params
                // (a session/room ID rather than the battle counter). commandName is
                // passed through so the ViewModel can prioritize hero fights over clan/brawler.
                val battleId = params?.let { extractBattleIdDirect(it) } ?: "?"
                GameEvent.BattleStarted(battleId, command)
            }

            command in BATTLE_START_COMMANDS && !isOut -> {
                val detail = buildString {
                    if (params != null) {
                        try {
                            // Battle config object lives at params[2][1]
                            val f3      = readProtoFields(params)
                            val bigBlob = f3[2] as? ByteArray
                            val bc      = bigBlob?.let { readProtoFields(it)[1] as? ByteArray }
                            val bcFields = bc?.let { readProtoFields(it) } ?: emptyMap()
                            // field[3] = 0-indexed sub-battle number (absent on fight 0)
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
                // For event_battle_finish_fight the server's params.field[1] is its own
                // sequential fight counter, not the client's battle template ID, so we use
                // "?" as a wildcard to always clear currentBattle. Other end commands echo
                // back the client's battle ID and match normally.
                //
                // A null params here means the server returned an error envelope rather
                // than a real win — emit BattleCommand instead so it shows up as an error
                // in the log without falsely triggering "WIN CONFIRMED".
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

    // brawler_finish inner proto: field[1]=match info, field[2]=result (1=WIN, 3=LOSS),
    // field[3]=wonRounds, field[4]=round outcome entries (WIN only), field[5]=totalRounds,
    // field[6]=equipped items, field[7]=fight stats. The frame byte (0x01/0x02) isn't a
    // reliable WIN/LOSS signal — field[2] in the inner proto is the canonical check.
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
        // Keep calling inflate() until finished() is true — checking needsInput()
        // as an exit condition too early cuts off large packets (e.g. the 33 KB
        // finish_fight server response) before the output buffer is fully drained.
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
