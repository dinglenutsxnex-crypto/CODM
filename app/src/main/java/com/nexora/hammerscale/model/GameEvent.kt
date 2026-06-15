package com.nexora.hammerscale.model

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
    data class Command(val name: String, val isOutbound: Boolean, val extraDetail: String = "") : GameEvent()
    data class BattleCommand(val name: String, val battleId: String?, val isOutbound: Boolean) : GameEvent()
    /**
     * @param commandName The raw SF3 command that triggered this event.
     *   "event_battle_start_fight" = hero/PVP fight (high priority — always overrides current battle)
     *   "start_fight"              = clan/brawler fight (low priority — only set if no battle active)
     */
    data class BattleStarted(val battleId: String, val commandName: String = "start_fight") : GameEvent()
    data class WinConfirmed(val battleId: String) : GameEvent()
    /**
     * Fired for outbound brawler_finish packets.
     * @param result   "WIN" or "LOSS" (inner proto field[2]: 1=WIN, 3=LOSS)
     * @param wonRounds  inner proto field[3] varint
     * @param totalRounds inner proto field[5] varint (0 if absent — e.g. on LOSS)
     */
    data class BrawlerFinished(val result: String, val wonRounds: Int, val totalRounds: Int) : GameEvent()

    val label: String get() = when (this) {
        is HandshakeOut    -> ">> HANDSHAKE  ${serverName}"
        is HandshakeIn     -> ">> TOKEN  ${sessionToken.take(20)}..."
        is LoginOut        -> ">> LOGIN"
        is LoginIn         -> "<< LOGIN OK"
        is Command         -> "${if (isOutbound) ">>" else "<<"} ${name}"
        is BattleCommand   -> "${if (isOutbound) ">>" else "<<"} ${name}${if (battleId != null) "  #$battleId" else ""}"
        is BattleStarted   -> "!! BATTLE STARTED  #${battleId}${if (commandName == "event_battle_start_fight") " [FIGHT]" else " [clan]"}"
        is WinConfirmed    -> "## WIN CONFIRMED  #${battleId}"
        is BrawlerFinished -> ">> BRAWLER FINISH  ${result}  ${wonRounds}/${totalRounds}"
    }

    val detail: String get() = when (this) {
        is HandshakeOut    -> ""
        is HandshakeIn     -> ""
        is LoginOut        -> "guid: ${guid.take(18)}...\npass: ${password}"
        is LoginIn         -> ""
        is Command         -> extraDetail
        is BattleCommand   -> if (battleId != null) "battle: $battleId" else ""
        is BattleStarted   -> "battle_id: ${battleId}"
        is WinConfirmed    -> "battle_id: ${battleId}  /  server confirmed"
        is BrawlerFinished -> "result: ${result}  wonRounds: ${wonRounds}  totalRounds: ${totalRounds}"
    }
}
