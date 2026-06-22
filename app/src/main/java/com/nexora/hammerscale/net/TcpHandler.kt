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
    // Outbound data queue — ensures writes to the real server are serialized
    val outboundQueue: KChannel<ByteArray> = KChannel(KChannel.UNLIMITED),
    // Write lock — held by writerLoop during each write; also held by injectDirect
    // so injected bytes never interleave with queued bytes mid-packet.
    val writeLock: java.util.concurrent.locks.ReentrantLock = java.util.concurrent.locks.ReentrantLock(),
    var awaitingWsHandshake: Boolean = false,
    var isWebSocket: Boolean = false,
    val inboundWsBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    val outboundWsBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    // SF3 stream reassembly buffers — the server's large responses (e.g. finish_fight ACK
    // is 33 KB decompressed / 12 KB compressed) arrive in multiple TCP segments.
    // We must buffer raw bytes and emit ONLY complete SF3 frames to the parser.
    val inboundSf3Buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    val outboundSf3Buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    // Counts consecutive unknown-frame-type bytes seen in parseSf3Frames.
    // Once this exceeds MAX_RESYNC_BYTES the buffer is wiped and resync counter reset
    // so a single bad chunk never permanently blocks all future inbound events.
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

    // ── Intercept-and-replace flag ────────────────────────────────────────
    // When set, the next outbound event_battle_finish_fight packet is silently
    // replaced with a crafted WIN packet before being forwarded to the server.
    // This is the correct MITM approach: the game client sent the request so its
    // state machine is in "waiting for server response" mode → the server's WIN
    // response is processed normally and the win screen is shown.
    private val interceptArmed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val interceptRounds = java.util.concurrent.atomic.AtomicInteger(3)

    /** Arm the finish_fight intercept. The next outbound finish_fight is replaced with a WIN
     *  using [roundsToWin] for both field[5] (wonRounds) and field[7] (totalRounds). */
    fun armIntercept(roundsToWin: Int = 3) {
        interceptRounds.set(roundsToWin.coerceIn(1, 127))
        interceptArmed.set(true)
    }

    /** Disarm without firing (e.g. user cancelled or battle ended without a finish_fight). */
    fun disarmIntercept() { interceptArmed.set(false) }

    // ── Clan battle auto-intercept ────────────────────────────────────────
    // Auto-armed when the server's clan_start_fight response is sniffed inbound.
    // Rounds are read directly from the server's battle config (field[10] in the
    // nested response proto) rather than from any JSON lookup.
    // When armed, the next outbound clan_finish_fight is patched to WIN (same
    // surgical approach as the event_battle intercept above).
    private val clanInterceptArmed  = java.util.concurrent.atomic.AtomicBoolean(false)
    private val clanInterceptRounds = java.util.concurrent.atomic.AtomicInteger(2)

    /** Disarm the clan intercept without firing. */
    fun disarmClanIntercept() { clanInterceptArmed.set(false) }

    // ── Raid damage intercept ─────────────────────────────────────────────
    // When armed, the next outbound raid_fight_finish is patched so that
    // params.field[2] fixed32 = 1.0, meaning 100% of boss HP dealt (boss killed).
    private val raidInterceptArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun armRaidIntercept()    { raidInterceptArmed.set(true) }
    fun disarmRaidIntercept() { raidInterceptArmed.set(false) }

    // ── Brawler WIN intercept ─────────────────────────────────────────────
    // When armed, the next outbound brawler_finish is fully rebuilt as a WIN.
    // The inner proto is completely replaced (WIN has 7 fields vs LOSS's 4)
    // rather than surgically patched — see PacketInjector.patchBrawlerFinishToWin.
    private val brawlerInterceptArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun armBrawlerIntercept() {
        brawlerInterceptArmed.set(true)
        android.util.Log.d("HammerBrawler", "TcpHandler.armBrawlerIntercept: flag SET")
    }
    fun disarmBrawlerIntercept() {
        brawlerInterceptArmed.set(false)
        android.util.Log.d("HammerBrawler", "TcpHandler.disarmBrawlerIntercept: flag CLEARED")
    }

    /**
     * Called from parseSf3Frames for every INBOUND complete frame.
     * If [frame] is the server's clan_start_fight response, extracts the round
     * count and arms the clan intercept so the next outbound clan_finish_fight
     * is automatically patched to WIN with the correct round value.
     */
    /**
     * Extracts the SF3 command name from a framed packet (0x01 or 0x02 prefix).
     * Returns null if the command cannot be determined (unknown/unparsed).
     */
    private fun extractCommandName(frame: ByteArray): String? {
        return try {
            val proto = GameProtocolParser.extractPayload(frame) ?: return null
            val fields = GameProtocolParser.readProtoFields(proto)
            val cmdBytes = fields[2] as? ByteArray ?: return null
            val cmd = cmdBytes.toString(Charsets.UTF_8)
            // Only return if it's a meaningful command (not empty or whitespace)
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

    /**
     * Called for every inbound frame. If this is the server's event_battle_start_fight
     * response, extracts the 0-indexed sub-battle sequence number (field[3] in params)
     * and reports it via [onBattleSeq].
     *
     * field[3] absent → sub-battle 0 (first fight or single-fight battle)
     * field[3] = N   → sub-battle N (0-indexed)
     */
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

            // ── ARM-WIN intercept ────────────────────────────────────────────
            // If the intercept is armed and this segment is a complete outbound
            // event_battle_finish_fight frame, swap it for a crafted WIN packet.
            // We send the WIN using the SAME counter the game used, so the server
            // responds on the SAME connection the game's state machine is waiting on.
            // The game client then processes the win response and shows the WIN screen.
            //
            // The finish_fight frame is always a small packet (≤ 57 B in captures)
            // so it always arrives as one complete TCP segment — no partial-frame risk.
            var payloadForServer = packet.payload
            if (interceptArmed.get() && GameProtocolParser.tryExtractFinishFight(packet.payload) != null) {
                interceptArmed.set(false)
                val patched = PacketInjector.patchFinishFightToWin(packet.payload, interceptRounds.get())
                if (patched == null) {
                    onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND,
                        "PATCH FAILED: field[4] not found — hex: ${packet.payload.joinToString(" ") { "%02x".format(it) }}".toByteArray()))
                    return  // do NOT forward the unmodified loss packet
                }
                payloadForServer = patched
            }

            // ── Clan battle auto-intercept ────────────────────────────────
            // Auto-armed when we sniffed the server's clan_start_fight response
            // (see parseSf3Frames inbound path). When the game now sends its own
            // clan_finish_fight, patch field[4]→WIN and set rounds from the value
            // we captured from the server. No user interaction required.
            if (clanInterceptArmed.get() && GameProtocolParser.tryExtractClanFinishFight(packet.payload) != null) {
                clanInterceptArmed.set(false)
                val patched = PacketInjector.patchFinishFightToWin(packet.payload, clanInterceptRounds.get())
                if (patched == null) {
                    onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND,
                        "CLAN PATCH FAILED: field[4] not found — hex: ${packet.payload.joinToString(" ") { "%02x".format(it) }}".toByteArray()))
                    return  // do NOT forward the unmodified loss packet
                }
                payloadForServer = patched
            }

            // ── Raid damage intercept ─────────────────────────────────────
            // When armed, replaces params.field[2] fixed32 with 1.0 so the
            // server sees 100% of boss HP dealt = boss reduced to 0 HP.
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

            // ── Brawler WIN intercept ─────────────────────────────────────
            // When armed, replaces the outbound brawler_finish with a fully
            // rebuilt WIN packet (same counter, match info preserved from the
            // game's own packet so the server knows who was played against).
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

            // Buffer and reassemble SF3 frames — large packets (e.g. get_player at 9 KB
            // compressed) arrive across many TCP segments; passing a partial segment to the
            // parser returns null and we'd lose counter tracking and battle detection.
            conn.outboundSf3Buffer.write(payloadForServer)
            parseSf3Frames(conn.connId, conn.outboundSf3Buffer, LiveMessage.Direction.OUTBOUND)

            conn.outboundQueue.trySend(payloadForServer)
        } else {
            // WS is established — buffer and parse outbound frames (client→server, masked)
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

    /**
     * Drains the outbound queue and writes each chunk to the real server socket.
     * Acquires writeLock around each write so injectDirect can safely interleave
     * without corrupting the byte stream.
     */
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

                // ── Inbound brawler_finish intercept ──────────────────────────────────
                // For real-time brawler duels the client sends binary game-session data
                // (not an SF3 brawler_finish command), so the outbound intercept never
                // fires. Instead, watch for the SERVER's inbound brawler_finish response
                // and replace the result with WIN before the game client sees it.
                // Works for the common case where the full 0x02 frame arrives in one read
                // (10 KB frame < 32 KB buffer). If the frame is split across reads the
                // patch is skipped and the original response reaches the game.
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

                // Push data back to the app through the TUN interface
                sendDataToApp(conn, dataToForward)

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
                    // Use dataToForward so that if the brawler inbound patch fired, the log
                    // also records the patched WIN frame rather than the original LOSS.
                    conn.inboundSf3Buffer.write(dataToForward)
                    parseSf3Frames(conn.connId, conn.inboundSf3Buffer, LiveMessage.Direction.INBOUND, conn)
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
                val cmdName = extractCommandName(payload)
                onMessage(connId, makeMessage(dir, payload, cmdName))
            }

            offset += totalFrame
        }

        // Keep any leftover bytes for the next read
        if (offset < raw.size) buffer.write(raw, offset, raw.size - offset)
    }

    companion object {
        // If parseSf3Frames skips more than this many consecutive unknown-type bytes,
        // the buffer is poisoned (bad data, protocol switch, partial write corruption).
        // Wipe it and start fresh rather than blocking all future inbound events.
        private const val MAX_RESYNC_BYTES = 64
        // Maximum sane compressed-frame size (8 MB). A legitimately larger frame would be
        // pathological; treat it as a sync error and wipe.
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }

    /**
     * Buffers raw bytes from [buffer] and emits each COMPLETE SF3 frame to [onMessage].
     * Leftover incomplete bytes stay in [buffer] for the next call.
     *
     * SF3 framing:
     *   Small  0x01 + 1B-len + payload          total = 2 + len
     *   Large  0x02 + 4B-LE-len + compressed    total = 5 + compLen
     *
     * Sync-loss recovery: if more than MAX_RESYNC_BYTES consecutive bytes have an unknown
     * frame type, the buffer is wiped. This prevents a single bad server chunk (e.g. an
     * unexpected keepalive or protocol error) from permanently blocking all future events.
     */
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
                            // Implausible size — treat as sync error, skip this byte
                            if (conn != null) {
                                conn.inboundResyncBytes++
                                if (conn.inboundResyncBytes > MAX_RESYNC_BYTES) {
                                    conn.inboundResyncBytes = 0
                                    return
                                }
                            }
                            pos++
                        }
                        pos + 5 + compLen > raw.size -> break  // frame not yet complete
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
                    // Unknown frame type byte — try to re-sync one byte at a time.
                    // If we've skipped too many, wipe the buffer and bail out so
                    // future valid frames aren't permanently blocked.
                    if (conn != null && dir == LiveMessage.Direction.INBOUND) {
                        conn.inboundResyncBytes++
                        if (conn.inboundResyncBytes > MAX_RESYNC_BYTES) {
                            conn.inboundResyncBytes = 0
                            return  // buffer already reset above; start fresh next read
                        }
                    }
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
     * Inject raw SF3-framed bytes DIRECTLY into the server socket, bypassing the
     * outbound queue entirely.
     *
     * This is the preferred injection path because:
     *   • It works even if writerLoop has crashed (closed channel is detected immediately
     *     and a clear error is returned rather than a silent no-op).
     *   • It acquires writeLock so bytes never interleave with the writerLoop's writes.
     *   • It returns a concrete result: "SENT Nb" on success, "FAIL: …" on any error.
     *
     * Called from a background coroutine (Dispatchers.IO) in the overlay — never from
     * the main thread — so blocking on writeLock is safe.
     */
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
            // Log the injected packet so it appears in LOGS alongside the server's
            // response — this is the "exited VPN" log entry the user can diff against
            // what the game originally sent (or against what we built in PacketInjector).
            // We log `data` (the unframed SF3 bytes), not `payload` (which may be
            // WS-wrapped), so GameProtocolParser can parse the SF3 envelope correctly.
            onMessage(conn.connId, LiveMessage(LiveMessage.Direction.OUTBOUND, data))
            result
        } catch (e: Exception) {
            "FAIL: write error: ${e.message}"
        }
    }

    /**
     * injectDirect on the first ESTABLISHED connection (last-resort fallback).
     */
    fun injectDirectToAny(data: ByteArray): String {
        val conn = connections.values.firstOrNull { it.status == TcpStatus.ESTABLISHED }
            ?: return "FAIL: no ESTABLISHED conn (${connections.size} tracked)"
        return injectDirect(conn.connId, data)
    }

    /**
     * Queue-based inject — kept for reference but injectDirect is preferred.
     * Returns null if [connId] is not found or not ESTABLISHED.
     */
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

    /**
     * Inject to the first ESTABLISHED connection (last-resort fallback).
     */
    fun injectToAny(data: ByteArray): String? {
        val conn = connections.values.firstOrNull { it.status == TcpStatus.ESTABLISHED }
            ?: return "FAIL: injectToAny found no ESTABLISHED conn (${connections.size} conns)"
        val payload = if (conn.isWebSocket) wrapInWsFrame(data) else data
        val result = conn.outboundQueue.trySend(payload)
        return if (result.isSuccess) "QUEUED via any  id=…${conn.connId.takeLast(16)}  ws=${conn.isWebSocket}"
        else "FAIL: queue closed on conn …${conn.connId.takeLast(16)}"
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
