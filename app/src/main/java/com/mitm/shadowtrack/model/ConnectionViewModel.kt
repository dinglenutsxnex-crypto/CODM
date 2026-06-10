package com.mitm.shadowtrack.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ConnectionViewModel : ViewModel() {

    private val connectionMap = ConcurrentHashMap<String, ConnectionEntry>()

    private val _connections = MutableLiveData<List<ConnectionEntry>>(emptyList())
    val connections: LiveData<List<ConnectionEntry>> = _connections

    private val _vpnRunning = MutableLiveData(false)
    val vpnRunning: LiveData<Boolean> = _vpnRunning

    private val _stats = MutableLiveData(Stats())
    val stats: LiveData<Stats> = _stats

    // The connection that sent the first HANDSHAKE message — this is the game socket
    private val _gameSocketId = MutableLiveData<String?>(null)
    val gameSocketId: LiveData<String?> = _gameSocketId

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

    fun addMessage(id: String, message: LiveMessage) {
        connectionMap[id]?.let { conn ->
            viewModelScope.launch(Dispatchers.Default) {
                synchronized(conn.messages) {
                    conn.messages.add(message)
                    if (conn.messages.size > 500) {
                        conn.messages.removeAt(0)
                    }
                }
                if (message.direction == LiveMessage.Direction.INBOUND) {
                    conn.bytesIn += message.data.size
                } else {
                    conn.bytesOut += message.data.size
                }
                conn.lastActivityTime = System.currentTimeMillis()

                // Auto-detect the game socket: any message whose payload
                // contains the ASCII string "HANDSHAKE" locks this connection in.
                val text = String(message.data, Charsets.ISO_8859_1)
                if (text.contains("HANDSHAKE")) {
                    _gameSocketId.postValue(id)
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
        publishUpdate()
    }

    fun getConnection(id: String): ConnectionEntry? = connectionMap[id]

    fun getMessages(id: String): List<LiveMessage> {
        val conn = connectionMap[id] ?: return emptyList()
        return synchronized(conn.messages) { conn.messages.toList() }
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
