package com.mitm.shadowtrack.model

import java.text.SimpleDateFormat
import java.util.*

sealed class GameEvent {
    val timestamp: Long = System.currentTimeMillis()

    val timeStr: String get() =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    data class HandshakeOut(val serverName: String) : GameEvent()
    data class HandshakeIn(val sessionToken: String) : GameEvent()
    data class LoginOut(val guid: String, val password: String) : GameEvent()
    class LoginIn : GameEvent()
    data class Command(val name: String, val isOutbound: Boolean) : GameEvent()
    data class BattleCommand(val name: String, val battleId: String?, val isOutbound: Boolean) : GameEvent()

    val label: String get() = when (this) {
        is HandshakeOut  -> "🤝 HANDSHAKE → ${serverName}"
        is HandshakeIn   -> "🔑 TOKEN: ${sessionToken.take(20)}…"
        is LoginOut      -> "🔐 LOGIN"
        is LoginIn       -> "✅ LOGIN OK"
        is Command       -> "${if (isOutbound) "▲" else "▼"} ${name}"
        is BattleCommand -> "${if (isOutbound) "▲" else "▼"} ${name}${if (battleId != null) " #$battleId" else ""}"
    }

    val detail: String get() = when (this) {
        is HandshakeOut  -> ""
        is HandshakeIn   -> ""
        is LoginOut      -> "guid: ${guid.take(18)}…\npass: ${password}"
        is LoginIn       -> ""
        is Command       -> ""
        is BattleCommand -> if (battleId != null) "battle: $battleId" else ""
    }
}
