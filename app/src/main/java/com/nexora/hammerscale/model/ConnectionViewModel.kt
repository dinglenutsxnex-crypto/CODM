package com.nexora.hammerscale.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexora.hammerscale.net.GameProtocolParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    // May differ from gameSocketId (HANDSHAKE conn) if SF3 uses separate connections.
    private val _battleSocketId = MutableLiveData<String?>(null)
    val battleSocketId: LiveData<String?> = _battleSocketId

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

    // Round count auto-detected from the server's clan_start_fight response.
    private val _clanRounds = MutableLiveData<Int?>(null)
    val clanRounds: LiveData<Int?> = _clanRounds

    // 0 = first fight (field[3] absent), N = Nth fight (0-indexed). Null until first sniff.
    private val _battleSeq = MutableLiveData<Int?>(null)
    val battleSeq: LiveData<Int?> = _battleSeq

    // True between outbound raid_fight_start and inbound raid_fight_finish.
    private val _raidFightActive = MutableLiveData<Boolean>(false)
    val raidFightActive: LiveData<Boolean> = _raidFightActive

    private val _sfaPort = java.util.concurrent.atomic.AtomicInteger(443)
    val sfaPort: Int get() = _sfaPort.get()

    fun setClanRounds(rounds: Int) {
        _clanRounds.postValue(rounds)
    }

    fun setBattleSeq(seq: Int) {
        _battleSeq.postValue(seq)
    }

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

    // Returns every LiveMessage from every connection, sorted by timestamp, so exported
    // zips include both the auth/game socket and battle socket, not just gameSocketId.
    fun getAllMessages(): List<LiveMessage> {
        return connectionMap.values
            .flatMap { conn -> synchronized(conn.messages) { conn.messages.toList() } }
            .sortedBy { it.timestamp }
    }

    fun addMessage(id: String, message: LiveMessage) {
        connectionMap[id]?.let { conn ->
            // Must update synchronously, not inside the coroutine below — otherwise the Win
            // button (main thread) can read a stale counter and inject a duplicate the server
            // silently drops, resulting in no WIN response.
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
                    if (event is GameEvent.Command) {
                        when {
                            event.isOutbound  && event.name == "raid_fight_start"  -> _raidFightActive.postValue(true)
                            !event.isOutbound && event.name == "raid_fight_finish" -> _raidFightActive.postValue(false)
                        }
                    }

                    synchronized(gameEventList) {
                        gameEventList.add(event)
                        if (gameEventList.size > 2000) gameEventList.removeAt(0)
                    }
                    _gameEvents.postValue(gameEventList.toList())

                    when (event) {
                        is GameEvent.BattleStarted -> {
                            if (event.battleId != "?") {
                                // event_battle_start_fight (hero/PVP) always overrides whatever
                                // is tracked, so a clan start_fight firing first doesn't stick.
                                val isHeroFight = event.commandName == "event_battle_start_fight"
                                if (isHeroFight || _currentBattle.value == null) {
                                    _currentBattle.postValue(BattleState(event.battleId))
                                    if (isHeroFight) _battleSeq.postValue(null)
                                    _battleSocketId.postValue(id)
                                }
                            }
                        }
                        is GameEvent.WinConfirmed -> {
                            // "?" means the server ack used its own sequential fight counter
                            // instead of echoing the client's template battle ID, so we clear
                            // unconditionally in that case; otherwise the ID must still match.
                            val tracked = _currentBattle.value?.battleId
                            if (tracked == null || event.battleId == "?" || tracked == event.battleId) {
                                _currentBattle.postValue(null)
                            }
                        }
                        is GameEvent.BrawlerFinished -> {
                            _currentBattle.postValue(null)
                        }
                        is GameEvent.BattleCommand -> {
                            if (event.name in setOf("finish_fight", "event_battle_finish_fight", "clan_finish_fight")) {
                                _currentBattle.postValue(null)
                            }
                            // event_battle_finish_fight opens a new TCP connection distinct
                            // from the (already closed) start_fight one — track it so a WIN
                            // injection targets the right socket.
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
        _clanRounds.postValue(null)
        _battleSeq.postValue(null)
        _raidFightActive.postValue(false)
        publishUpdate()
    }

    fun getConnection(id: String): ConnectionEntry? = connectionMap[id]

    fun getMessages(id: String): List<LiveMessage> {
        val conn = connectionMap[id] ?: return emptyList()
        return synchronized(conn.messages) { conn.messages.toList() }
    }

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
            is GameEvent.BrawlerFinished -> {
                _currentBattle.postValue(null)
            }
            is GameEvent.BattleCommand -> {
                if (event.name in setOf("finish_fight", "event_battle_finish_fight", "clan_finish_fight")) {
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
