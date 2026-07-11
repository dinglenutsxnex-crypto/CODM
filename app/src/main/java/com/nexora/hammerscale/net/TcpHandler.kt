package com.nexora.hammerscale.net

import android.net.VpnService
import android.util.Log
import com.nexora.hammerscale.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as KChannel
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

private const val TAG = "TcpHandler"

data class TcpConnState(
    val connId: String,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    @Volatile var localSeq: Long,
    @Volatile var remoteSeq: Long,
    @Volatile var channel: SocketChannel? = null,
    @Volatile var status: TcpStatus = TcpStatus.SYN_RECEIVED,
    val outboundQueue: KChannel<ByteArray> = KChannel(KChannel.UNLIMITED),
    val writeLock: ReentrantLock = ReentrantLock()
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
            tcp.isFIN               -> handleFin(connKey)
            tcp.isRST               -> handleRst(connKey)
        }
    }

    private fun handleSyn(packet: ParsedPacket, tcp: TCPHeader, connKey: String) {
        val existing = connections.remove(connKey)
        if (existing != null) {
            existing.outboundQueue.close()
            try { existing.channel?.close() } catch (_: Exception) {}
        }

        val srcIp = packet.ip.srcAddr.address
        val dstIp = packet.ip.dstAddr.address

        val entry = ConnectionEntry(
            id = connKey,
            protocol = Protocol.TCP,
            srcPort = tcp.srcPort,
            dstIp = packet.ip.dstAddr.hostAddress ?: "?",
            dstPort = tcp.dstPort,
            status = ConnectionStatus.CONNECTING
        )
        onConnectionEvent(entry)

        val conn = TcpConnState(
            connId = connKey,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = tcp.srcPort,
            dstPort = tcp.dstPort,
            localSeq = (Math.random() * 0xFFFFFFFFL).toLong(),
            remoteSeq = (tcp.seqNum + 1L) and 0xFFFFFFFFL
        )
        connections[connKey] = conn

        sendSynAck(conn, tcp.seqNum)

        val serverAddr = packet.ip.dstAddr
        val serverPort = tcp.dstPort
        scope.launch {
            try {
                val channel = SocketChannel.open()
                channel.configureBlocking(false)
                vpnService.protect(channel.socket())
                channel.connect(InetSocketAddress(serverAddr, serverPort))

                var attempts = 0
                while (!channel.finishConnect() && attempts++ < 200) delay(10)

                if (!channel.isConnected) {
                    Log.w(TAG, "Connection to $serverAddr:$serverPort timed out")
                    channel.close()
                    cleanup(connKey)
                    return@launch
                }

                channel.configureBlocking(true)

                conn.channel = channel
                conn.status = TcpStatus.ESTABLISHED
                onStatusChange(connKey, ConnectionStatus.ACTIVE)
                Log.d(TAG, "TCP connection established: $connKey")

                launch { writerLoop(conn) }
                launch { readerLoop(conn) }

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: $connKey", e)
                cleanup(connKey)
            }
        }
    }

    private fun handleAck(packet: ParsedPacket, tcp: TCPHeader, connKey: String) {
        val conn = connections[connKey] ?: return

        if (conn.status == TcpStatus.SYN_RECEIVED) return

        if (packet.payload.isEmpty()) return

        conn.remoteSeq = (tcp.seqNum + packet.payload.size.toLong()) and 0xFFFFFFFFL
        sendAck(conn)

        onMessage(conn.connId, LiveMessage(LiveMessage.Direction.OUTBOUND, packet.payload.clone()))
        conn.outboundQueue.trySend(packet.payload)
    }

    private fun handleFin(connKey: String) {
        val conn = connections[connKey] ?: return
        conn.status = TcpStatus.FIN_WAIT
        onStatusChange(conn.connId, ConnectionStatus.CLOSING)
        conn.outboundQueue.close()
        scope.launch {
            try { conn.channel?.close() } catch (_: Exception) {}
            cleanup(connKey)
        }
    }

    private fun handleRst(connKey: String) {
        val conn = connections.remove(connKey) ?: return
        conn.status = TcpStatus.CLOSED
        conn.outboundQueue.close()
        onStatusChange(conn.connId, ConnectionStatus.CLOSED)
        scope.launch { try { conn.channel?.close() } catch (_: Exception) {} }
    }

    private suspend fun writerLoop(conn: TcpConnState) {
        val ch = conn.channel ?: return
        try {
            for (data in conn.outboundQueue) {
                val buf = ByteBuffer.wrap(data)
                conn.writeLock.lock()
                try {
                    while (buf.hasRemaining()) {
                        ch.write(buf)
                    }
                } finally {
                    conn.writeLock.unlock()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "writerLoop error: ${conn.key}", e)
            cleanup(conn.key)
        }
    }

    private suspend fun readerLoop(conn: TcpConnState) {
        val ch = conn.channel ?: return
        val buf = ByteBuffer.allocate(32768)
        try {
            while (conn.status == TcpStatus.ESTABLISHED) {
                buf.clear()
                val read = withContext(Dispatchers.IO) { ch.read(buf) }
                if (read == -1) {
                    Log.d(TAG, "Server closed: ${conn.key}")
                    sendFin(conn)
                    cleanup(conn.key)
                    break
                }
                if (read <= 0) continue

                buf.flip()
                val data = ByteArray(read).also { buf.get(it) }

                sendDataToApp(conn, data)
                onMessage(conn.connId, LiveMessage(LiveMessage.Direction.INBOUND, data))
            }
        } catch (e: Exception) {
            if (conn.status == TcpStatus.ESTABLISHED) {
                Log.e(TAG, "readerLoop error: ${conn.key}", e)
            }
            cleanup(conn.key)
        }
    }

    private fun sendDataToApp(conn: TcpConnState, data: ByteArray) {
        val seq = conn.localSeq
        val ack = conn.remoteSeq
        val pkt = PacketParser.buildIPv4TCPPacket(
            srcIp = conn.dstIp,
            dstIp = conn.srcIp,
            srcPort = conn.dstPort,
            dstPort = conn.srcPort,
            seq = seq,
            ack = ack,
            flags = 0x18,
            window = 65535,
            payload = data
        )
        conn.localSeq = (seq + data.size.toLong()) and 0xFFFFFFFFL
        writeToVpn(pkt)
    }

    private fun sendSynAck(conn: TcpConnState, remoteSynSeq: Long) {
        val pkt = PacketParser.buildIPv4TCPPacket(
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
        writeToVpn(pkt)
    }

    private fun sendAck(conn: TcpConnState) {
        val pkt = PacketParser.buildIPv4TCPPacket(
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
        writeToVpn(pkt)
    }

    private fun sendFin(conn: TcpConnState) {
        val pkt = PacketParser.buildIPv4TCPPacket(
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
        writeToVpn(pkt)
    }

    private fun writeToVpn(data: ByteArray) {
        try {
            outStream.write(data)
        } catch (e: Exception) {
            Log.e(TAG, "writeToVpn failed", e)
        }
    }

    private fun cleanup(connKey: String) {
        val conn = connections.remove(connKey) ?: return
        conn.status = TcpStatus.CLOSED
        conn.outboundQueue.close()
        onStatusChange(conn.connId, ConnectionStatus.CLOSED)
        try { conn.channel?.close() } catch (_: Exception) {}
    }

    fun shutdown() {
        scope.cancel()
        connections.values.forEach {
            it.outboundQueue.close()
            try { it.channel?.close() } catch (_: Exception) {}
        }
        connections.clear()
    }
}
