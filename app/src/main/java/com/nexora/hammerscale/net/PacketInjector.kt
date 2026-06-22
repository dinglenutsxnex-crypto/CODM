package com.nexora.hammerscale.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Builds SF3 wire-format packets for injection.
 *
 * Wire format:
 *   Small  [0x01][1B len][protobuf envelope]          payload <= 255 bytes
 *   Large  [0x02][4B LE len][raw-deflate protobuf envelope]  payload > 255 bytes
 *
 * Outer protobuf envelope:
 *   field[1] varint = counter   (session packet sequence number — must be last seen + 1)
 *   field[2] string = command
 *   field[3] bytes  = params
 *
 * WIN params verified from 3002601_battle_win capture / user_194.7.bin (REAL WIN):
 *
 *   field[1]  varint = battleId
 *   field[4]  varint = 1       WIN  ← NOT 3. The game sends 3 on a LOSS — do NOT copy that.
 *   field[5]  varint = rounds   wonRounds  (absent in loss packet)
 *   field[6]  bytes  = {field[1] = ts_ms}  live timestamp proto
 *   field[7]  varint = rounds   totalRounds  (game sends 1 on loss)
 *   field[10] bytes  = WIN_ITEMS  equipped items (empty in loss)
 *   field[13] bytes  = WIN_STATS  71-byte fight stats (2-byte junk in loss)
 *   field[14] varint = 28      player level (absent in loss)
 */
object PacketInjector {

    // ── Brawler WIN constants (verified from user_137.3.bin WIN capture) ─────
    // Items:  4 equipped items (IDs 1617, 1618, 6617, 6620; levels 1, 1, 2, 2)
    // Stats:  54-byte fight stats from the real brawler WIN packet
    // Rounds: 5 round sub-messages from brawler WIN inner proto field[4]
    private val BRAWLER_WIN_ITEMS = byteArrayOf(
        0x0a, 0x05, 0x08, 0xd1.toByte(), 0x0c, 0x10, 0x01,
        0x0a, 0x05, 0x08, 0xd2.toByte(), 0x0c, 0x10, 0x01,
        0x0a, 0x05, 0x08, 0xd9.toByte(), 0x34, 0x10, 0x02,
        0x0a, 0x05, 0x08, 0xdc.toByte(), 0x34, 0x10, 0x02
    )
    private val BRAWLER_WIN_STATS = byteArrayOf(
        0x08, 0x02, 0x10, 0x1f, 0x1a, 0x02, 0x01, 0x01,
        0x22, 0x02, 0x09, 0x05, 0x2a, 0x02, 0x01, 0x01,
        0x32, 0x08, 0x00, 0x00, 0x80.toByte(), 0x3f, 0x00, 0x00, 0x80.toByte(), 0x3f,
        0x3a, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x42, 0x08, 0x66, 0x66, 0xe6.toByte(), 0x3e, 0x66, 0x66, 0xe6.toByte(), 0x3e,
        0x4a, 0x02, 0x03, 0x03, 0x52, 0x02, 0x00, 0x00
    )
    // 5 round entries for inner proto field[4] (repeated bytes sub-messages)
    private val BRAWLER_WIN_ROUND_ENTRIES = arrayOf(
        byteArrayOf(0x08, 0x03, 0x10, 0x01),
        byteArrayOf(0x08, 0x04, 0x10, 0x02),
        byteArrayOf(0x08, 0x05, 0x10, 0x03),
        byteArrayOf(0x08, 0x06, 0x10, 0x02),
        byteArrayOf(0x08, 0x07)
    )

    // ── WIN constants from 3002601_battle_win / user_194.7.bin ───────────────
    // Items:  two equipped items, IDs 1617 + 1618, both level 4
    // Stats:  71-byte fight stats blob from the real win packet
    // Level:  28 (field[14] in the real win packet)
    private val WIN_ITEMS = byteArrayOf(
        0x0a, 0x05, 0x08, 0xd1.toByte(), 0x0c, 0x10, 0x04,
        0x0a, 0x05, 0x08, 0xd2.toByte(), 0x0c, 0x10, 0x04
    )
    private val WIN_STATS = byteArrayOf(
        0x08, 0x1c, 0x10, 0x4c, 0x1a, 0x03, 0x08, 0x0c, 0x08, 0x22, 0x03, 0x10, 0x12, 0x0e,
        0x2a, 0x03, 0x04, 0x07, 0x04, 0x32, 0x0c, 0x00, 0x00, 0x80.toByte(), 0x3f, 0xb2.toByte(),
        0xd7.toByte(), 0x7b, 0x3f, 0x00, 0x00, 0x80.toByte(), 0x3f, 0x3a, 0x0c, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x42, 0x0c, 0xd6.toByte(), 0xa3.toByte(),
        0x60, 0x3e, 0xd6.toByte(), 0xa3.toByte(), 0x60, 0x3e, 0xd8.toByte(), 0xa3.toByte(), 0x60, 0x3e,
        0x4a, 0x03, 0x26, 0x3c, 0x24, 0x52, 0x03, 0x00, 0x01, 0x00
    )
    private const val WIN_LEVEL = 28L

    /**
     * Surgically patch the game's own finish_fight packet to report a WIN.
     *
     * Takes the raw SF3 frame the game was about to send, makes a copy, and makes
     * the minimum edits required for the server to accept it as a WIN:
     *
     *   1. Flips params.field[4] from 3 (LOSS) → 1 (WIN) in-place.
     *   2. Fixes params.field[7] (totalRounds) in-place to [roundsToWin].
     *   3. Appends params.field[5] = [roundsToWin] (wonRounds) if absent.
     *      The server validates "check result and wonRounds" — result=WIN without
     *      wonRounds (or with wonRounds ≠ totalRounds) gets an IllegalArgumentException.
     *
     * All other bytes — battleId, counter, seed, items, stats, level — are
     * preserved exactly as the game sent them.
     *
     * Returns the patched packet on success, or null if parsing fails (caller logs and drops).
     */
    fun patchFinishFightToWin(data: ByteArray, roundsToWin: Int = 3): ByteArray? {
        if (data.size < 3 || (data[0].toInt() and 0xFF) != 0x01) return null
        val frameLen = data[1].toInt() and 0xFF
        val protoEnd = 2 + frameLen
        if (data.size < protoEnd) return null

        // Walk the outer envelope to find field[3] (params)
        var pos = 2
        while (pos < protoEnd) {
            val tagByte = data[pos].toInt() and 0xFF
            pos++
            val fieldNum = tagByte ushr 3
            val wireType = tagByte and 7
            when (wireType) {
                0 -> { while (pos < protoEnd && (data[pos].toInt() and 0x80) != 0) pos++; pos++ }
                2 -> {
                    val paramsLenBytePos = pos  // remember where the length byte is
                    var len = 0; var shift = 0; var lenByteCount = 0
                    while (pos < protoEnd) {
                        val b = data[pos++].toInt() and 0xFF; lenByteCount++
                        len = len or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (fieldNum != 3) { pos += len; continue }

                    // params length must fit in one varint byte (<128) for simple surgery
                    if (lenByteCount != 1) return null

                    val paramsStart = pos
                    val paramsEnd   = pos + len

                    // First pass: collect positions and values for field[4], field[5], field[7]
                    var field4ValPos   = -1
                    var field5ValPos   = -1
                    var field7ValPos   = -1
                    var field5Present  = false
                    var pp = paramsStart
                    while (pp < paramsEnd) {
                        val ptag = data[pp].toInt() and 0xFF; pp++
                        val pf = ptag ushr 3; val pw = ptag and 7
                        when (pw) {
                            0 -> {
                                val vPos = pp
                                var v = 0L; var vs = 0
                                while (pp < paramsEnd && (data[pp].toInt() and 0x80) != 0) {
                                    v = v or ((data[pp].toLong() and 0x7F) shl vs); vs += 7; pp++
                                }
                                v = v or ((data[pp].toLong() and 0x7F) shl vs); pp++
                                when (pf) {
                                    4 -> field4ValPos = vPos
                                    5 -> { field5Present = true; field5ValPos = vPos }
                                    7 -> field7ValPos = vPos
                                }
                            }
                            2 -> {
                                var plen = 0; var ps2 = 0
                                while (pp < paramsEnd) {
                                    val b = data[pp++].toInt() and 0xFF
                                    plen = plen or ((b and 0x7F) shl ps2)
                                    if (b and 0x80 == 0) break; ps2 += 7
                                }
                                pp += plen
                            }
                            else -> return null
                        }
                    }

                    if (field4ValPos < 0) return null  // field[4] not found in params

                    val rVal = (roundsToWin and 0x7F).toByte()

                    return if (field5Present) {
                        // field[5] already present — fix field[4], field[5], and field[7] in-place.
                        data.copyOf().also { copy ->
                            copy[field4ValPos] = 0x01
                            if (field5ValPos >= 0) copy[field5ValPos] = rVal
                            if (field7ValPos >= 0) copy[field7ValPos] = rVal
                        }
                    } else {
                        // Append field[5] = roundsToWin after existing params;
                        // also fix field[7] (totalRounds) in-place to roundsToWin.
                        // field[5] tag = (5 shl 3) or 0 = 0x28
                        // Round values are always 1–3, so single-byte varint is safe.
                        // params is always the last envelope field, so appending to the
                        // packet is safe — nothing follows params in the SF3 envelope.
                        val extra = byteArrayOf(0x28, rVal)
                        val result = ByteArray(data.size + extra.size)
                        data.copyInto(result)
                        result[field4ValPos]     = 0x01                             // flip result → WIN
                        if (field7ValPos >= 0) result[field7ValPos] = rVal          // fix totalRounds
                        result[1]                = (frameLen + extra.size).toByte() // extend frame length
                        result[paramsLenBytePos] = (len + extra.size).toByte()      // extend params length
                        extra.copyInto(result, protoEnd)                            // append field[5]
                        result
                    }
                }
                else -> return null
            }
        }
        return null
    }

    /**
     * Patches the game's own raid_fight_finish packet so that params.field[2] fixed32
     * (the damage/boss_max_hp ratio) is set to 1.0 — boss reduced to 0 HP.
     *
     * Two cases handled:
     *   • field[2] present  (player dealt real damage): overwrite the 4 bytes in-place
     *     with [0x00, 0x00, 0x80, 0x3F] (LE IEEE 754 float 1.0).  No length changes.
     *   • field[2] absent   (player dealt 0 damage): the game omits the ratio field
     *     entirely.  In this case we inject tag 0x15 + 4-byte 1.0 at the front of the
     *     existing params blob and rebuild the envelope via envelope().  This means the
     *     intercept works even when you deal zero actual damage.
     *
     * Returns the patched frame on success, or null if the outer frame cannot be parsed.
     */
    fun patchRaidFightFinishToMaxDamage(data: ByteArray): ByteArray? {
        if (data.size < 3 || (data[0].toInt() and 0xFF) != 0x01) return null
        val frameLen = data[1].toInt() and 0xFF
        val protoEnd = 2 + frameLen
        if (data.size < protoEnd) return null

        var pos = 2
        var outerCounter = 0L  // captured from outer field[1] for envelope rebuild
        while (pos < protoEnd) {
            val tagByte = data[pos].toInt() and 0xFF; pos++
            val fieldNum = tagByte ushr 3; val wireType = tagByte and 7
            when (wireType) {
                0 -> {
                    // Read and capture the varint (needed for counter in rebuild path)
                    var v = 0L; var vs = 0
                    while (pos < protoEnd) {
                        val b = data[pos++].toInt() and 0xFF
                        v = v or ((b and 0x7F).toLong() shl vs); vs += 7
                        if (b and 0x80 == 0) break
                    }
                    if (fieldNum == 1) outerCounter = v
                }
                2 -> {
                    var len = 0; var shift = 0
                    while (pos < protoEnd) {
                        val b = data[pos++].toInt() and 0xFF
                        len = len or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break; shift += 7
                    }
                    if (fieldNum != 3) { pos += len; continue }
                    val paramsStart = pos
                    val paramsEnd   = pos + len

                    var pp = paramsStart
                    while (pp < paramsEnd) {
                        val ptag = data[pp].toInt() and 0xFF; pp++
                        val pf = ptag ushr 3; val pw = ptag and 7
                        when (pw) {
                            0 -> {
                                while (pp < paramsEnd && (data[pp].toInt() and 0x80) != 0) pp++
                                pp++
                            }
                            2 -> {
                                var plen = 0; var ps = 0
                                while (pp < paramsEnd) {
                                    val b = data[pp++].toInt() and 0xFF
                                    plen = plen or ((b and 0x7F) shl ps)
                                    if (b and 0x80 == 0) break; ps += 7
                                }
                                pp += plen
                            }
                            5 -> {
                                if (pf == 2 && pp + 4 <= paramsEnd) {
                                    // field[2] exists — overwrite in-place (player dealt some damage)
                                    return data.copyOf().also { copy ->
                                        copy[pp]     = 0x00
                                        copy[pp + 1] = 0x00
                                        copy[pp + 2] = 0x80.toByte()
                                        copy[pp + 3] = 0x3F
                                    }
                                }
                                pp += 4
                            }
                            1 -> pp += 8
                            else -> return null
                        }
                    }
                    // field[2] was absent — player dealt 0 damage so the game omitted the ratio
                    // field entirely. Inject field[2] = 1.0 (tag 0x15 = field 2 fixed32) by
                    // prepending it to the existing params bytes and rebuilding the envelope.
                    val injection = byteArrayOf(0x15, 0x00, 0x00, 0x80.toByte(), 0x3F)
                    val origParams = data.copyOfRange(paramsStart, paramsEnd)
                    val newParams  = injection + origParams
                    return envelope("raid_fight_finish", newParams, outerCounter)
                }
                else -> return null
            }
        }
        return null
    }

    /**
     * Intercepts an inbound server brawler_finish 0x02 frame and patches the result to WIN.
     *
     * Real-time brawler duels send fight data via a binary game-session protocol, NOT as an
     * SF3 brawler_finish command from the client. The server therefore never receives a
     * patchable outbound SF3 frame — it receives binary session data and autonomously sends
     * back an SF3 brawler_finish response. This function intercepts that INBOUND response
     * before it reaches the game client and flips the result field.
     *
     * Patch: params.field[2].field[5]  2 (LOSS) → 1 (WIN).
     * All other bytes (rewards, ELO delta, match stats) are preserved so the game can parse
     * the response normally; only the result indicator is changed.
     *
     * Returns patched data on success, or null if no complete brawler_finish frame is present
     * in [data] (frame arrives split across TCP reads — caller falls back to unpatched).
     */
    fun patchInboundBrawlerFinishToWin(data: ByteArray): ByteArray? {
        var pos = 0
        while (pos < data.size) {
            val t = data[pos].toInt() and 0xFF
            when (t) {
                0x02 -> {
                    if (pos + 5 > data.size) return null   // incomplete header — wait
                    val compLen = ByteBuffer.wrap(data, pos + 1, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int
                    if (compLen <= 0 || pos + 5 + compLen > data.size) return null  // incomplete frame
                    val rawProto = rawInflate(data.copyOfRange(pos + 5, pos + 5 + compLen))
                    if (rawProto != null) {
                        val patchedFrame = tryPatchInboundBrawlerProto(rawProto)
                        if (patchedFrame != null) {
                            return data.copyOfRange(0, pos) + patchedFrame +
                                   data.copyOfRange(pos + 5 + compLen, data.size)
                        }
                    }
                    pos += 5 + compLen
                }
                0x01 -> {
                    if (pos + 2 > data.size) return null
                    val len = data[pos + 1].toInt() and 0xFF
                    if (pos + 2 + len > data.size) return null
                    pos += 2 + len
                }
                else -> pos++
            }
        }
        return null
    }

    /**
     * Verifies the decompressed proto is a brawler_finish, locates the result byte
     * (params.field[2].field[5]), flips it to 1 (WIN), then re-encodes as a 0x02 frame.
     */
    private fun tryPatchInboundBrawlerProto(rawProto: ByteArray): ByteArray? {
        var counter = -1L
        var paramsStart = -1; var paramsLen = -1
        var isFinish = false
        var pos = 0
        // Walk outer envelope
        while (pos < rawProto.size) {
            val tr = readVarintAt(rawProto, pos) ?: break
            val tag = tr.first; pos += tr.second
            val fn = (tag shr 3).toInt(); val wt = (tag and 7L).toInt()
            when (wt) {
                0 -> {
                    val vr = readVarintAt(rawProto, pos) ?: break
                    if (fn == 1) counter = vr.first
                    pos += vr.second
                }
                2 -> {
                    val lr = readVarintAt(rawProto, pos) ?: break
                    pos += lr.second
                    val len = lr.first.toInt()
                    if (pos + len > rawProto.size) break
                    when (fn) {
                        2 -> isFinish = rawProto.copyOfRange(pos, pos + len)
                                            .toString(Charsets.UTF_8) == "brawler_finish"
                        3 -> { paramsStart = pos; paramsLen = len }
                    }
                    pos += len
                }
                else -> break
            }
        }
        if (!isFinish || counter < 0 || paramsStart < 0) return null

        // Walk params to find field[2]
        var f2Start = -1; var f2Len = -1
        pos = paramsStart
        val paramsEnd = paramsStart + paramsLen
        while (pos < paramsEnd) {
            val tr = readVarintAt(rawProto, pos) ?: break
            val tag = tr.first; pos += tr.second
            val fn = (tag shr 3).toInt(); val wt = (tag and 7L).toInt()
            when (wt) {
                0 -> { val vr = readVarintAt(rawProto, pos) ?: break; pos += vr.second }
                2 -> {
                    val lr = readVarintAt(rawProto, pos) ?: break
                    pos += lr.second
                    val len = lr.first.toInt()
                    if (pos + len > rawProto.size) break
                    if (fn == 2) { f2Start = pos; f2Len = len }
                    pos += len
                }
                1 -> { if (pos + 8 > rawProto.size) break; pos += 8 }
                5 -> { if (pos + 4 > rawProto.size) break; pos += 4 }
                else -> break
            }
        }
        if (f2Start < 0) return null

        // Walk params.field[2] to find field[5] (result varint)
        pos = f2Start
        val f2End = f2Start + f2Len
        while (pos < f2End) {
            val tr = readVarintAt(rawProto, pos) ?: break
            val tag = tr.first; pos += tr.second
            val fn = (tag shr 3).toInt(); val wt = (tag and 7L).toInt()
            when (wt) {
                0 -> {
                    val valuePos = pos
                    val vr = readVarintAt(rawProto, pos) ?: break
                    pos += vr.second
                    if (fn == 5) {
                        // Flip result to WIN (1) — single-byte varint, in-place safe
                        val patched = rawProto.copyOf()
                        patched[valuePos] = 0x01
                        val patchedParams = patched.copyOfRange(paramsStart, paramsStart + paramsLen)
                        return envelope("brawler_finish", patchedParams, counter)
                    }
                }
                2 -> {
                    val lr = readVarintAt(rawProto, pos) ?: break
                    pos += lr.second
                    val len = lr.first.toInt()
                    if (pos + len > rawProto.size) break
                    pos += len
                }
                1 -> { if (pos + 8 > rawProto.size) break; pos += 8 }
                5 -> { if (pos + 4 > rawProto.size) break; pos += 4 }
                else -> break
            }
        }
        return null
    }

    /**
     * Rebuilds the game's own brawler_finish packet so the server records a WIN.
     *
     * Unlike patchFinishFightToWin (surgical byte-flip), brawler_finish requires a
     * FULL inner-proto rebuild because WIN and LOSS have completely different field
     * sets: WIN has fields 1–7 (including 5 round entries in field[4]); LOSS is
     * missing fields 4, 5, and 6.
     *
     * Steps:
     *   1. Decode the game's frame (0x01 or 0x02) → extract outer counter + params.field[1]
     *      (match info bytes that identify the opponent — kept so the server can resolve
     *      the correct PvP session).
     *   2. Rebuild inner proto: field[1]=matchInfo, field[2]=1 (WIN), field[3]=2 (wonRounds),
     *      five field[4] round entries, field[5]=2 (totalRounds), field[6]=BRAWLER_WIN_ITEMS,
     *      field[7]=BRAWLER_WIN_STATS.
     *   3. Re-encode as 0x02 frame (always; the rebuilt WIN proto is always > 255 bytes).
     *
     * Returns null if the frame cannot be parsed (caller logs and drops the patch).
     */
    fun patchBrawlerFinishToWin(data: ByteArray): ByteArray? {
        // Find the brawler_finish SF3 frame — it may not be at byte 0 if multiple
        // SF3 frames were coalesced into one TCP segment by the kernel.
        var pos = 0
        while (pos < data.size) {
            val t = data[pos].toInt() and 0xFF
            when (t) {
                0x01 -> {
                    if (pos + 2 > data.size) return null
                    val len = data[pos + 1].toInt() and 0xFF
                    if (pos + 2 + len > data.size) return null
                    val rawProto = data.copyOfRange(pos + 2, pos + 2 + len)
                    val patched = tryPatchBrawlerProto(rawProto)
                    if (patched != null) {
                        val before = data.copyOfRange(0, pos)
                        val after  = data.copyOfRange(pos + 2 + len, data.size)
                        return before + patched + after
                    }
                    pos += 2 + len
                }
                0x02 -> {
                    if (pos + 5 > data.size) return null
                    val compLen = ByteBuffer.wrap(data, pos + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (compLen <= 0 || pos + 5 + compLen > data.size) return null
                    val rawProto = rawInflate(data.copyOfRange(pos + 5, pos + 5 + compLen)) ?: return null
                    val patched = tryPatchBrawlerProto(rawProto)
                    if (patched != null) {
                        val before = data.copyOfRange(0, pos)
                        val after  = data.copyOfRange(pos + 5 + compLen, data.size)
                        return before + patched + after
                    }
                    pos += 5 + compLen
                }
                else -> pos++
            }
        }
        return null
    }

    /**
     * Given a raw (decompressed) SF3 envelope proto that is a brawler_finish,
     * rebuilds the inner params as a full WIN and returns the re-encoded SF3 frame
     * (always 0x02 since the WIN proto exceeds 255 bytes). Returns null if this
     * proto is not a brawler_finish or if the counter cannot be extracted.
     */
    private fun tryPatchBrawlerProto(rawProto: ByteArray): ByteArray? {
        var pos = 0
        var counter = -1L
        var paramsBytes: ByteArray? = null
        // Quick command check before full parse
        var cmdFound = false
        var pp = 0
        while (pp < rawProto.size) {
            val tr = readVarintAt(rawProto, pp) ?: break
            val tag = tr.first; pp += tr.second
            val fieldNum = (tag shr 3).toInt()
            val wireType = (tag and 7L).toInt()
            when (wireType) {
                0 -> { val vr = readVarintAt(rawProto, pp) ?: break; pp += vr.second }
                2 -> {
                    val lr = readVarintAt(rawProto, pp) ?: break; pp += lr.second
                    val len = lr.first.toInt()
                    if (pp + len > rawProto.size) break
                    if (fieldNum == 2) {
                        val cmd = rawProto.copyOfRange(pp, pp + len).toString(Charsets.UTF_8)
                        if (cmd != "brawler_finish") return null
                        cmdFound = true
                    }
                    pp += len
                }
                else -> break
            }
        }
        if (!cmdFound) return null

        // Full parse: extract counter and params
        while (pos < rawProto.size) {
            val tr = readVarintAt(rawProto, pos) ?: break
            val tag = tr.first; pos += tr.second
            val fieldNum = (tag shr 3).toInt()
            val wireType = (tag and 7L).toInt()
            when (wireType) {
                0 -> {
                    val vr = readVarintAt(rawProto, pos) ?: break
                    if (fieldNum == 1) counter = vr.first
                    pos += vr.second
                }
                2 -> {
                    val lr = readVarintAt(rawProto, pos) ?: break
                    pos += lr.second
                    val len = lr.first.toInt()
                    if (pos + len > rawProto.size) break
                    if (fieldNum == 3) paramsBytes = rawProto.copyOfRange(pos, pos + len)
                    pos += len
                }
                else -> break
            }
        }
        if (counter < 0) return null

        val matchInfo: ByteArray? = paramsBytes?.let { extractBytesField1(it) }
        val newParams = proto {
            if (matchInfo != null) bytesField(1, matchInfo)
            varintField(2, 1L)
            varintField(3, 2L)
            for (entry in BRAWLER_WIN_ROUND_ENTRIES) bytesField(4, entry)
            varintField(5, 2L)
            bytesField(6, BRAWLER_WIN_ITEMS)
            bytesField(7, BRAWLER_WIN_STATS)
        }
        return envelope("brawler_finish", newParams, counter)
    }

    /** Extract the first field[1] bytes value from a proto blob; null on parse failure. */
    private fun extractBytesField1(data: ByteArray): ByteArray? {
        var pos = 0
        while (pos < data.size) {
            val tr = readVarintAt(data, pos) ?: return null
            val tag = tr.first; pos += tr.second
            val fieldNum = (tag shr 3).toInt()
            val wireType = (tag and 7L).toInt()
            when (wireType) {
                0 -> { val vr = readVarintAt(data, pos) ?: return null; pos += vr.second }
                2 -> {
                    val lr = readVarintAt(data, pos) ?: return null
                    pos += lr.second
                    val len = lr.first.toInt()
                    if (pos + len > data.size) return null
                    if (fieldNum == 1) return data.copyOfRange(pos, pos + len)
                    pos += len
                }
                else -> return null
            }
        }
        return null
    }

    /** Read a varint from [data] at [start]; returns (value, bytesConsumed) or null. */
    private fun readVarintAt(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L; var shift = 0; var i = start
        while (i < data.size) {
            val b = data[i++].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value to (i - start)
            shift += 7
            if (shift >= 64) break
        }
        return null
    }

    /** Raw inflate (windowBits = -15, no zlib header). Mirrors rawDeflate. */
    private fun rawInflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n > 0) out.write(buf, 0, n)
            else if (inflater.needsInput()) break
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    // ── Proto writer ──────────────────────────────────────────────────────

    private fun proto(block: ProtoWriter.() -> Unit): ByteArray {
        val w = ProtoWriter()
        w.block()
        return w.toByteArray()
    }

    private fun envelope(command: String, params: ByteArray, counter: Long): ByteArray {
        val body = proto {
            varintField(1, counter)
            stringField(2, command)
            bytesField(3, params)
        }
        return if (body.size <= 255) {
            byteArrayOf(0x01, body.size.toByte()) + body
        } else {
            val compressed = rawDeflate(body)
            val lenBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(compressed.size).array()
            byteArrayOf(0x02) + lenBytes + compressed
        }
    }

    /**
     * Raw deflate (no zlib header/trailer). Matches Python: zlib.compress(data, 6)[2:-4]
     * and is required for the SF3 large-packet (0x02) framing.
     */
    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(6, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    // ── Low-level proto writer ────────────────────────────────────────────

    private class ProtoWriter {
        private val buf = mutableListOf<Byte>()

        fun varintField(fieldNum: Int, value: Long) {
            writeVarint((fieldNum.toLong() shl 3) or 0L)
            writeVarint(value)
        }

        fun stringField(fieldNum: Int, value: String) {
            bytesField(fieldNum, value.toByteArray(Charsets.UTF_8))
        }

        fun bytesField(fieldNum: Int, bytes: ByteArray) {
            writeVarint((fieldNum.toLong() shl 3) or 2L)
            writeVarint(bytes.size.toLong())
            bytes.forEach { buf.add(it) }
        }

        private fun writeVarint(value: Long) {
            var v = value
            while (v and -0x80L != 0L) {
                buf.add(((v and 0x7F) or 0x80L).toByte())
                v = v ushr 7
            }
            buf.add((v and 0x7F).toByte())
        }

        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}
