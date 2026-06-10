package com.mitm.shadowtrack.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds SF3 wire-format packets for injection.
 *
 * Wire format:
 *   Small  [0x01][1B len][protobuf envelope]
 *   Large  [0x02][4B LE len][raw-deflate protobuf envelope]
 *
 * Verified against captured outbound event_battle_finish_fight packet (user_3):
 *
 *   envelope:
 *     field[1] varint = 34          (controller)
 *     field[2] string = "event_battle_finish_fight"
 *     field[3] bytes  = params
 *
 *   params:
 *     field[1]  varint = battleId          (e.g. 3001602)
 *     field[4]  varint = 3                 (constant)
 *     field[6]  bytes  = {field[1] = ts}   (current timestamp ms, nested proto)
 *     field[7]  varint = 1                 (win outcome)
 *     field[10] bytes  = ""                (empty)
 *     field[13] bytes  = {field[2] = 29}   (result payload, nested proto)
 */
object PacketInjector {

    fun buildFinishFight(battleId: Long): ByteArray {
        val ts        = System.currentTimeMillis()
        val tsProto   = proto { varintField(1, ts) }
        val resProto  = proto { varintField(2, 29L) }

        val params = proto {
            varintField(1,  battleId)           // battle_id
            varintField(4,  3L)                 // constant
            bytesField(6,   tsProto)             // timestamp nested proto
            varintField(7,  1L)                 // win = 1
            bytesField(10,  ByteArray(0))        // empty field
            bytesField(13,  resProto)            // result nested proto
        }
        return envelope("event_battle_finish_fight", params, controller = 34L)
    }

    // ── Proto writer ──────────────────────────────────────────────────────

    private fun proto(block: ProtoWriter.() -> Unit): ByteArray {
        val w = ProtoWriter()
        w.block()
        return w.toByteArray()
    }

    private fun envelope(command: String, params: ByteArray, controller: Long): ByteArray {
        val body = proto {
            varintField(1, controller)
            stringField(2, command)
            bytesField(3, params)
        }
        return if (body.size < 255) {
            byteArrayOf(0x01, body.size.toByte()) + body
        } else {
            val lenBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array()
            byteArrayOf(0x02) + lenBytes + body
        }
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
