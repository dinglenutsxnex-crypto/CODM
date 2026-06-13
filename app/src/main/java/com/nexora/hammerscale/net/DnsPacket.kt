package com.nexora.hammerscale.net

import java.nio.ByteBuffer

data class DnsRecord(val name: String, val type: Int, val data: String)

data class DnsPacket(
    val id: Int,
    val isResponse: Boolean,
    val questions: List<String>,
    val answers: List<DnsRecord>
)

object DnsParser {

    fun parse(data: ByteArray): DnsPacket? {
        if (data.size < 12) return null
        return try {
            val buf = ByteBuffer.wrap(data)
            val id = buf.getShort().toInt() and 0xFFFF
            val flags = buf.getShort().toInt() and 0xFFFF
            val isResponse = (flags shr 15) and 1 == 1
            val qdCount = buf.getShort().toInt() and 0xFFFF
            val anCount = buf.getShort().toInt() and 0xFFFF
            buf.getShort(); buf.getShort() // ns, ar count

            val questions = mutableListOf<String>()
            repeat(qdCount) {
                val name = readName(buf, data)
                buf.getShort(); buf.getShort() // type, class
                questions.add(name)
            }

            val answers = mutableListOf<DnsRecord>()
            repeat(anCount) {
                if (buf.remaining() < 10) return@repeat
                val name = readName(buf, data)
                val type = buf.getShort().toInt() and 0xFFFF
                buf.getShort() // class
                buf.getInt()   // TTL
                val rdLen = buf.getShort().toInt() and 0xFFFF
                val rdData = when (type) {
                    1 -> { // A record
                        if (rdLen == 4 && buf.remaining() >= 4) {
                            val ip = ByteArray(4).also { buf.get(it) }
                            ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
                        } else { repeat(rdLen) { if (buf.hasRemaining()) buf.get() }; "?" }
                    }
                    28 -> { // AAAA record
                        if (rdLen == 16 && buf.remaining() >= 16) {
                            val ip = ByteArray(16).also { buf.get(it) }
                            ip.toList().chunked(2).joinToString(":") {
                                "%04x".format(((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF))
                            }
                        } else { repeat(rdLen) { if (buf.hasRemaining()) buf.get() }; "?" }
                    }
                    5 -> { // CNAME
                        val saved = buf.position()
                        val cname = try { readName(buf, data) } catch (e: Exception) { "?" }
                        val read = buf.position() - saved
                        if (read < rdLen) repeat(rdLen - read) { if (buf.hasRemaining()) buf.get() }
                        cname
                    }
                    else -> {
                        repeat(rdLen) { if (buf.hasRemaining()) buf.get() }
                        "type=$type"
                    }
                }
                answers.add(DnsRecord(name, type, rdData))
            }

            DnsPacket(id, isResponse, questions, answers)
        } catch (e: Exception) {
            null
        }
    }

    private fun readName(buf: ByteBuffer, raw: ByteArray): String {
        val sb = StringBuilder()
        var jumped = false
        var savedPos = -1
        var maxJumps = 10

        while (buf.hasRemaining()) {
            val len = buf.get().toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                if (!buf.hasRemaining()) break
                val lo = buf.get().toInt() and 0xFF
                val ptr = ((len and 0x3F) shl 8) or lo
                if (!jumped) { savedPos = buf.position(); jumped = true }
                if (ptr >= raw.size || maxJumps-- <= 0) break
                buf.position(ptr)
                continue
            }
            if (sb.isNotEmpty()) sb.append('.')
            if (buf.remaining() < len) break
            val labelBytes = ByteArray(len).also { buf.get(it) }
            sb.append(String(labelBytes, Charsets.US_ASCII))
        }

        if (jumped && savedPos >= 0) buf.position(savedPos)
        return sb.toString()
    }

    fun buildQuery(domain: String, type: Int = 1): ByteArray {
        val buf = ByteBuffer.allocate(512)
        val id = (Math.random() * 65535).toInt()
        buf.putShort(id.toShort())
        buf.putShort(0x0100.toShort()) // Standard query, recursion desired
        buf.putShort(1.toShort()) // 1 question
        buf.putShort(0); buf.putShort(0); buf.putShort(0)

        domain.split('.').forEach { label ->
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0) // null terminator
        buf.putShort(type.toShort()) // A record
        buf.putShort(1.toShort()) // IN class

        return buf.array().copyOf(buf.position())
    }
}
