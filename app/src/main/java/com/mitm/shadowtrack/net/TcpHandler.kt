package com.mitm.shadowtrack.net

import android.net.VpnService
import com.mitm.shadowtrack.model.*
import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
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
    val pendingData: ArrayDeque<ByteArray> = ArrayDeque()
) {
    val key get() = "${srcIp.joinToString(".")}:$srcPort->${dstIp.joinToString(".")}:$dstPort"
}

enum class TcpStatus { SYN_RECEIVED, ESTABLISHED, FIN_WAIT, CLOSED }

class TcpHandler(
    private val vpnService: VpnService,
    private val vpnFd: FileDescriptor,
    private val onConnectionEvent: (ConnectionEntry) -> Unit,
    private val onMessage: (String, LiveMessage) -> Unit,
    private val onStatusChange: (String, ConnectionStatus) -> Unit
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

        // Send SYN-ACK back to the app
        sendSynAck(connState, tcp.seqNum)

        // Connect to real destination
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

                    // Send any queued data
                    connState.pendingData.forEach { data ->
                        channel.write(ByteBuffer.wrap(data))
                    }
                    connState.pendingData.clear()

                    // Start relay loop
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
            // Detect WebSocket upgrade
            val payloadStr = String(packet.payload, Charsets.ISO_8859_1)
            val isWsUpgrade = payloadStr.contains("Upgrade: websocket", ignoreCase = true) ||
                              payloadStr.contains("Upgrade: WebSocket", ignoreCase = true)

            val msg = LiveMessage(LiveMessage.Direction.OUTBOUND, packet.payload)
            onMessage(conn.connId, msg)

            if (isWsUpgrade) {
                onStatusChange(conn.connId, ConnectionStatus.ACTIVE)
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
            flags = 0x12, // SYN + ACK
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
            flags = 0x10, // ACK
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
                        // Server closed connection
                        sendFin(conn)
                        cleanup(conn.key)
                        break
                    }
                    if (read > 0) {
                        buf.flip()
                        val data = ByteArray(read).also { buf.get(it) }

                        // Deliver data back to app via VPN
                        val pkt = PacketParser.buildIPv4TCPPacket(
                            srcIp = conn.dstIp,
                            dstIp = conn.srcIp,
                            srcPort = conn.dstPort,
                            dstPort = conn.srcPort,
                            seq = conn.localSeq,
                            ack = conn.remoteSeq,
                            flags = 0x18, // PSH + ACK
                            window = 65535,
                            payload = data
                        )
                        conn.localSeq = (conn.localSeq + read.toLong()) and 0xFFFFFFFFL
                        writeToVpn(pkt)

                        onMessage(conn.connId, LiveMessage(LiveMessage.Direction.INBOUND, data))
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

    private fun sendFin(conn: TcpConnState) {
        val packet = PacketParser.buildIPv4TCPPacket(
            srcIp = conn.dstIp,
            dstIp = conn.srcIp,
            srcPort = conn.dstPort,
            dstPort = conn.srcPort,
            seq = conn.localSeq,
            ack = conn.remoteSeq,
            flags = 0x11, // FIN + ACK
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
