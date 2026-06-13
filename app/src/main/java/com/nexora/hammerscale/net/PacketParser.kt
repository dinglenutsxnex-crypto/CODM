package com.nexora.hammerscale.net

import java.net.InetAddress
import java.nio.ByteBuffer

data class IPHeader(
    val version: Int,
    val ihl: Int,
    val totalLen: Int,
    val protocol: Int,
    val srcAddr: InetAddress,
    val dstAddr: InetAddress,
    val headerLen: Int
)

data class TCPHeader(
    val srcPort: Int,
    val dstPort: Int,
    val seqNum: Long,
    val ackNum: Long,
    val dataOffset: Int,
    val flags: Int,
    val window: Int
) {
    val isSYN get() = (flags and 0x02) != 0
    val isACK get() = (flags and 0x10) != 0
    val isFIN get() = (flags and 0x01) != 0
    val isRST get() = (flags and 0x04) != 0
    val isPSH get() = (flags and 0x08) != 0
    val headerLen get() = dataOffset * 4
}

data class UDPHeader(
    val srcPort: Int,
    val dstPort: Int,
    val length: Int
)

data class ParsedPacket(
    val ip: IPHeader,
    val tcp: TCPHeader? = null,
    val udp: UDPHeader? = null,
    val payload: ByteArray = ByteArray(0),
    val rawBuffer: ByteBuffer
)

object PacketParser {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val PROTO_ICMP = 1

    fun parse(buf: ByteBuffer): ParsedPacket? {
        if (buf.remaining() < 20) return null
        buf.mark()

        val versionIHL = buf.get().toInt() and 0xFF
        val version = versionIHL shr 4
        if (version != 4) { buf.reset(); return null }

        val ihl = (versionIHL and 0x0F) * 4
        if (buf.remaining() < ihl - 1) { buf.reset(); return null }

        buf.get() // DSCP/ECN
        val totalLen = buf.getShort().toInt() and 0xFFFF
        buf.getShort() // ID
        buf.getShort() // flags+fragment offset
        buf.get()      // TTL
        val protocol = buf.get().toInt() and 0xFF
        buf.getShort() // checksum

        val srcBytes = ByteArray(4).also { buf.get(it) }
        val dstBytes = ByteArray(4).also { buf.get(it) }
        val srcAddr = InetAddress.getByAddress(srcBytes)
        val dstAddr = InetAddress.getByAddress(dstBytes)

        // skip IP options if any
        val optLen = ihl - 20
        if (optLen > 0 && buf.remaining() >= optLen) repeat(optLen) { buf.get() }

        val ipHeader = IPHeader(version, ihl, totalLen, protocol, srcAddr, dstAddr, ihl)

        return when (protocol) {
            PROTO_TCP -> parseTCP(buf, ipHeader, totalLen - ihl)
            PROTO_UDP -> parseUDP(buf, ipHeader)
            else -> null
        }
    }

    private fun parseTCP(buf: ByteBuffer, ip: IPHeader, remaining: Int): ParsedPacket? {
        if (buf.remaining() < 20) return null
        val srcPort = buf.getShort().toInt() and 0xFFFF
        val dstPort = buf.getShort().toInt() and 0xFFFF
        val seqNum = buf.getInt().toLong() and 0xFFFFFFFFL
        val ackNum = buf.getInt().toLong() and 0xFFFFFFFFL
        val dataOffsetFlags = buf.getShort().toInt() and 0xFFFF
        val dataOffset = (dataOffsetFlags shr 12) and 0x0F
        val flags = dataOffsetFlags and 0x01FF
        val window = buf.getShort().toInt() and 0xFFFF
        buf.getShort() // checksum
        buf.getShort() // urgent pointer

        val tcpHeaderLen = dataOffset * 4
        val optLen = tcpHeaderLen - 20
        if (optLen > 0 && buf.remaining() >= optLen) repeat(optLen) { buf.get() }

        val payloadLen = remaining - tcpHeaderLen
        val payload = if (payloadLen > 0 && buf.remaining() >= payloadLen) {
            ByteArray(payloadLen).also { buf.get(it) }
        } else ByteArray(0)

        val tcpHeader = TCPHeader(srcPort, dstPort, seqNum, ackNum, dataOffset, flags, window)
        buf.rewind()
        return ParsedPacket(ip, tcp = tcpHeader, payload = payload, rawBuffer = buf)
    }

    private fun parseUDP(buf: ByteBuffer, ip: IPHeader): ParsedPacket? {
        if (buf.remaining() < 8) return null
        val srcPort = buf.getShort().toInt() and 0xFFFF
        val dstPort = buf.getShort().toInt() and 0xFFFF
        val length = buf.getShort().toInt() and 0xFFFF
        buf.getShort() // checksum
        val payloadLen = length - 8
        val payload = if (payloadLen > 0 && buf.remaining() >= payloadLen) {
            ByteArray(payloadLen).also { buf.get(it) }
        } else ByteArray(0)

        val udpHeader = UDPHeader(srcPort, dstPort, length)
        buf.rewind()
        return ParsedPacket(ip, udp = udpHeader, payload = payload, rawBuffer = buf)
    }

    fun buildIPv4TCPPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int, window: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen + payload.size
        val buf = ByteBuffer.allocate(totalLen)

        // IP header
        buf.put((0x45).toByte())
        buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.putShort(0) // ID
        buf.putShort(0x4000.toShort()) // Don't fragment
        buf.put(64)  // TTL
        buf.put(PROTO_TCP.toByte())
        buf.putShort(0) // checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)
        val ipChecksum = checksum(buf.array(), 0, ipHeaderLen)
        buf.putShort(10, ipChecksum.toShort())

        // TCP header
        val tcpStart = ipHeaderLen
        buf.putShort((srcPort and 0xFFFF).toShort())
        buf.putShort((dstPort and 0xFFFF).toShort())
        buf.putInt((seq and 0xFFFFFFFFL).toInt())
        buf.putInt((ack and 0xFFFFFFFFL).toInt())
        buf.putShort((0x5000 or (flags and 0x01FF)).toShort())
        buf.putShort((window and 0xFFFF).toShort())
        buf.putShort(0) // checksum placeholder
        buf.putShort(0) // urgent

        if (payload.isNotEmpty()) buf.put(payload)

        val tcpChecksum = tcpChecksum(srcIp, dstIp, buf.array(), tcpStart, tcpHeaderLen + payload.size)
        buf.putShort(tcpStart + 16, tcpChecksum.toShort())

        return buf.array()
    }

    fun buildIPv4UDPPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + payload.size
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte()); buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.putShort(0); buf.putShort(0x4000.toShort())
        buf.put(64); buf.put(PROTO_UDP.toByte())
        buf.putShort(0)
        buf.put(srcIp); buf.put(dstIp)
        val ipCsum = checksum(buf.array(), 0, ipHeaderLen)
        buf.putShort(10, ipCsum.toShort())

        val udpStart = ipHeaderLen
        buf.putShort((srcPort and 0xFFFF).toShort())
        buf.putShort((dstPort and 0xFFFF).toShort())
        buf.putShort((udpHeaderLen + payload.size).toShort())
        buf.putShort(0)
        if (payload.isNotEmpty()) buf.put(payload)

        return buf.array()
    }

    private fun checksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + len - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((len and 1) != 0) sum += (data[offset + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksum(srcIp: ByteArray, dstIp: ByteArray, segment: ByteArray, offset: Int, len: Int): Int {
        val pseudo = ByteBuffer.allocate(12 + len)
        pseudo.put(srcIp); pseudo.put(dstIp)
        pseudo.put(0); pseudo.put(PROTO_TCP.toByte())
        pseudo.putShort(len.toShort())
        pseudo.put(segment, offset, len)
        return checksum(pseudo.array(), 0, pseudo.capacity())
    }
}
