package com.nexora.hammerscale.net

import android.net.VpnService
import com.nexora.hammerscale.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as KChannel
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
    val outboundQueue: KChannel<ByteArray> = KChannel(KChannel.UNLIMITED),
    val writeLock: java.util.concurrent.locks.ReentrantLock = java.util.concurrent.locks.ReentrantLock(),
    var awaitingWsHandshake: Boolean = false,
    var isWebSocket: Boolean = false,
    val inboundWsBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    val outboundWsBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    val inboundSf3Buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    val outboundSf3Buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    var inboundResyncBytes: Int = 0
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
    private val onWebSocket: (String) -> Unit = {},
    private val onClanRounds: (Int) -> Unit = {},
    private val onBattleSeq: (Int) -> Unit = {}
) {
    private val connections = ConcurrentHashMap<String, TcpConnState>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outStream = FileOutputStream(vpnFd)

    private val interceptArmed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val interceptRounds = java.util.concurrent.atomic.AtomicInteger(3)

    fun armIntercept(roundsToWin: Int = 3) {
        interceptRounds.set(roundsToWin.coerceIn(1, 127))
        interceptArmed.set(true)
    }

    fun disarmIntercept() { interceptArmed.set(false) }

    private val clanInterceptArmed  = java.util.concurrent.atomic.AtomicBoolean(false)
    private val clanInterceptRounds = java.util.concurrent.atomic.AtomicInteger(2)

    fun disarmClanIntercept() { clanInterceptArmed.set(false) }

    private val raidInterceptArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun armRaidIntercept()    { raidInterceptArmed.set(true) }
    fun disarmRaidIntercept() { raidInterceptArmed.set(false) }

    private val brawlerInterceptArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun armBrawlerIntercept() {
        brawlerInterceptArmed.set(true)
        android.util.Log.d("HammerBrawler", "TcpHandler.armBrawlerIntercept: flag SET")
    }
    fun disarmBrawlerIntercept() {
        brawlerInterceptArmed.set(false)
        android.util.Log.d("HammerBrawler", "TcpHandler.disarmBrawlerIntercept: flag CLEARED")
    }

    private fun extractCommandName(frame: ByteArray): String? {
        return try {
            val proto = GameProtocolParser.extractPayload(frame) ?: return null
            val fields = GameProtocolParser.readProtoFields(proto)
            val cmdBytes = fields[2] as? ByteArray ?: return null
            val cmd = cmdBytes.toString(Charsets.UTF_8)
            if (cmd.isNotBlank()) cmd else null
        } catch (_: Exception) { null }
    }

    private fun makeMessage(dir: LiveMessage.Direction, data: ByteArray, cmdName: String?): LiveMessage {
        return LiveMessage(dir, data, commandName = cmdName)
    }

    private fun sniffClanStart(frame: ByteArray) {
        val rounds = GameProtocolParser.extractClanRoundsFromStartResponse(frame) ?: return
        clanInterceptRounds.set(rounds)
        clanInterceptArmed.set(true)
        onClanRounds(rounds)
    }

    private fun sniffEventBattleStart(frame: ByteArray) {
        val seq = GameProtocolParser.extractBattleSeqFromServerStart(frame) ?: return
        onBattleSeq(seq)
    }

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

        scope.launch {
            try {
                val channel = SocketChannel.open()
                channel.configureBlocking(false)
                vpnService.protect(channel.socket())
                channel.connect(InetSocketAddress(packet.ip.dstAddr, tcp.dstPort))

                var attempts = 0
                while (!channel.finishConnect() && attempts++ < 100) delay(10)

                if (!channel.isConnected) {
                    channel.close()
                    cleanup(connKey)
                    return@launch
                }

                channel.configureBlocking(true)

                conn.channel = channel
                conn.status = TcpStatus.ESTABLISHED
                onStatusChange(connKey, ConnectionStatus.ACTIVE)

                launch { writerLoop(conn) }
                launch { readerLoop(conn) }

            } catch (e: Exception) {
                cleanup(connKey)
            }
        }
    }

    private fun handleAck(packet: ParsedPacket, tcp: TCPHeader, connKey: String) {
        val conn = connections[connKey] ?: return
        if (packet.payload.isEmpty()) return

        conn.remoteSeq = (tcp.seqNum + packet.payload.size.toLong()) and 0xFFFFFFFFL

        sendAck(conn)

        if (!conn.isWebSocket) {
            val text = String(packet.payload, Charsets.ISO_8859_1)
            if (text.contains("Upgrade: websocket", ignoreCase = true) ||
                text.contains("Upgrade: WebSocket", ignoreCase = true)) {
                conn.awaitingWsHandshake = true
            }

            var payloadForServer = packet.payload
            if (interceptArmed.get() && GameProtocolParser.tryExtractFinishFight(packet.payload) != null) {
                interceptArmed.set(false)
                val patched = PacketInjector.patchFinishFightToWin(packet.payload, interceptRounds.get())
                if (patched == null) {
                    onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND,
                        "PATCH FAILED: field[4] not found — hex: ${packet.payload.joinToString(" ") { "%02x".format(it) }}".toByteArray()))
                    return
                }
                payloadForServer = patched
            }

            if (clanInterceptArmed.get() && GameProtocolParser.tryExtractClanFinishFight(packet.payload) != null) {
                clanInterceptArmed.set(false)
                val patched = PacketInjector.patchFinishFightToWin(packet.payload, clanInterceptRounds.get())
                if (patched == null) {
                    onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND,
                        "CLAN PATCH FAILED: field[4] not found — hex: ${packet.payload.joinToString(" ") { "%02x".format(it) }}".toByteArray()))
                    return
                }
                payloadForServer = patched
            }

            if (raidInterceptArmed.get() && GameProtocolParser.tryExtractRaidFightFinish(payloadForServer)) {
                raidInterceptArmed.set(false)
                val patched = PacketInjector.patchRaidFightFinishToMaxDamage(payloadForServer)
                if (patched != null) {
                    payloadForServer = patched
                } else {
                    onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND,
                        "RAID PATCH FAILED — hex: ${payloadForServer.joinToString(" ") { "%02x".format(it) }}".toByteArray()))
                }
            }

            if (brawlerInterceptArmed.get() && GameProtocolParser.tryExtractBrawlerFinish(payloadForServer)) {
                brawlerInterceptArmed.set(false)
                val patched = PacketInjector.patchBrawlerFinishToWin(payloadForServer)
                if (patched != null) {
                    payloadForServer = patched
                } else {
                    onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND,
                        "BRAWLER PATCH FAILED — hex: ${payloadForServer.joinToString(" ") { "%02x".format(it) }}".toByteArray()))
                }
            }

            conn.outboundSf3Buffer.write(payloadForServer)
            parseSf3Frames(conn.connId, conn.outboundSf3Buffer, LiveMessage.Direction.OUTBOUND)

            conn.outboundQueue.trySend(payloadForServer)
        } else {
            conn.outboundWsBuffer.write(packet.payload)
            parseWsFrames(conn.connId, conn.outboundWsBuffer, LiveMessage.Direction.OUTBOUND)
            conn.outboundQueue.trySend(packet.payload)
        }
    }

    private fun handleFin(connKey: String) {
        val conn = connections[connKey] ?: return
        conn.status = TcpStatus.FIN_WAIT
        onStatusChange(conn.connId, ConnectionStatus.CLOSING)
        conn.outboundQueue.close()
        scope.launch {
            conn.channel?.close()
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
        } catch (_: Exception) {
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
                    sendFin(conn)
                    cleanup(conn.key)
                    break
                }
                if (read <= 0) continue

                buf.flip()
                val data = ByteArray(read).also { buf.get(it) }

                val dataToForward: ByteArray = if (!conn.isWebSocket && brawlerInterceptArmed.get()) {
                    val patched = PacketInjector.patchInboundBrawlerFinishToWin(data)
                    if (patched != null) {
                        brawlerInterceptArmed.set(false)
                        android.util.Log.d("HammerBrawler", "readerLoop: inbound patch FIRED on conn=${conn.connId} size=${data.size}→${patched.size}")
                        patched
                    } else {
                        android.util.Log.d("HammerBrawler", "readerLoop: armed, checked ${data.size}B on conn=${conn.connId}, no brawler_finish frame yet")
                        data
                    }
                } else {
                    if (brawlerInterceptArmed.get()) {
                        android.util.Log.d("HammerBrawler", "readerLoop: armed but conn=${conn.connId} isWebSocket=${conn.isWebSocket}, skipping check")
                    }
                    data
                }

                sendDataToApp(conn, dataToForward)

                if (!conn.isWebSocket && conn.awaitingWsHandshake) {
                    val text = String(data, Charsets.ISO_8859_1)
                    if (text.contains("101 Switching Protocols", ignoreCase = true)) {
                        conn.isWebSocket = true
                        conn.awaitingWsHandshake = false
                        onWebSocket(conn.connId)
                        onMessage(conn.connId, LiveMessage(LiveMessage.Direction.INBOUND, data))
                        continue
                    }
                }

                if (conn.isWebSocket) {
                    conn.inboundWsBuffer.write(data)
                    parseWsFrames(conn.connId, conn.inboundWsBuffer, LiveMessage.Direction.INBOUND)
                } else {
                    conn.inboundSf3Buffer.write(dataToForward)
                    parseSf3Frames(conn.connId, conn.inboundSf3Buffer, LiveMessage.Direction.INBOUND, conn)
                }
            }
        } catch (_: Exception) {
            cleanup(conn.key)
        }
    }

    private fun parseWsFrames(connId: String, buffer: ByteArrayOutputStream, dir: LiveMessage.Direction) {
        val raw = buffer.toByteArray()
        buffer.reset()

        var offset = 0
        while (offset < raw.size) {
            if (offset + 2 > raw.size) break

            val b0 = raw[offset].toInt() and 0xFF
            val b1 = raw[offset + 1].toInt() and 0xFF
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()
            var headerSize = 2

            when {
                payloadLen == 126L -> {
                    if (offset + 4 > raw.size) break
                    payloadLen = ((raw[offset + 2].toInt() and 0xFF) shl 8 or
                                  (raw[offset + 3].toInt() and 0xFF)).toLong()
                    headerSize = 4
                }
                payloadLen == 127L -> {
                    if (offset + 10 > raw.size) break
                    payloadLen = 0L
                    for (i in 0..7) payloadLen = (payloadLen shl 8) or (raw[offset + 2 + i].toInt() and 0xFF).toLong()
                    headerSize = 10
                }
            }

            val maskOffset = offset + headerSize
            val dataOffset = maskOffset + if (masked) 4 else 0
            val totalFrame = (dataOffset - offset) + payloadLen.toInt()

            if (offset + totalFrame > raw.size) break

            val payload = ByteArray(payloadLen.toInt())
            if (masked) {
                val key = raw.slice(maskOffset until maskOffset + 4)
                for (i in payload.indices) {
                    payload[i] = (raw[dataOffset + i].toInt() xor (key[i % 4].toInt() and 0xFF)).toByte()
                }
            } else {
                System.arraycopy(raw, dataOffset, payload, 0, payload.size)
            }

            if (opcode in 0..2 && payload.isNotEmpty()) {
                val cmdName = extractCommandName(payload)
                onMessage(connId, makeMessage(dir, payload, cmdName))
            }

            offset += totalFrame
        }

        if (offset < raw.size) buffer.write(raw, offset, raw.size - offset)
    }

    companion object {
        private const val MAX_RESYNC_BYTES = 64
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }

    private fun parseSf3Frames(
        connId: String,
        buffer: ByteArrayOutputStream,
        dir: LiveMessage.Direction,
        conn: TcpConnState? = null
    ) {
        val raw = buffer.toByteArray()
        buffer.reset()

        var pos = 0
        while (pos < raw.size) {
            val t = raw[pos].toInt() and 0xFF
            when (t) {
                0x01 -> {
                    if (pos + 2 > raw.size) break
                    val len = raw[pos + 1].toInt() and 0xFF
                    if (pos + 2 + len > raw.size) break
                    conn?.inboundResyncBytes = 0
                    val frame01 = raw.copyOfRange(pos, pos + 2 + len)
                    val cmdName = extractCommandName(frame01)
                    onMessage(connId, makeMessage(dir, frame01, cmdName))
                    if (dir == LiveMessage.Direction.INBOUND) {
                        sniffClanStart(frame01)
                        sniffEventBattleStart(frame01)
                    }
                    pos += 2 + len
                }
                0x02 -> {
                    if (pos + 5 > raw.size) break
                    val compLen = ByteBuffer.wrap(raw, pos + 1, 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
                    when {
                        compLen <= 0 || compLen > MAX_FRAME_BYTES -> {
                            if (conn != null) {
                                conn.inboundResyncBytes++
                                if (conn.inboundResyncBytes > MAX_RESYNC_BYTES) {
                                    conn.inboundResyncBytes = 0
                                    return
                                }
                            }
                            pos++
                        }
                        pos + 5 + compLen > raw.size -> break
                        else -> {
                            conn?.inboundResyncBytes = 0
                            val frame02 = raw.copyOfRange(pos, pos + 5 + compLen)
                            val cmdName = extractCommandName(frame02)
                            onMessage(connId, makeMessage(dir, frame02, cmdName))
                            if (dir == LiveMessage.Direction.INBOUND) {
                                sniffClanStart(frame02)
                                sniffEventBattleStart(frame02)
                            }
                            pos += 5 + compLen
                        }
                    }
                }
                else -> {
                    if (conn != null && dir == LiveMessage.Direction.INBOUND) {
                        conn.inboundResyncBytes++
                        if (conn.inboundResyncBytes > MAX_RESYNC_BYTES) {
                            conn.inboundResyncBytes = 0
                            return
                        }
                    }
                    pos++
                }
            }
        }

        if (pos < raw.size) buffer.write(raw, pos, raw.size - pos)
    }

    private fun sendDataToApp(conn: TcpConnState, data: ByteArray) {
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
        conn.localSeq = (conn.localSeq + data.size.toLong()) and 0xFFFFFFFFL
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
        try { outStream.write(data) } catch (_: Exception) {}
    }

    private fun cleanup(connKey: String) {
        val conn = connections.remove(connKey) ?: return
        conn.status = TcpStatus.CLOSED
        conn.outboundQueue.close()
        onStatusChange(conn.connId, ConnectionStatus.CLOSED)
        try { conn.channel?.close() } catch (_: Exception) {}
    }

    fun injectDirect(connId: String, data: ByteArray): String {
        val conn = connections[connId]
            ?: return "FAIL: connId not in connections (${connections.size} active)"
        if (conn.status != TcpStatus.ESTABLISHED)
            return "FAIL: status=${conn.status}"
        val ch = conn.channel
            ?: return "FAIL: channel is null"
        if (!ch.isOpen || !ch.isConnected)
            return "FAIL: channel closed/disconnected"
        val payload = if (conn.isWebSocket) wrapInWsFrame(data) else data
        return try {
            conn.writeLock.lock()
            val result = try {
                val buf = java.nio.ByteBuffer.wrap(payload)
                while (buf.hasRemaining()) ch.write(buf)
                "SENT ${payload.size}B  ws=${conn.isWebSocket}"
            } finally {
                conn.writeLock.unlock()
            }
            onMessage(conn.connId, LiveMessage(LiveMessage.Direction.OUTBOUND, data))
            result
        } catch (e: Exception) {
            "FAIL: write error: ${e.message}"
        }
    }

    fun injectDirectToAny(data: ByteArray): String {
        val conn = connections.values.firstOrNull { it.status == TcpStatus.ESTABLISHED }
            ?: return "FAIL: no ESTABLISHED conn (${connections.size} tracked)"
        return injectDirect(conn.connId, data)
    }

    fun injectToServer(connId: String, data: ByteArray): String? {
        val conn = connections[connId]
            ?: return "FAIL: connId not in connections (${connections.size} conns tracked)"
        if (conn.status != TcpStatus.ESTABLISHED)
            return "FAIL: conn.status=${conn.status} (not ESTABLISHED)"
        val payload = if (conn.isWebSocket) wrapInWsFrame(data) else data
        val result = conn.outboundQueue.trySend(payload)
        return if (result.isSuccess) "QUEUED  ws=${conn.isWebSocket}"
        else "FAIL: outboundQueue closed (writerLoop died)"
    }

    fun injectToAny(data: ByteArray): String? {
        val conn = connections.values.firstOrNull { it.status == TcpStatus.ESTABLISHED }
            ?: return "FAIL: injectToAny found no ESTABLISHED conn (${connections.size} conns)"
        val payload = if (conn.isWebSocket) wrapInWsFrame(data) else data
        val result = conn.outboundQueue.trySend(payload)
        return if (result.isSuccess) "QUEUED via any  id=…${conn.connId.takeLast(16)}  ws=${conn.isWebSocket}"
        else "FAIL: queue closed on conn …${conn.connId.takeLast(16)}"
    }

    private fun wrapInWsFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        val maskKey = ByteArray(4).also { java.util.Random().nextBytes(it) }

        val header = java.io.ByteArrayOutputStream()
        header.write(0x82)
        when {
            len <= 125   -> header.write(0x80 or len)
            len <= 65535 -> {
                header.write(0x80 or 126)
                header.write((len shr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else         -> {
                header.write(0x80 or 127)
                for (i in 7 downTo 0) header.write((len.toLong() shr (i * 8)).toInt() and 0xFF)
            }
        }
        header.write(maskKey)

        val masked = ByteArray(len) { i -> (payload[i].toInt() xor (maskKey[i % 4].toInt() and 0xFF)).toByte() }
        return header.toByteArray() + masked
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
