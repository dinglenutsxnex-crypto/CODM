package com.mitm.shadowtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mitm.shadowtrack.model.*
import com.mitm.shadowtrack.net.*
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.nio.ByteBuffer

class TrafficVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.mitm.shadowtrack.START_VPN"
        const val ACTION_STOP  = "com.mitm.shadowtrack.STOP_VPN"
        const val TARGET_PACKAGE = "com.nekki.shadowfight3"
        const val CHANNEL_ID = "hammerscale_vpn"
        const val NOTIF_ID = 1001
        const val VPN_ADDRESS = "10.0.0.1"
        const val VPN_ROUTE   = "0.0.0.0"

        @Volatile var instance: TrafficVpnService? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var tcpHandler: TcpHandler? = null
    private var udpHandler: UdpHandler? = null

    val viewModel: ConnectionViewModel by lazy { AppState.viewModel }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY }
        }
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("HAMMERSCALE")
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)

            // Only capture traffic from the target app
            try {
                builder.addAllowedApplication(TARGET_PACKAGE)
            } catch (e: Exception) {
                // Target app not installed - still monitor all traffic
            }

            vpnInterface = builder.establish()
            val fd = vpnInterface?.fileDescriptor ?: return

            tcpHandler = TcpHandler(
                vpnService = this,
                vpnFd = fd,
                onConnectionEvent = { entry -> viewModel.addOrUpdateConnection(entry) },
                onMessage = { id, msg -> viewModel.addMessage(id, msg) },
                onStatusChange = { id, status ->
                    viewModel.updateConnectionStatus(id, status)
                },
                onWebSocket = { id -> viewModel.markAsWebSocket(id) }
            )

            udpHandler = UdpHandler(
                vpnService = this,
                vpnFd = fd,
                onConnectionEvent = { entry -> viewModel.addOrUpdateConnection(entry) },
                onMessage = { id, msg -> viewModel.addMessage(id, msg) },
                onStatusChange = { id, status ->
                    viewModel.updateConnectionStatus(id, status)
                }
            )

            captureJob = scope.launch { captureLoop(fd) }
            viewModel.setVpnRunning(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e("TrafficVpnService", "Failed to start VPN", e)
            stopVpn()
        }
    }

    private suspend fun captureLoop(fd: java.io.FileDescriptor) {
        val input = FileInputStream(fd)
        val buf = ByteBuffer.allocate(32767)

        while (currentCoroutineContext().isActive) {
            try {
                buf.clear()
                val len = withContext(Dispatchers.IO) {
                    input.read(buf.array())
                }
                if (len <= 0) { delay(1); continue }

                buf.limit(len)
                val packet = PacketParser.parse(buf) ?: continue

                when (packet.ip.protocol) {
                    PacketParser.PROTO_TCP -> tcpHandler?.handlePacket(packet)
                    PacketParser.PROTO_UDP -> udpHandler?.handlePacket(packet)
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                delay(10)
            }
        }
    }

    /**
     * Inject a crafted packet into the battle connection's outbound stream.
     *
     * Priority:
     *   1. battleSocketId — the connection that carried the BattleStarted packet
     *      (may differ from gameSocketId if SF3 uses separate auth/battle connections)
     *   2. gameSocketId — the HANDSHAKE connection
     *   3. Any ESTABLISHED TCP connection (last resort)
     *
     * If the connection is WebSocket the handler automatically wraps the payload
     * in a masked WS binary frame before writing to the server.
     */
    fun injectToGameSocket(data: ByteArray) {
        val vm = AppState.viewModel
        val battleId = vm.battleSocketId.value
        val handshakeId = vm.gameSocketId.value

        when {
            battleId != null    -> tcpHandler?.injectToServer(battleId, data)
            handshakeId != null -> tcpHandler?.injectToServer(handshakeId, data)
            else                -> tcpHandler?.injectToAny(data)
        }
    }

    fun stopVpn() {
        captureJob?.cancel()
        tcpHandler?.shutdown()
        udpHandler?.shutdown()
        vpnInterface?.close()
        vpnInterface = null
        viewModel.setVpnRunning(false)
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAMMERSCALE VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Traffic monitoring VPN"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TrafficVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAMMERSCALE Active")
            .setContentText("Monitoring: $TARGET_PACKAGE")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }
}
