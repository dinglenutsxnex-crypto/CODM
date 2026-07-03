package com.nexora.hammerscale.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

object PacketInjector {

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
    private val BRAWLER_WIN_ROUND_ENTRIES = arrayOf(
        byteArrayOf(0x08, 0x03, 0x10, 0x01),
        byteArrayOf(0x08, 0x04, 0x10, 0x02),
        byteArrayOf(0x08, 0x05, 0x10, 0x03),
        byteArrayOf(0x08, 0x06, 0x10, 0x02),
        byteArrayOf(0x08, 0x07)
    )

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

    fun patchFinishFightToWin(data: ByteArray, roundsToWin: Int = 3): ByteArray? {
        if (data.size < 3 || (data[0].toInt() and 0xFF) != 0x01) return null
        val frameLen = data[1].toInt() and 0xFF
        val protoEnd = 2 + frameLen
        if (data.size < protoEnd) return null

        var pos = 2
        while (pos < protoEnd) {
            val tagByte = data[pos].toInt() and 0xFF
            pos++
            val fieldNum = tagByte ushr 3
            val wireType = tagByte and 7
            when (wireType) {
                0 -> { while (pos < protoEnd && (data[pos].toInt() and 0x80) != 0) pos++; pos++ }
                2 -> {
                    val paramsLenBytePos = pos
                    var len = 0; var shift = 0; var lenByteCount = 0
                    while (pos < protoEnd) {
                        val b = data[pos++].toInt() and 0xFF; lenByteCount++
                        len = len or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (fieldNum != 3) { pos += len; continue }

                    if (lenByteCount != 1) return null

                    val paramsStart = pos
                    val paramsEnd   = pos + len

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

                    if (field4ValPos < 0) return null

                    val rVal = (roundsToWin and 0x7F).toByte()

                    return if (field5Present) {
                        data.copyOf().also { copy ->
                            copy[field4ValPos] = 0x01
                            if (field5ValPos >= 0) copy[field5ValPos] = rVal
                            if (field7ValPos >= 0) copy[field7ValPos] = rVal
                        }
                    } else {
                        val extra = byteArrayOf(0x28, rVal)
                        val result = ByteArray(data.size + extra.size)
                        data.copyInto(result)
                        result[field4ValPos]     = 0x01
                        if (field7ValPos >= 0) result[field7ValPos] = rVal
                        result[1]                = (frameLen + extra.size).toByte()
                        result[paramsLenBytePos] = (len + extra.size).toByte()
                        extra.copyInto(result, protoEnd)
                        result
                    }
                }
                else -> return null
            }
        }
        return null
    }

    fun patchRaidFightFinishToMaxDamage(data: ByteArray): ByteArray? {
        if (data.size < 3 || (data[0].toInt() and 0xFF) != 0x01) return null
        val frameLen = data[1].toInt() and 0xFF
        val protoEnd = 2 + frameLen
        if (data.size < protoEnd) return null

        var pos = 2
        var outerCounter = 0L
        while (pos < protoEnd) {
            val tagByte = data[pos].toInt() and 0xFF; pos++
            val fieldNum = tagByte ushr 3; val wireType = tagByte and 7
            when (wireType) {
                0 -> {
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

    fun patchInboundBrawlerFinishToWin(data: ByteArray): ByteArray? {
        var pos = 0
        while (pos < data.size) {
            val t = data[pos].toInt() and 0xFF
            when (t) {
                0x02 -> {
                    if (pos + 5 > data.size) return null
                    val compLen = ByteBuffer.wrap(data, pos + 1, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int
                    if (compLen <= 0 || pos + 5 + compLen > data.size) return null
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

    private fun tryPatchInboundBrawlerProto(rawProto: ByteArray): ByteArray? {
        var counter = -1L
        var paramsStart = -1; var paramsLen = -1
        var isFinish = false
        var pos = 0
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

    fun patchBrawlerFinishToWin(data: ByteArray): ByteArray? {
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

    private fun tryPatchBrawlerProto(rawProto: ByteArray): ByteArray? {
        var pos = 0
        var counter = -1L
        var paramsBytes: ByteArray? = null
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
