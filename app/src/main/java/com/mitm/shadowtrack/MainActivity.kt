package com.mitm.shadowtrack

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.mitm.shadowtrack.databinding.ActivityMainBinding
import com.mitm.shadowtrack.model.ConnectionStatus
import com.mitm.shadowtrack.model.ConnectionViewModel
import com.mitm.shadowtrack.model.ConnectionViewModelFactory
import com.mitm.shadowtrack.ui.LiveMessageAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ConnectionViewModel
    private lateinit var messageAdapter: LiveMessageAdapter

    private val VPN_REQUEST_CODE     = 100
    private val OVERLAY_REQUEST_CODE = 101

    private var overlayRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        viewModel = ViewModelProvider(this, ConnectionViewModelFactory())[ConnectionViewModel::class.java]

        messageAdapter = LiveMessageAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            adapter = messageAdapter
        }

        binding.fabToggleVpn.setOnClickListener {
            if (viewModel.vpnRunning.value == true) stopVpn() else requestVpnPermission()
        }

        observeViewModel()

        // Request overlay permission on first launch so it's ready when needed
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        }
    }

    private fun observeViewModel() {
        viewModel.vpnRunning.observe(this) { running ->
            binding.fabToggleVpn.setImageResource(
                if (running) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            if (!running) {
                binding.tvStatus.text = "○ IDLE — Tap ▶ to start"
                binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
                if (viewModel.gameSocketId.value == null) {
                    binding.tvWaitingTitle.text = "Waiting for game socket"
                    binding.tvWaitingSub.text = "Start VPN and open Shadow Fight 3"
                }
                // Stop overlay when VPN stops
                if (overlayRunning) stopOverlay()
            } else {
                binding.tvStatus.text = "● LIVE — scanning…"
                binding.tvStatus.setTextColor(getColor(R.color.green_active))
                if (viewModel.gameSocketId.value == null) {
                    binding.tvWaitingTitle.text = "VPN active — scanning"
                    binding.tvWaitingSub.text = "Open Shadow Fight 3 to detect game socket"
                }
                // Start overlay automatically when VPN starts (if permission granted)
                if (!overlayRunning && Settings.canDrawOverlays(this)) startOverlay()
            }
        }

        viewModel.gameSocketId.observe(this) { id ->
            if (id == null) { showWaiting(); return@observe }
            showSocket()
        }

        viewModel.connections.observe(this) { _ ->
            val id = viewModel.gameSocketId.value ?: return@observe
            val entry = viewModel.getConnection(id) ?: return@observe

            binding.tvConnAddress.text = entry.displayAddress
            binding.tvConnId.text = "ID: ${entry.id}"
            binding.tvConnStatus.text = "Status: ${entry.status.name}"
            binding.tvConnTraffic.text = entry.trafficSummary
            binding.tvTraffic.text = entry.trafficSummary

            val isLive = entry.status == ConnectionStatus.ACTIVE ||
                         entry.status == ConnectionStatus.CONNECTING
            binding.tvLiveIndicator.visibility = if (isLive) View.VISIBLE else View.GONE

            val messages = viewModel.getMessages(id)
            messageAdapter.submitList(messages)
            binding.tvEmptyMessages.visibility =
                if (messages.isEmpty()) View.VISIBLE else View.GONE
            if (messages.isNotEmpty()) binding.rvMessages.scrollToPosition(messages.size - 1)
        }
    }

    private fun showWaiting() {
        binding.layoutWaiting.visibility = View.VISIBLE
        binding.layoutSocket.visibility  = View.GONE
    }

    private fun showSocket() {
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutSocket.visibility  = View.VISIBLE
    }

    // ── Overlay ───────────────────────────────────────────────────────────

    private fun startOverlay() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        })
        overlayRunning = true
    }

    private fun stopOverlay() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        })
        overlayRunning = false
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_REQUEST_CODE)
    }

    // ── VPN ───────────────────────────────────────────────────────────────

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
        else startVpn()
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VPN_REQUEST_CODE -> if (resultCode == Activity.RESULT_OK) startVpn()
            OVERLAY_REQUEST_CODE -> {
                // Permission result — overlay will start automatically when VPN starts
            }
        }
    }

    private fun startVpn() {
        startService(Intent(this, TrafficVpnService::class.java).apply {
            action = TrafficVpnService.ACTION_START
        })
    }

    private fun stopVpn() {
        startService(Intent(this, TrafficVpnService::class.java).apply {
            action = TrafficVpnService.ACTION_STOP
        })
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear -> { viewModel.clearAll(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
