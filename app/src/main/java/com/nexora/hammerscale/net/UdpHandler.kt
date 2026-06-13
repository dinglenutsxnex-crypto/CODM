package com.nexora.hammerscale.net

import android.net.VpnService
import com.nexora.hammerscale.model.*
import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class UdpHandler(
    private val vpnService: VpnService,
    private val vpnFd: FileDescriptor,
    private val onConnectionEvent: (ConnectionEntry) -> Unit,
    private val onMessage: (String, LiveMessage) -> Unit,
    private val onStatusChange: (String, ConnectionStatus) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outStream = FileOutputStream(vpnFd)
    private val udpSessions = ConcurrentHashMap<String, DatagramSocket>()

    fun handlePacket(packet: ParsedPacket) {
        val udp = packet.udp ?: return
        val isDns = udp.dstPort == 53

        val connKey = "${packet.ip.srcAddr.hostAddress}:${udp.srcPort}->" +
                      "${packet.ip.dstAddr.hostAddress}:${udp.dstPort}"

        if (isDns) {
            handleDns(packet, udp, connKey)
        } else {
            handleGenericUdp(packet, udp, connKey)
        }
    }

    private fun handleDns(packet: ParsedPacket, udp: UDPHeader, connKey: String) {
        val dnsQuery = DnsParser.parse(packet.payload)
        val queryName = dnsQuery?.questions?.firstOrNull() ?: "?"

        val entry = ConnectionEntry(
            id = connKey,
            protocol = Protocol.DNS,
            srcPort = udp.srcPort,
            dstIp = packet.ip.dstAddr.hostAddress ?: "8.8.8.8",
            dstPort = udp.dstPort,
            dstHost = "DNS: $queryName",
            status = ConnectionStatus.CONNECTING,
            dnsQuery = queryName
        )
        onConnectionEvent(entry)

        val srcIp = packet.ip.srcAddr.address
        val dstIp = packet.ip.dstAddr.address
        val payload = packet.payload

        scope.launch {
            try {
                val sock = DatagramSocket()
                vpnService.protect(sock)
                sock.soTimeout = 3000

                val target = InetSocketAddress(packet.ip.dstAddr, udp.dstPort)
                val sendPkt = DatagramPacket(payload, payload.size, target)
                sock.send(sendPkt)

                onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND, payload, commandName = "unknown"))

                val recvBuf = ByteArray(4096)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                sock.receive(recvPkt)
                sock.close()

                val response = recvBuf.copyOf(recvPkt.length)
                onMessage(connKey, LiveMessage(LiveMessage.Direction.INBOUND, response, commandName = "unknown"))

                // Parse DNS response
                val dnsResp = DnsParser.parse(response)
                val answers = dnsResp?.answers?.map { rec ->
                    when (rec.type) {
                        1 -> "A: ${rec.data}"
                        28 -> "AAAA: ${rec.data}"
                        5 -> "CNAME: ${rec.data}"
                        else -> rec.data
                    }
                } ?: emptyList()

                // Update connection entry with answers
                val updatedEntry = entry.copy(
                    dnsAnswers = answers,
                    status = ConnectionStatus.CLOSED,
                    dstHost = "DNS: $queryName → ${answers.firstOrNull() ?: "NXDOMAIN"}"
                )
                onConnectionEvent(updatedEntry)

                // Write DNS response back to VPN fd
                val responsePacket = PacketParser.buildIPv4UDPPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = udp.dstPort,
                    dstPort = udp.srcPort,
                    payload = response
                )
                writeToVpn(responsePacket)
            } catch (e: Exception) {
                onStatusChange(connKey, ConnectionStatus.CLOSED)
            }
        }
    }

    private fun handleGenericUdp(packet: ParsedPacket, udp: UDPHeader, connKey: String) {
        val entry = ConnectionEntry(
            id = connKey,
            protocol = Protocol.UDP,
            srcPort = udp.srcPort,
            dstIp = packet.ip.dstAddr.hostAddress ?: "?",
            dstPort = udp.dstPort,
            status = ConnectionStatus.ACTIVE
        )
        onConnectionEvent(entry)

        val srcIp = packet.ip.srcAddr.address
        val dstIp = packet.ip.dstAddr.address
        val payload = packet.payload

        onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND, payload, commandName = "unknown"))

        scope.launch {
            try {
                val sock = udpSessions.getOrPut(connKey) {
                    DatagramSocket().also { vpnService.protect(it) }
                }
                sock.soTimeout = 5000

                val target = InetSocketAddress(packet.ip.dstAddr, udp.dstPort)
                sock.send(DatagramPacket(payload, payload.size, target))

                val recvBuf = ByteArray(65507)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    sock.receive(recvPkt)
                    val response = recvBuf.copyOf(recvPkt.length)
                    onMessage(connKey, LiveMessage(LiveMessage.Direction.INBOUND, response, commandName = "unknown"))

                    val responsePacket = PacketParser.buildIPv4UDPPacket(
                        srcIp = dstIp,
                        dstIp = srcIp,
                        srcPort = udp.dstPort,
                        dstPort = udp.srcPort,
                        payload = response
                    )
                    writeToVpn(responsePacket)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                udpSessions.remove(connKey)
                onStatusChange(connKey, ConnectionStatus.CLOSED)
            }
        }
    }

    private fun writeToVpn(data: ByteArray) {
        try { outStream.write(data) } catch (_: Exception) {}
    }

    fun shutdown() {
        scope.cancel()
        udpSessions.values.forEach { try { it.close() } catch (_: Exception) {} }
        udpSessions.clear()
    }
}
