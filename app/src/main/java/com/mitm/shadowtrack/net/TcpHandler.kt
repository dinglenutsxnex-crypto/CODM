package com.mitm.shadowtrack.net

import android.net.VpnService
import com.mitm.shadowtrack.model.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

data class TcpConnState(
    val connId: String,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    var localSeq: Long,
    var remoteSeq: Long,
    var channel: SocketChannel? = null,
    var status: TcpStatus = TcpStatus.SYN_RECEIVED,
    val pendingData: ArrayDeque<ByteArray> = ArrayDeque(),
    var awaitingWsHandshake: Boolean = false,
    var isWebSocket: Boolean = false,
    val inboundWsBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    val outboundWsBuffer: ByteArrayOutputStream = ByteArrayOutputStream()
) {
    val key get() = "${srcIp.joinToString(".")}:$srcPort->${dstIp.joinToString(".")}:$dstPort"
}

enum class TcpStatus { SYN_RECEIVED, ESTABLISHED, FIN_WAIT, CLOSED }

class TcpHandler(
    private val vpnService: VpnService,
    private val vpnFd: FileDescriptor,
    private val onConnectionEvent: (ConnectionEntry) -> Unit,
    private val onMessage: (String, LiveMessage) -> Unit,
    private val onStatusChange: (String, ConnectionStatus) -> Unit,
    private val onWebSocket: (String) -> Unit = {}
) {
    private val connections = ConcurrentHashMap<String, TcpConnState>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outStream = FileOutputStream(vpnFd)

    fun handlePacket(packet: ParsedPacket) {
        val tcp = packet.tcp ?: return
        val connKey = "${packet.ip.srcAddr.address.joinToString(".")}:${tcp.srcPort}->" +
                      "${packet.ip.dstAddr.address.joinToString(".")}:${tcp.dstPort}"

        when {
            tcp.isSYN && !tcp.isACK -> handleSyn(packet, tcp, connKey)
            tcp.isACK && !tcp.isSYN -> handleAck(packet, tcp, connKey)
            tcp.isFIN -> handleFin(connKey)
            tcp.isRST -> handleRst(connKey)
        }
    }

    private fun handleSyn(packet: ParsedPacket, tcp: TCPHeader, connKey: String) {
        val srcIp = packet.ip.srcAddr.address
        val dstIp = packet.ip.dstAddr.address
        val connId = connKey

        val entry = ConnectionEntry(
            id = connId,
            protocol = Protocol.TCP,
            srcPort = tcp.srcPort,
            dstIp = packet.ip.dstAddr.hostAddress ?: "?",
            dstPort = tcp.dstPort,
            status = ConnectionStatus.CONNECTING
        )
        onConnectionEvent(entry)

        val connState = TcpConnState(
            connId = connId,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = tcp.srcPort,
            dstPort = tcp.dstPort,
            localSeq = (Math.random() * 0xFFFFFFFFL).toLong(),
            remoteSeq = (tcp.seqNum + 1L) and 0xFFFFFFFFL
        )
        connections[connKey] = connState

        sendSynAck(connState, tcp.seqNum)

        scope.launch {
            try {
                val channel = SocketChannel.open()
                channel.configureBlocking(false)
                vpnService.protect(channel.socket())
                channel.connect(InetSocketAddress(packet.ip.dstAddr, tcp.dstPort))

                var attempts = 0
                while (!channel.finishConnect() && attempts++ < 50) delay(20)

                if (channel.isConnected) {
                    connState.channel = channel
                    connState.status = TcpStatus.ESTABLISHED
                    onStatusChange(connId, ConnectionStatus.ACTIVE)

                    connState.pendingData.forEach { data ->
                        channel.write(ByteBuffer.wrap(data))
                    }
                    connState.pendingData.clear()

                    startRelayLoop(connState)
                } else {
                    channel.close()
                    cleanup(connKey)
                }
            } catch (e: Exception) {
                cleanup(connKey)
            }
        }
    }

    private fun handleAck(packet: ParsedPacket, tcp: TCPHeader, connKey: String) {
        val conn = connections[connKey] ?: return
        conn.remoteSeq = (tcp.seqNum + maxOf(0, packet.payload.size).toLong()) and 0xFFFFFFFFL

        if (packet.payload.isNotEmpty()) {
            val payloadStr = String(packet.payload, Charsets.ISO_8859_1)

            if (!conn.isWebSocket) {
                // Detect outbound HTTP→WS upgrade request
                if (payloadStr.contains("Upgrade: websocket", ignoreCase = true) ||
                    payloadStr.contains("Upgrade: WebSocket", ignoreCase = true)) {
                    conn.awaitingWsHandshake = true
                }

                val msg = LiveMessage(LiveMessage.Direction.OUTBOUND, packet.payload)
                onMessage(conn.connId, msg)
            } else {
                // Already a WebSocket — parse outbound frames (client→server, masked)
                conn.outboundWsBuffer.write(packet.payload)
                parseWsFrames(conn.connId, conn.outboundWsBuffer, LiveMessage.Direction.OUTBOUND)
            }

            when (conn.status) {
                TcpStatus.ESTABLISHED -> {
                    val ch = conn.channel
                    if (ch != null && ch.isConnected) {
                        scope.launch {
                            try {
                                ch.write(ByteBuffer.wrap(packet.payload))
                            } catch (_: Exception) {}
                        }
                    } else {
                        conn.pendingData.add(packet.payload)
                    }
                }
                TcpStatus.SYN_RECEIVED -> conn.pendingData.add(packet.payload)
                else -> {}
            }
        }
    }

    private fun handleFin(connKey: String) {
        val conn = connections[connKey] ?: return
        conn.status = TcpStatus.FIN_WAIT
        onStatusChange(conn.connId, ConnectionStatus.CLOSING)
        scope.launch {
            conn.channel?.close()
            cleanup(connKey)
        }
    }

    private fun handleRst(connKey: String) {
        val conn = connections[connKey] ?: return
        conn.status = TcpStatus.CLOSED
        onStatusChange(conn.connId, ConnectionStatus.CLOSED)
        scope.launch { conn.channel?.close() }
        connections.remove(connKey)
    }

    private fun sendSynAck(conn: TcpConnState, remoteSynSeq: Long) {
        val packet = PacketParser.buildIPv4TCPPacket(
            srcIp = conn.dstIp,
            dstIp = conn.srcIp,
            srcPort = conn.dstPort,
            dstPort = conn.srcPort,
            seq = conn.localSeq,
            ack = (remoteSynSeq + 1L) and 0xFFFFFFFFL,
            flags = 0x12,
            window = 65535,
            payload = ByteArray(0)
        )
        conn.localSeq = (conn.localSeq + 1L) and 0xFFFFFFFFL
        writeToVpn(packet)
    }

    private fun sendAck(conn: TcpConnState) {
        val packet = PacketParser.buildIPv4TCPPacket(
            srcIp = conn.dstIp,
            dstIp = conn.srcIp,
            srcPort = conn.dstPort,
            dstPort = conn.srcPort,
            seq = conn.localSeq,
            ack = conn.remoteSeq,
            flags = 0x10,
            window = 65535,
            payload = ByteArray(0)
        )
        writeToVpn(packet)
    }

    private fun startRelayLoop(conn: TcpConnState) {
        val ch = conn.channel ?: return
        scope.launch {
            val buf = ByteBuffer.allocate(32768)
            while (conn.status == TcpStatus.ESTABLISHED) {
                try {
                    buf.clear()
                    val read = ch.read(buf)
                    if (read == -1) {
                        sendFin(conn)
                        cleanup(conn.key)
                        break
                    }
                    if (read > 0) {
                        buf.flip()
                        val data = ByteArray(read).also { buf.get(it) }

                        val pkt = PacketParser.buildIPv4TCPPacket(
                            srcIp = conn.dstIp,
                            dstIp = conn.srcIp,
                            srcPort = conn.dstPort,
                            dstPort = conn.srcPort,
                            seq = conn.localSeq,
                            ack = conn.remoteSeq,
                            flags = 0x18,
                            window = 65535,
                            payload = data
                        )
                        conn.localSeq = (conn.localSeq + read.toLong()) and 0xFFFFFFFFL
                        writeToVpn(pkt)

                        if (!conn.isWebSocket && conn.awaitingWsHandshake) {
                            // Look for server's 101 Switching Protocols response
                            val responseStr = String(data, Charsets.ISO_8859_1)
                            if (responseStr.contains("101 Switching Protocols", ignoreCase = true)) {
                                conn.isWebSocket = true
                                conn.awaitingWsHandshake = false
                                onWebSocket(conn.connId)
                                // The 101 response itself is the last HTTP message; log it raw
                                onMessage(conn.connId, LiveMessage(LiveMessage.Direction.INBOUND, data))
                                continue
                            }
                        }

                        if (conn.isWebSocket) {
                            // Parse inbound WS frames (server→client, not masked)
                            conn.inboundWsBuffer.write(data)
                            parseWsFrames(conn.connId, conn.inboundWsBuffer, LiveMessage.Direction.INBOUND)
                        } else {
                            onMessage(conn.connId, LiveMessage(LiveMessage.Direction.INBOUND, data))
                        }
                    } else {
                        delay(5)
                    }
                } catch (e: Exception) {
                    break
                }
            }
            cleanup(conn.key)
        }
    }

    /**
     * Parses as many complete WebSocket frames as possible from [buffer].
     * Any leftover incomplete bytes remain in the buffer for the next read.
     *
     * WS frame layout:
     *   Byte 0: FIN(1) RSV(3) Opcode(4)
     *   Byte 1: MASK(1) PayloadLen(7)   [126 → next 2 bytes, 127 → next 8 bytes]
     *   Masking key (4 bytes, only if MASK=1)
     *   Payload
     */
    private fun parseWsFrames(connId: String, buffer: ByteArrayOutputStream, direction: LiveMessage.Direction) {
        val raw = buffer.toByteArray()
        buffer.reset()

        var offset = 0
        while (offset < raw.size) {
            if (offset + 2 > raw.size) break  // Need at least 2 header bytes

            val b0 = raw[offset].toInt() and 0xFF
            val b1 = raw[offset + 1].toInt() and 0xFF
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            var headerSize = 2
            if (payloadLen == 126L) {
                if (offset + 4 > raw.size) break  // Need 2 extended length bytes
                payloadLen = ((raw[offset + 2].toInt() and 0xFF) shl 8 or
                              (raw[offset + 3].toInt() and 0xFF)).toLong()
                headerSize = 4
            } else if (payloadLen == 127L) {
                if (offset + 10 > raw.size) break  // Need 8 extended length bytes
                payloadLen = 0L
                for (i in 0..7) {
                    payloadLen = (payloadLen shl 8) or (raw[offset + 2 + i].toInt() and 0xFF).toLong()
                }
                headerSize = 10
            }

            val maskOffset = offset + headerSize
            val dataOffset = maskOffset + if (masked) 4 else 0
            val totalFrame = dataOffset - offset + payloadLen.toInt()

            if (offset + totalFrame > raw.size) break  // Frame not yet complete

            val payload = ByteArray(payloadLen.toInt())
            if (masked) {
                val key = raw.slice(maskOffset until maskOffset + 4)
                for (i in payload.indices) {
                    payload[i] = (raw[dataOffset + i].toInt() xor (key[i % 4].toInt() and 0xFF)).toByte()
                }
            } else {
                System.arraycopy(raw, dataOffset, payload, 0, payload.size)
            }

            // Emit a message for data frames only (text=1, binary=2, continuation=0)
            if (opcode in 0..2 && payload.isNotEmpty()) {
                onMessage(connId, LiveMessage(direction, payload))
            }

            offset += totalFrame
        }

        // Buffer any leftover incomplete bytes
        if (offset < raw.size) {
            buffer.write(raw, offset, raw.size - offset)
        }
    }

    private fun sendFin(conn: TcpConnState) {
        val packet = PacketParser.buildIPv4TCPPacket(
            srcIp = conn.dstIp,
            dstIp = conn.srcIp,
            srcPort = conn.dstPort,
            dstPort = conn.srcPort,
            seq = conn.localSeq,
            ack = conn.remoteSeq,
            flags = 0x11,
            window = 0,
            payload = ByteArray(0)
        )
        writeToVpn(packet)
    }

    private fun writeToVpn(data: ByteArray) {
        try {
            outStream.write(data)
        } catch (_: Exception) {}
    }

    private fun cleanup(connKey: String) {
        val conn = connections.remove(connKey)
        if (conn != null) {
            onStatusChange(conn.connId, ConnectionStatus.CLOSED)
            try { conn.channel?.close() } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        scope.cancel()
        connections.values.forEach { try { it.channel?.close() } catch (_: Exception) {} }
        connections.clear()
    }
}
