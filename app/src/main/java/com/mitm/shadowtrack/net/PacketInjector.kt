package com.mitm.shadowtrack.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

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
     *   2. Appends params.field[5] = field[7] (wonRounds = totalRounds) if absent.
     *      The server validates "check result and wonRounds" — result=WIN without
     *      wonRounds gets an IllegalArgumentException.
     *
     * All other bytes — battleId, counter, seed, items, stats, level — are
     * preserved exactly as the game sent them.
     *
     * Returns the patched packet on success, or null if parsing fails (caller logs and drops).
     */
    fun patchFinishFightToWin(data: ByteArray): ByteArray? {
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

                    // First pass: collect field[4] value-byte position, field[7] value, field[5] presence
                    var field4ValPos   = -1
                    var field7Val      = 1L   // totalRounds — default 1 if not found
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
                                    5 -> field5Present = true
                                    7 -> field7Val = v
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

                    return if (field5Present) {
                        // field[5] already there — just flip field[4]
                        data.copyOf().also { it[field4ValPos] = 0x01 }
                    } else {
                        // Append field[5] = field[7] after existing params.
                        // field[5] tag = (5 shl 3) or 0 = 0x28
                        // field[7] is always 1–3, single-byte varint.
                        // params is always the last envelope field, so appending to the
                        // packet is safe — nothing follows params in the SF3 envelope.
                        val extra = byteArrayOf(0x28, (field7Val and 0x7F).toByte())
                        val result = ByteArray(data.size + extra.size)
                        data.copyInto(result)
                        result[field4ValPos]      = 0x01                           // flip result to WIN
                        result[1]                 = (frameLen + extra.size).toByte() // extend frame length
                        result[paramsLenBytePos]  = (len + extra.size).toByte()      // extend params length
                        extra.copyInto(result, protoEnd)                           // append field[5]
                        result
                    }
                }
                else -> return null
            }
        }
        return null
    }

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
