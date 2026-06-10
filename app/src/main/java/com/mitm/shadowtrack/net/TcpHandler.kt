package com.mitm.shadowtrack.net

import android.net.VpnService
import com.mitm.shadowtrack.model.*
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
    // Outbound data queue — ensures writes to the real server are serialized
    val outboundQueue: KChannel<ByteArray> = KChannel(KChannel.UNLIMITED),
    var awaitingWsHandshake: Boolean = false,
    var isWebSocket: Boolean = false,
    val inboundWsBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    val outboundWsBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    // SF3 stream reassembly buffers — the server's large responses (e.g. finish_fight ACK
    // is 33 KB decompressed / 12 KB compressed) arrive in multiple TCP segments.
    // We must buffer raw bytes and emit ONLY complete SF3 frames to the parser.
    val inboundSf3Buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    val outboundSf3Buffer: ByteArrayOutputStream = ByteArrayOutputStream()
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

        // Reply with SYN-ACK immediately so the app can proceed
        sendSynAck(conn, tcp.seqNum)

        // Connect to the real destination, then start relay loops
        scope.launch {
            try {
                val channel = SocketChannel.open()
                // Keep non-blocking just for the connect phase, then switch to blocking
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

                // Switch to blocking — write() will block until ALL bytes are written,
                // completely eliminating the "partial write silently drops data" bug.
                channel.configureBlocking(true)

                conn.channel = channel
                conn.status = TcpStatus.ESTABLISHED
                onStatusChange(connKey, ConnectionStatus.ACTIVE)

                // Start the two relay loops
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

        // Update remote sequence — used as ACK number when we reply
        conn.remoteSeq = (tcp.seqNum + packet.payload.size.toLong()) and 0xFFFFFFFFL

        // Send an immediate ACK back to the app so its retransmit timer doesn't fire
        sendAck(conn)

        if (!conn.isWebSocket) {
            // Detect the outbound HTTP Upgrade request
            val text = String(packet.payload, Charsets.ISO_8859_1)
            if (text.contains("Upgrade: websocket", ignoreCase = true) ||
                text.contains("Upgrade: WebSocket", ignoreCase = true)) {
                conn.awaitingWsHandshake = true
            }
            // Buffer and reassemble SF3 frames — large packets (e.g. get_player at 9 KB
            // compressed) arrive across many TCP segments; passing a partial segment to the
            // parser returns null and we'd lose counter tracking and battle detection.
            conn.outboundSf3Buffer.write(packet.payload)
            parseSf3Frames(conn.connId, conn.outboundSf3Buffer, LiveMessage.Direction.OUTBOUND)
        } else {
            // WS is established — buffer and parse outbound frames (client→server, masked)
            conn.outboundWsBuffer.write(packet.payload)
            parseWsFrames(conn.connId, conn.outboundWsBuffer, LiveMessage.Direction.OUTBOUND)
        }

        // Queue the raw bytes for the writer loop to send to the real server
        conn.outboundQueue.trySend(packet.payload)
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

    /**
     * Drains the outbound queue and writes each chunk to the real server socket.
     * Runs on a single coroutine → no concurrent writes, no interleaving.
     * Channel is blocking → write() sends all bytes before returning.
     */
    private suspend fun writerLoop(conn: TcpConnState) {
        val ch = conn.channel ?: return
        try {
            for (data in conn.outboundQueue) {
                val buf = ByteBuffer.wrap(data)
                while (buf.hasRemaining()) {
                    ch.write(buf)   // blocking: retries internally until buffer drains
                }
            }
        } catch (_: Exception) {
            cleanup(conn.key)
        }
    }

    /**
     * Reads from the real server and injects the response back into the VPN TUN.
     * Also handles the HTTP→WS upgrade detection in the server's 101 response.
     */
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

                // Push data back to the app through the TUN interface
                sendDataToApp(conn, data)

                if (!conn.isWebSocket && conn.awaitingWsHandshake) {
                    val text = String(data, Charsets.ISO_8859_1)
                    if (text.contains("101 Switching Protocols", ignoreCase = true)) {
                        conn.isWebSocket = true
                        conn.awaitingWsHandshake = false
                        onWebSocket(conn.connId)
                        // The 101 response itself is logged as the handshake message
                        onMessage(conn.connId, LiveMessage(LiveMessage.Direction.INBOUND, data))
                        continue
                    }
                }

                if (conn.isWebSocket) {
                    conn.inboundWsBuffer.write(data)
                    parseWsFrames(conn.connId, conn.inboundWsBuffer, LiveMessage.Direction.INBOUND)
                } else {
                    // Buffer and reassemble SF3 frames.
                    // The server's finish_fight ACK is 12 KB compressed / 33 KB decompressed
                    // and arrives across 3 separate TCP reads.  Passing a raw partial chunk
                    // to the parser returns null (extractPayload checks data.size < 5+len)
                    // so WinConfirmed is never emitted.  We accumulate bytes here and only
                    // call onMessage once a COMPLETE frame is available.
                    conn.inboundSf3Buffer.write(data)
                    parseSf3Frames(conn.connId, conn.inboundSf3Buffer, LiveMessage.Direction.INBOUND)
                }
            }
        } catch (_: Exception) {
            cleanup(conn.key)
        }
    }

    /**
     * Parses as many complete WebSocket frames as possible from [buffer].
     * Any leftover incomplete bytes stay in the buffer for the next read.
     *
     * Frame layout:
     *   Byte 0: FIN(1) RSV(3) Opcode(4)
     *   Byte 1: MASK(1) PayloadLen(7)  [126→next 2B, 127→next 8B]
     *   Masking key (4 bytes, only if MASK=1)
     *   Payload (XOR'd with masking key if masked)
     */
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

            if (offset + totalFrame > raw.size) break  // incomplete frame, wait for more data

            val payload = ByteArray(payloadLen.toInt())
            if (masked) {
                val key = raw.slice(maskOffset until maskOffset + 4)
                for (i in payload.indices) {
                    payload[i] = (raw[dataOffset + i].toInt() xor (key[i % 4].toInt() and 0xFF)).toByte()
                }
            } else {
                System.arraycopy(raw, dataOffset, payload, 0, payload.size)
            }

            // Emit for data frames (continuation=0, text=1, binary=2)
            if (opcode in 0..2 && payload.isNotEmpty()) {
                onMessage(connId, LiveMessage(dir, payload))
            }

            offset += totalFrame
        }

        // Keep any leftover bytes for the next read
        if (offset < raw.size) buffer.write(raw, offset, raw.size - offset)
    }

    /**
     * Buffers raw bytes from [buffer] and emits each COMPLETE SF3 frame to [onMessage].
     * Leftover incomplete bytes stay in [buffer] for the next call.
     *
     * SF3 framing:
     *   Small  0x01 + 1B-len + payload          total = 2 + len
     *   Large  0x02 + 4B-LE-len + compressed    total = 5 + compLen
     *
     * This is the key fix for WinConfirmed never firing: the server's finish_fight
     * response is 12 KB compressed / 33 KB decompressed and arrives across 3 TCP reads.
     * Without this buffer, each partial read is passed to extractPayload which checks
     * `data.size < 5 + compLen` and returns null, silently dropping the response.
     */
    private fun parseSf3Frames(
        connId: String,
        buffer: ByteArrayOutputStream,
        dir: LiveMessage.Direction
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
                    onMessage(connId, LiveMessage(dir, raw.copyOfRange(pos, pos + 2 + len)))
                    pos += 2 + len
                }
                0x02 -> {
                    if (pos + 5 > raw.size) break
                    val compLen = ByteBuffer.wrap(raw, pos + 1, 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    if (compLen <= 0 || pos + 5 + compLen > raw.size) break
                    onMessage(connId, LiveMessage(dir, raw.copyOfRange(pos, pos + 5 + compLen)))
                    pos += 5 + compLen
                }
                else -> {
                    // Unknown frame type — skip one byte and try to re-sync
                    pos++
                }
            }
        }

        // Keep any incomplete bytes for the next read
        if (pos < raw.size) buffer.write(raw, pos, raw.size - pos)
    }

    // ── Packet building helpers ───────────────────────────────────────────────

    private fun sendDataToApp(conn: TcpConnState, data: ByteArray) {
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
            flags = 0x12, // SYN + ACK
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
            flags = 0x10, // ACK
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
            flags = 0x11, // FIN + ACK
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

    /**
     * Inject raw SF3-framed bytes into the outbound stream of [connId].
     *
     * If the connection is a WebSocket the raw bytes are automatically wrapped
     * in a masked WS binary frame — the server expects properly-framed WS data
     * and silently discards bare proto bytes.
     *
     * Returns a diagnostic string describing the outcome, or null if the connId
     * was not found or the connection was not ESTABLISHED.
     */
    fun injectToServer(connId: String, data: ByteArray): String? {
        val conn = connections[connId]
            ?: return "FAIL: connId not in connections (${connections.size} conns tracked)"
        if (conn.status != TcpStatus.ESTABLISHED)
            return "FAIL: conn.status=${conn.status} (not ESTABLISHED)"
        val payload = if (conn.isWebSocket) wrapInWsFrame(data) else data
        conn.outboundQueue.trySend(payload)
        // Do NOT call onMessage here — calling it from the main thread (overlay) while
        // readerLoop calls it from IO causes a concurrent access pattern that silently
        // crashes readerLoop, after which ALL inbound messages stop being logged.
        return "QUEUED  ws=${conn.isWebSocket}"
    }

    /**
     * Inject to the first ESTABLISHED connection (last-resort fallback).
     * Returns a diagnostic string, or null if no ESTABLISHED connection exists.
     */
    fun injectToAny(data: ByteArray): String? {
        val conn = connections.values.firstOrNull { it.status == TcpStatus.ESTABLISHED }
            ?: return "FAIL: injectToAny found no ESTABLISHED conn (${connections.size} conns)"
        val payload = if (conn.isWebSocket) wrapInWsFrame(data) else data
        conn.outboundQueue.trySend(payload)
        return "QUEUED via any  id=…${conn.connId.takeLast(16)}  ws=${conn.isWebSocket}"
    }

    /**
     * Wraps [payload] in a WebSocket binary frame (opcode 0x2) with a random
     * 4-byte masking key, as required for client→server WS messages (RFC 6455).
     *
     * Frame layout:
     *   0x82               FIN=1, opcode=2 (binary)
     *   0x80 | len7        MASK=1, 7-bit length  (126/127 extended for larger payloads)
     *   [2B extended len]  if 126 ≤ len ≤ 65535
     *   [4B masking key]
     *   [masked payload]
     */
    private fun wrapInWsFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        val maskKey = ByteArray(4).also { java.util.Random().nextBytes(it) }

        val header = java.io.ByteArrayOutputStream()
        header.write(0x82)                          // FIN + binary opcode
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
        header.write(maskKey)                       // masking key

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
