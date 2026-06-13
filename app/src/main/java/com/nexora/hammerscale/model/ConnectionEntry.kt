package com.nexora.hammerscale.model

import java.text.SimpleDateFormat
import java.util.*

enum class Protocol { TCP, UDP, DNS, WEBSOCKET, UNKNOWN }
enum class ConnectionStatus { CONNECTING, ACTIVE, CLOSING, CLOSED }

data class LiveMessage(
    val direction: Direction,
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val commandName: String? = null  // e.g. "login", "ping", "brawler_finish", null for unknown
) {
    enum class Direction { OUTBOUND, INBOUND }

    val displayText: String get() {
        val printable = data.map { b ->
            val c = (b.toInt() and 0xFF).toChar()
            if (c.code in 32..126) c else '.'
        }.joinToString("")
        return if (printable.length > 500) printable.substring(0, 500) + "…" else printable
    }

    val hexDump: String get() {
        return data.take(128).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    val timeStr: String get() {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }

    /** Filename-safe command name: lowercase, underscores, or "unknown" */
    val filenameTag: String get() {
        return commandName?.lowercase()?.replace("-", "_") ?: "unknown"
    }
}

data class ConnectionEntry(
    val id: String,
    val protocol: Protocol,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    var dstHost: String = dstIp,
    var status: ConnectionStatus = ConnectionStatus.CONNECTING,
    val startTime: Long = System.currentTimeMillis(),
    var lastActivityTime: Long = System.currentTimeMillis(),
    val messages: MutableList<LiveMessage> = mutableListOf(),
    var bytesIn: Long = 0L,
    var bytesOut: Long = 0L,
    var dnsQuery: String? = null,
    var dnsAnswers: List<String> = emptyList(),
    var httpMethod: String? = null,
    var httpHost: String? = null,
    var httpPath: String? = null,
    var isWebSocket: Boolean = false
) {
    val connectionLabel: String get() = when {
        isWebSocket -> "WS"
        protocol == Protocol.DNS -> "DNS"
        protocol == Protocol.UDP -> "UDP"
        else -> "TCP"
    }

    val displayAddress: String get() = if (dstHost != dstIp) "$dstHost:$dstPort" else "$dstIp:$dstPort"

    val startTimeStr: String get() =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(startTime))

    val trafficSummary: String get() =
        "↑${formatBytes(bytesOut)} ↓${formatBytes(bytesIn)}"

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        else -> "%.1fMB".format(b / (1024.0 * 1024.0))
    }

    val isLive: Boolean get() = status == ConnectionStatus.ACTIVE || status == ConnectionStatus.CONNECTING
}
