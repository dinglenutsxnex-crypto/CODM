package com.mitm.shadowtrack

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.mitm.shadowtrack.databinding.ActivityMainBinding
import com.mitm.shadowtrack.model.ConnectionEntry
import com.mitm.shadowtrack.model.ConnectionViewModel
import com.mitm.shadowtrack.model.ConnectionViewModelFactory
import com.mitm.shadowtrack.model.Protocol
import com.mitm.shadowtrack.ui.ConnectionAdapter
import com.mitm.shadowtrack.ui.SocketDetailActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ConnectionViewModel
    private lateinit var adapter: ConnectionAdapter

    private val VPN_REQUEST_CODE = 100
    private var filterProtocol: Protocol? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        viewModel = ViewModelProvider(this, ConnectionViewModelFactory())[ConnectionViewModel::class.java]

        setupRecyclerView()
        setupChipFilters()
        observeViewModel()

        binding.fabToggleVpn.setOnClickListener {
            if (viewModel.vpnRunning.value == true) {
                stopVpn()
            } else {
                requestVpnPermission()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ConnectionAdapter { entry -> openDetail(entry) }
        binding.rvConnections.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(false)
        }
    }

    private fun setupChipFilters() {
        binding.chipAll.setOnClickListener { filterProtocol = null; applyFilter() }
        binding.chipTcp.setOnClickListener { filterProtocol = Protocol.TCP; applyFilter() }
        binding.chipDns.setOnClickListener { filterProtocol = Protocol.DNS; applyFilter() }
        binding.chipUdp.setOnClickListener { filterProtocol = Protocol.UDP; applyFilter() }
        binding.chipWs.setOnClickListener  { filterProtocol = Protocol.WEBSOCKET; applyFilter() }
    }

    private fun applyFilter() {
        val all = viewModel.connections.value ?: return
        val filtered = when (filterProtocol) {
            null               -> all
            Protocol.WEBSOCKET -> all.filter { it.isWebSocket }
            else               -> all.filter { it.protocol == filterProtocol }
        }
        adapter.submitList(filtered.toList())
        updateEmptyState(filtered)
    }

    private fun observeViewModel() {
        viewModel.connections.observe(this) { connections ->
            applyFilter()
        }

        viewModel.vpnRunning.observe(this) { running ->
            binding.fabToggleVpn.apply {
                setImageResource(if (running) android.R.drawable.ic_media_pause
                                 else android.R.drawable.ic_media_play)
                contentDescription = if (running) "Stop VPN" else "Start VPN"
            }
            binding.tvStatus.text = if (running) {
                "● LIVE — Monitoring ${TrafficVpnService.TARGET_PACKAGE}"
            } else {
                "○ IDLE — Tap ▶ to start monitoring"
            }
            binding.tvStatus.setTextColor(
                getColor(if (running) R.color.green_active else R.color.text_secondary)
            )
        }

        viewModel.stats.observe(this) { stats ->
            binding.tvStats.text =
                "Total: ${stats.totalConnections} | Active: ${stats.activeConnections} | " +
                "DNS: ${stats.dnsQueries} | WS: ${stats.webSockets}"
        }
    }

    private fun updateEmptyState(list: List<ConnectionEntry>) {
        val isEmpty = list.isEmpty()
        binding.rvConnections.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.layoutEmpty.visibility   = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun openDetail(entry: ConnectionEntry) {
        val intent = Intent(this, SocketDetailActivity::class.java).apply {
            putExtra(SocketDetailActivity.EXTRA_CONN_ID, entry.id)
        }
        startActivity(intent)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpn()
        }
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, TrafficVpnService::class.java).apply {
            action = TrafficVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, TrafficVpnService::class.java).apply {
            action = TrafficVpnService.ACTION_STOP
        }
        startService(intent)
    }

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
