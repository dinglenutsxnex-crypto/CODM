package com.nexora.hammerscale.net

import com.nexora.hammerscale.model.GameEvent
import com.nexora.hammerscale.model.LiveMessage
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
 *   field[1] varint  = controller  (23=battle-start server, 34=battle-finish client, etc.)
 *   field[2] string  = command     ("HANDSHAKE", "LOGIN", "event_battle_start_fight", etc.)
 *   field[3] bytes   = params (nested protobuf)
 *
 * ── Battle ID extraction (from captured packets) ──────────────────────────
 * Outbound event_battle_start_fight params: {field[1] varint = battleId}
 * Outbound event_battle_finish_fight params: {field[1]=battleId, field[4]=3, ...}
 * → Battle ID is ALWAYS params.field[1] varint in outbound packets.
 *
 * Server's inbound event_battle_start_fight has completely different params
 * (field[1] = 60926, not a battle ID) → BattleStarted must only fire for outbound.
 */
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

        // ONLY fire BattleStarted for outbound packets — server echoes the same
        // command with entirely different params that don't contain the battle ID.
        // Check event_battle_start_fight FIRST because start_fight is a substring of it —
        // a set iteration order is undefined so we'd risk misclassifying the high-priority
        // hero fight as a low-priority clan fight if start_fight matched first.
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

        // Check clan_finish_fight before finish_fight — clan_finish_fight contains "finish_fight"
        // as a substring so order matters to avoid misclassification.
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

    /**
     * From captured packets, SF3 battle IDs are 5–9 digit incrementing counters
     * (e.g. 3001602). Values >= 1,000,000,000 are Unix-second timestamps or
     * player/session IDs. Values < 10,000 are flags/enums.
     */
    private fun isBattleIdCandidate(v: Long): Boolean =
        v in 10_000L..999_999_999L

    private fun extractIdFromRawText(text: String): String? {
        return Regex("[0-9]{5,9}").findAll(text)
            .mapNotNull { m -> m.value.toLongOrNull()?.let { v -> if (isBattleIdCandidate(v)) m.value else null } }
            .minByOrNull { it.toLong() }
    }

    // ── Counter extraction (public — used by ViewModel to track session seq) ─

    /**
     * Returns the outbound packet counter (field[1] in the SF3 envelope) from
     * a raw framed packet, or null if the packet can't be parsed.
     *
     * The counter increments with every packet the client sends. Injected packets
     * must use counter = (last seen outbound counter) + 1 so the server doesn't
     * treat them as duplicates.
     */
    fun extractCounter(data: ByteArray): Long? {
        val payload = extractPayload(data) ?: return null
        return (readProtoFields(payload)[1] as? Long)?.takeIf { it > 0 }
    }

    /**
     * If [data] is a COMPLETE outbound event_battle_finish_fight SF3 frame, returns
     * Pair(battleId, counter) so TcpHandler can build a replacement WIN packet.
     * Returns null for any other command, partial frame, or parse failure.
     *
     * This is used by the "ARM WIN" intercept path: instead of injecting mid-fight
     * (which the game client ignores because its state machine is in "playing" mode),
     * HAMMERSCALE waits for the game's own finish_fight, replaces it with a WIN packet
     * using the SAME counter, so the server responds on the connection the game was
     * already waiting on → game client processes the win and shows the WIN screen.
     */
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

    /**
     * Returns true if [data] is a complete outbound raid_fight_finish SF3 frame.
     * Used by the raid damage intercept in TcpHandler to identify the packet to patch.
     */
    fun tryExtractRaidFightFinish(data: ByteArray): Boolean {
        val payload = extractPayload(data) ?: return false
        val fields  = readProtoFields(payload)
        val cmd     = (fields[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return false
        return cmd == "raid_fight_finish"
    }

    /**
     * Parses the server's inbound event_battle_start_fight response and returns the
     * 0-indexed sub-battle sequence number from params field[3].
     *
     * Confirmed from captures (4-battle skeletal event):
     *   fight_107: field[3] absent → sub-battle 0  (first fight)
     *   fight_141: field[3] = 1   → sub-battle 1
     *   fight_147: field[3] = 2   → sub-battle 2
     *   fight_154: field[3] = 3   → sub-battle 3
     *
     * Returns null if [data] is not an inbound event_battle_start_fight or cannot be parsed.
     * Returns 0 when field[3] is absent (first sub-battle in a sequence, or single-fight battle).
     */
    fun extractBattleSeqFromServerStart(data: ByteArray): Int? {
        return try {
            val payload = extractPayload(data) ?: return null
            val outer   = readProtoFields(payload)
            val cmd     = (outer[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
            if (cmd != "event_battle_start_fight") return null
            // field[3] (sub-battle index) is NOT directly in outer[3] (the raw params blob).
            // It lives at outer[3] → [2] → [1] — the battle config object confirmed from captures:
            //   fight_107: field[3] absent → 0 (first)
            //   fight_141: field[3] = 1   → second
            //   fight_147: field[3] = 2   → third
            //   fight_154: field[3] = 3   → fourth
            val params   = outer[3] as? ByteArray ?: return null
            val f3       = readProtoFields(params)
            val bigBlob  = f3[2]    as? ByteArray ?: return null
            val bcFields = readProtoFields(bigBlob)
            val bc       = bcFields[1] as? ByteArray ?: return null
            val subIdx   = (readProtoFields(bc)[3] as? Long)?.toInt() ?: 0
            subIdx
        } catch (_: Exception) { null }
    }

    /**
     * Returns true if [data] is a complete outbound brawler_finish SF3 frame.
     * Used by the brawler WIN intercept in TcpHandler to identify the packet to patch.
     */
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

    /**
     * Same as [tryExtractFinishFight] but for clan_finish_fight.
     * Returns Pair(battleId, counter) if [data] is a complete outbound clan_finish_fight frame.
     * The field layout is identical to event_battle_finish_fight — params.field[1] = battleId.
     */
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

    /**
     * Parses the server's inbound clan_start_fight response frame and extracts the
     * number of rounds for the battle.
     *
     * Navigation path (confirmed from hammerscale capture):
     *   outer envelope field[3] (params)
     *     → field[2] (big inventory/config blob)
     *       → field[1] (battle config outer, 8575B)
     *         → field[1] (battle config inner, 7926B)
     *           → field[10] = rounds (varint, e.g. 2)
     *
     * Returns null if [data] is not a clan_start_fight server response, is not fully
     * parseable, or if the extracted round count is outside the sane range 1–10.
     */
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

    // ── Framing ───────────────────────────────────────────────────────────

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

            command in BATTLE_START_COMMANDS && isOut -> {
                // Battle ID is always params.field[1] varint in outbound packets.
                // The server echoes the same command name but with completely different
                // params (field[1] = 60926, a session/room ID, not the battle counter).
                // Pass commandName through so the ViewModel can apply priority logic:
                // event_battle_start_fight (hero fight) always overrides currentBattle;
                // start_fight (clan/brawler) only sets if currentBattle is null.
                val battleId = params?.let { extractBattleIdDirect(it) } ?: "?"
                GameEvent.BattleStarted(battleId, command)
            }

            command in BATTLE_START_COMMANDS && !isOut -> {
                // Server echo of event_battle_start_fight / start_fight.
                // Parse params to extract the sub-battle sequence index (field[3])
                // and any other interesting fields so they appear in the dev log.
                val detail = buildString {
                    if (params != null) {
                        try {
                            // Navigate to the battle config object: params[2][1]
                            val f3      = readProtoFields(params)
                            val bigBlob = f3[2] as? ByteArray
                            val bc      = bigBlob?.let { readProtoFields(it)[1] as? ByteArray }
                            val bcFields = bc?.let { readProtoFields(it) } ?: emptyMap()
                            // field[3] = 0-indexed sub-battle number (absent on fight 0)
                            val seq = (bcFields[3] as? Long)?.toInt()
                            append("seq=${seq ?: 0}")
                            if (seq == null) append("  (field[3] absent → first fight)")
                            else             append("  (fight ${seq + 1})")
                            // Dump scalar fields from the battle config for debugging
                            bcFields.forEach { (fn, v) ->
                                if (fn == 3) return@forEach   // already shown as seq
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
                // Server confirmed the battle ended.
                // IMPORTANT: for event_battle_finish_fight the server's params.field[1] is
                // its own sequential fight counter (e.g. 61028), NOT the client's battle
                // template ID (e.g. 3001602). Reading it with extractBattleIdDirect would
                // produce a WinConfirmed ID that never matches currentBattle → stuck state.
                // Use "?" (wildcard) for hero-fight confirmations so the ViewModel always
                // clears regardless of ID. For other end commands (finish_fight clan etc.)
                // the server does echo back the client's battle ID in field[1], so match normally.
                //
                // ERROR GUARD: If params is null the server returned an error envelope
                // (field[4]=error_code, field[5]=error_string) — NOT a real win.
                // Emitting WinConfirmed for error responses is a false positive that shows
                // "WIN CONFIRMED" in the overlay even when the server rejected the request.
                // In that case emit BattleCommand so the error is visible in the events log
                // without triggering the "WIN CONFIRMED" UI state.
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

    // ── Brawler finish extraction ─────────────────────────────────────────

    /**
     * Parses an outbound brawler_finish params blob and returns a [GameEvent.BrawlerFinished].
     *
     * brawler_finish inner proto (confirmed from captured win + loss packets):
     *   field[1] bytes  = match info (opponent name/ID, round health events) — NOT a battle ID
     *   field[2] varint = result  1=WIN  3=LOSS
     *   field[3] varint = wonRounds   (2 on WIN / 1 on LOSS even when 0 rounds won)
     *   field[4] bytes  = round outcome entries (repeated; only present on WIN)
     *   field[5] varint = totalRounds (2 on WIN / absent on LOSS → defaults to 0)
     *   field[6] bytes  = equipped items (full on WIN / empty on LOSS)
     *   field[7] bytes  = fight stats   (54 bytes on WIN / 2 garbage bytes on LOSS)
     *
     * Frame type note: WIN is 0x02 (compressed, ~388B), LOSS is 0x01 (plain, ~189B).
     * The canonical check is field[2] in the inner proto, not the frame byte.
     *
     * Falls back to [GameEvent.BattleCommand] if params are null or unparseable.
     */
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

    // ── Battle ID extraction ───────────────────────────────────────────────

    /**
     * Direct extraction: battle ID is always params.field[1] varint.
     * Confirmed from captured outbound event_battle_start_fight and
     * event_battle_finish_fight packets — no heuristics needed.
     */
    private fun extractBattleIdDirect(params: ByteArray): String? {
        return try {
            val v = readProtoFields(params)[1]
            if (v is Long && isBattleIdCandidate(v)) v.toString() else null
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
        // Loop until the stream is fully decompressed.
        // The old condition `!finished() && !needsInput()` exited prematurely
        // when the inflater's internal output buffer was full but not yet drained,
        // causing partial decompression of large packets (e.g. the 33 KB finish_fight
        // server response).  The correct idiom is to keep calling inflate() until
        // finished() is true; needsInput() only becomes true if we supplied incomplete
        // compressed data, which should never happen after the stream reassembly fix.
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n > 0) out.write(buf, 0, n)
            else if (inflater.needsInput()) break  // incomplete input — stop gracefully
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    // ── JSON helper ───────────────────────────────────────────────────────

    private fun extractJsonValue(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
}
