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
     * Build a finish-fight WIN packet using the game's own battleId and counter.
     *
     * Called from TcpHandler's ARM-WIN intercept path: the game sent its natural
     * finish_fight (with loss values), we extracted battleId + counter from it,
     * and now send this replacement instead so the server sees a WIN on the exact
     * connection the game's state machine is already waiting on.
     *
     * @param battleId  From tryExtractFinishFight — the battle the game just finished.
     * @param counter   The game's own counter from that packet — reused verbatim.
     * @param rounds    Rounds to report as won/total. Default 3 matches event battles.
     */
    fun buildFinishFight(battleId: Long, counter: Long, rounds: Long = 3L): ByteArray {
        val seedProto = proto { varintField(1, System.currentTimeMillis()) }

        val params = proto {
            varintField(1,  battleId)
            varintField(4,  1L)          // WIN
            varintField(5,  rounds)      // wonRounds
            bytesField(6,   seedProto)   // live timestamp
            varintField(7,  rounds)      // totalRounds
            bytesField(10,  WIN_ITEMS)
            bytesField(13,  WIN_STATS)
            varintField(14, WIN_LEVEL)
        }
        return envelope("event_battle_finish_fight", params, counter = counter)
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
