package com.mitm.shadowtrack.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds SF3 wire-format packets that can be injected into the outbound
 * stream of the game socket.
 *
 * Wire format:
 *   Small  [0x01][1B len][protobuf envelope]
 *   Large  [0x02][4B LE len][raw-deflate protobuf envelope]
 *
 * Proto envelope:
 *   field[1] varint  = controller  (3 = extension)
 *   field[2] string  = command
 *   field[3] bytes   = params (nested proto)
 */
object PacketInjector {

    // ── Public API ────────────────────────────────────────────────────────

    fun buildFinishFight(battleId: Long): ByteArray {
        val params = proto {
            varintField(1, battleId)   // battle_id
            varintField(2, 1L)         // outcome = win (best guess; 0=lose 1=win)
        }
        return envelope("finish_fight", params)
    }

    // ── Proto writer ──────────────────────────────────────────────────────

    private fun proto(block: ProtoWriter.() -> Unit): ByteArray {
        val w = ProtoWriter()
        w.block()
        return w.toByteArray()
    }

    private fun envelope(command: String, params: ByteArray): ByteArray {
        val body = proto {
            varintField(1, 3L)          // controller = extension
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
            writeVarint(((fieldNum.toLong() shl 3) or 0L))
            writeVarint(value)
        }

        fun stringField(fieldNum: Int, value: String) {
            bytesField(fieldNum, value.toByteArray(Charsets.UTF_8))
        }

        fun bytesField(fieldNum: Int, bytes: ByteArray) {
            writeVarint(((fieldNum.toLong() shl 3) or 2L))
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
