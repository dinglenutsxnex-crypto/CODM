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
 * Verified against captured outbound event_battle_finish_fight packet (user_3):
 *
 *   envelope:
 *     field[1] varint = 34          (counter at that point in that captured session)
 *     field[2] string = "event_battle_finish_fight"
 *     field[3] bytes  = params
 *
 *   params:
 *     field[1]  varint = battleId
 *     field[4]  varint = 3                 (constant)
 *     field[6]  bytes  = {field[1] = seed} (seed nested proto, 7 bytes in capture)
 *     field[7]  varint = 1                 (win outcome)
 *     field[10] bytes  = ""                (empty)
 *     field[13] bytes  = {field[2] = 29}   (result payload, nested proto)
 */
object PacketInjector {

    /**
     * Build a finish-fight win packet.
     *
     * @param battleId  The battle ID captured from the outbound event_battle_start_fight packet.
     * @param counter   The next outbound sequence counter — pass ConnectionViewModel.nextInjectCounter.
     *                  Using the wrong counter (e.g. a hardcoded value) causes the server to treat
     *                  the packet as a duplicate and silently ignore it.
     */
    fun buildFinishFight(battleId: Long, counter: Long): ByteArray {
        val seedProto  = proto { varintField(1, System.currentTimeMillis()) }
        val resProto   = proto { varintField(2, 29L) }

        val params = proto {
            varintField(1,  battleId)
            varintField(4,  3L)
            bytesField(6,   seedProto)
            varintField(7,  1L)
            bytesField(10,  ByteArray(0))
            bytesField(13,  resProto)
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
