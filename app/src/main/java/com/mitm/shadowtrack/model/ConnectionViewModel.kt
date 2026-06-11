package com.mitm.shadowtrack.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitm.shadowtrack.net.GameProtocolParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ConnectionViewModel : ViewModel() {

    private val connectionMap = ConcurrentHashMap<String, ConnectionEntry>()

    private val _connections = MutableLiveData<List<ConnectionEntry>>(emptyList())
    val connections: LiveData<List<ConnectionEntry>> = _connections

    private val _vpnRunning = MutableLiveData(false)
    val vpnRunning: LiveData<Boolean> = _vpnRunning

    private val _stats = MutableLiveData(Stats())
    val stats: LiveData<Stats> = _stats

    private val _gameSocketId = MutableLiveData<String?>(null)
    val gameSocketId: LiveData<String?> = _gameSocketId

    // Tracks which connection carried the most recent battle-start packet.
    // May differ from gameSocketId (HANDSHAKE conn) if SF3 uses separate connections.
    private val _battleSocketId = MutableLiveData<String?>(null)
    val battleSocketId: LiveData<String?> = _battleSocketId

    // Tracks the highest outbound packet counter seen so far in the session.
    // Injected packets must use this + 1 so the server doesn't treat them as duplicates.
    private val _outboundCounter = AtomicLong(0L)
    val nextInjectCounter: Long get() {
        val c = _outboundCounter.get()
        return if (c > 0L) c + 1L else 1L
    }

    private val _gameEvents = MutableLiveData<List<GameEvent>>(emptyList())
    val gameEvents: LiveData<List<GameEvent>> = _gameEvents
    private val gameEventList = mutableListOf<GameEvent>()

    data class BattleState(val battleId: String, val startTime: Long = System.currentTimeMillis())

    private val _currentBattle = MutableLiveData<BattleState?>(null)
    val currentBattle: LiveData<BattleState?> = _currentBattle

    data class Stats(
        val totalConnections: Int = 0,
        val activeConnections: Int = 0,
        val dnsQueries: Int = 0,
        val webSockets: Int = 0
    )

    fun setVpnRunning(running: Boolean) {
        _vpnRunning.postValue(running)
        if (!running) {
            connectionMap.values.forEach { conn ->
                if (conn.isLive) conn.status = ConnectionStatus.CLOSED
            }
            publishUpdate()
        }
    }

    fun addOrUpdateConnection(entry: ConnectionEntry) {
        connectionMap[entry.id] = entry
        publishUpdate()
    }

    fun updateConnectionStatus(id: String, status: ConnectionStatus) {
        connectionMap[id]?.let {
            it.status = status
            it.lastActivityTime = System.currentTimeMillis()
            publishUpdate()
        }
    }

    fun markAsWebSocket(id: String) {
        connectionMap[id]?.let {
            it.isWebSocket = true
            it.lastActivityTime = System.currentTimeMillis()
            publishUpdate()
        }
    }

    /**
     * Returns every LiveMessage from every connection, sorted by timestamp (oldest first).
     * Used by LogDownloader so exported zips contain BOTH directions from ALL sockets —
     * auth/game socket AND battle socket — rather than just gameSocketId.
     */
    fun getAllMessages(): List<LiveMessage> {
        return connectionMap.values
            .flatMap { conn -> synchronized(conn.messages) { conn.messages.toList() } }
            .sortedBy { it.timestamp }
    }

    fun addMessage(id: String, message: LiveMessage) {
        connectionMap[id]?.let { conn ->
            // Update outbound counter SYNCHRONOUSLY before launching the coroutine.
            // The Win button reads _outboundCounter on the main thread. If this update
            // were inside the coroutine (Dispatchers.Default) there would be a race:
            // the latest ping counter might not be committed yet when the user taps Win,
            // causing nextInjectCounter to return a counter the server already processed →
            // server silently drops the duplicate → no WIN response.
            if (message.direction == LiveMessage.Direction.OUTBOUND) {
                val counter = GameProtocolParser.extractCounter(message.data)
                if (counter != null) {
                    _outboundCounter.updateAndGet { maxOf(it, counter) }
                }
            }

            viewModelScope.launch(Dispatchers.Default) {
                synchronized(conn.messages) {
                    conn.messages.add(message)
                    if (conn.messages.size > 2000) conn.messages.removeAt(0)
                }
                if (message.direction == LiveMessage.Direction.INBOUND) {
                    conn.bytesIn += message.data.size
                } else {
                    conn.bytesOut += message.data.size
                }
                conn.lastActivityTime = System.currentTimeMillis()

                val text = String(message.data, Charsets.ISO_8859_1)
                if (text.contains("HANDSHAKE")) {
                    _gameSocketId.postValue(id)
                }

                val event = GameProtocolParser.parse(message.data, message.direction)
                if (event != null) {
                    synchronized(gameEventList) {
                        gameEventList.add(event)
                        if (gameEventList.size > 2000) gameEventList.removeAt(0)
                    }
                    _gameEvents.postValue(gameEventList.toList())

                    when (event) {
                        is GameEvent.BattleStarted -> {
                            if (event.battleId != "?") {
                                // event_battle_start_fight = hero/PVP fight — ALWAYS overrides
                                // whatever is in currentBattle. This prevents a background clan
                                // fight (start_fight) that fired first from locking us into the
                                // wrong battle ID when the actual fight starts.
                                // start_fight = clan/brawler — lower priority, only set if idle.
                                val isHeroFight = event.commandName == "event_battle_start_fight"
                                if (isHeroFight || _currentBattle.value == null) {
                                    _currentBattle.postValue(BattleState(event.battleId))
                                    // Record WHICH connection carried this battle packet — may
                                    // differ from gameSocketId if SF3 uses separate conns.
                                    _battleSocketId.postValue(id)
                                }
                            }
                        }
                        is GameEvent.WinConfirmed -> {
                            // "?" = wildcard emitted for event_battle_finish_fight server ACK —
                            // the server sends its own sequential fight counter in field[1] (e.g.
                            // 61028), not the client's template battle ID (e.g. 3001602). The
                            // parser uses "?" to mean "clear unconditionally". For other commands
                            // (finish_fight clan fights) the server echoes the client ID, so the
                            // ID check still guards hero fights from being wiped by clan wins.
                            val tracked = _currentBattle.value?.battleId
                            if (tracked == null || event.battleId == "?" || tracked == event.battleId) {
                                _currentBattle.postValue(null)
                            }
                        }
                        is GameEvent.BattleCommand -> {
                            if (event.name in setOf("finish_fight", "brawler_finish", "event_battle_finish_fight", "clan_finish_fight")) {
                                _currentBattle.postValue(null)
                            }
                            // SF3 opens a NEW TCP connection for event_battle_finish_fight —
                            // different from the start_fight connection (which is already closed).
                            // Capture THAT connection as battleSocketId so any WIN injection
                            // pressed just before (or at the same moment) goes to the right socket.
                            if (event.isOutbound && event.name == "event_battle_finish_fight") {
                                _battleSocketId.postValue(id)
                            }
                        }
                        else -> {}
                    }
                }

                publishUpdate()
            }
        }
    }

    fun resolvedHost(id: String, host: String) {
        connectionMap[id]?.let {
            it.dstHost = host
            publishUpdate()
        }
    }

    fun clearAll() {
        connectionMap.clear()
        _gameSocketId.postValue(null)
        _battleSocketId.postValue(null)
        _outboundCounter.set(0L)
        synchronized(gameEventList) { gameEventList.clear() }
        _gameEvents.postValue(emptyList())
        _currentBattle.postValue(null)
        publishUpdate()
    }

    fun getConnection(id: String): ConnectionEntry? = connectionMap[id]

    fun getMessages(id: String): List<LiveMessage> {
        val conn = connectionMap[id] ?: return emptyList()
        return synchronized(conn.messages) { conn.messages.toList() }
    }

    /** Inject a synthetic event directly (e.g. locally-injected finish_fight). */
    fun emitEvent(event: GameEvent) {
        synchronized(gameEventList) {
            gameEventList.add(event)
            if (gameEventList.size > 2000) gameEventList.removeAt(0)
        }
        _gameEvents.postValue(gameEventList.toList())

        when (event) {
            is GameEvent.BattleStarted -> {
                if (event.battleId != "?") {
                    val isHeroFight = event.commandName == "event_battle_start_fight"
                    if (isHeroFight || _currentBattle.value == null) {
                        _currentBattle.postValue(BattleState(event.battleId))
                    }
                }
            }
            is GameEvent.WinConfirmed -> {
                val tracked = _currentBattle.value?.battleId
                if (tracked == null || event.battleId == "?" || tracked == event.battleId) {
                    _currentBattle.postValue(null)
                }
            }
            is GameEvent.BattleCommand -> {
                if (event.name in setOf("finish_fight", "brawler_finish", "event_battle_finish_fight", "clan_finish_fight")) {
                    _currentBattle.postValue(null)
                }
            }
            else -> {}
        }
    }

    private fun publishUpdate() {
        val list = connectionMap.values.sortedByDescending { it.lastActivityTime }
        _connections.postValue(list)
        val active = list.count { it.isLive }
        val dns = list.count { it.protocol == Protocol.DNS }
        val ws = list.count { it.isWebSocket }
        _stats.postValue(Stats(list.size, active, dns, ws))
    }
}
