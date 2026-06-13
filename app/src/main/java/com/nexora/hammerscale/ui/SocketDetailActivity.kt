package com.nexora.hammerscale.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.nexora.hammerscale.databinding.ActivitySocketDetailBinding
import com.nexora.hammerscale.model.ConnectionEntry
import com.nexora.hammerscale.model.ConnectionStatus
import com.nexora.hammerscale.model.ConnectionViewModel
import com.nexora.hammerscale.model.ConnectionViewModelFactory
import com.nexora.hammerscale.model.Protocol

class SocketDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONN_ID = "connection_id"
    }

    private lateinit var binding: ActivitySocketDetailBinding
    private lateinit var viewModel: ConnectionViewModel
    private lateinit var adapter: LiveMessageAdapter
    private var connId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySocketDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        connId = intent.getStringExtra(EXTRA_CONN_ID) ?: run { finish(); return }
        viewModel = ViewModelProvider(this, ConnectionViewModelFactory())[ConnectionViewModel::class.java]

        adapter = LiveMessageAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@SocketDetailActivity).also {
                it.stackFromEnd = true
            }
            adapter = this@SocketDetailActivity.adapter
        }

        observeConnection()
    }

    private fun observeConnection() {
        viewModel.connections.observe(this) { list ->
            val entry = list.find { it.id == connId } ?: return@observe
            updateHeader(entry)
            val messages = synchronized(entry.messages) { entry.messages.toList() }
            adapter.submitList(messages)
            if (messages.isNotEmpty()) {
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
            binding.tvEmptyMessages.visibility =
                if (messages.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateHeader(entry: ConnectionEntry) {
        supportActionBar?.title = entry.displayAddress
        binding.tvConnId.text = "ID: ${entry.id.takeLast(40)}"
        binding.tvProtocol.text = buildString {
            append("Protocol: ")
            append(when {
                entry.isWebSocket -> "WebSocket"
                entry.protocol == Protocol.DNS -> "DNS"
                entry.protocol == Protocol.UDP -> "UDP"
                else -> "TCP"
            })
        }
        binding.tvConnStatus.text = "Status: ${entry.status.name}"
        binding.tvTraffic.text = "Traffic: ${entry.trafficSummary}"

        if (entry.dnsQuery != null) {
            binding.cardDns.visibility = View.VISIBLE
            binding.tvDnsQuery.text = "Query: ${entry.dnsQuery}"
            binding.tvDnsAnswers.text = "Answers:\n${entry.dnsAnswers.joinToString("\n").ifEmpty { "  (none)" }}"
        } else {
            binding.cardDns.visibility = View.GONE
        }

        val isLive = entry.status == ConnectionStatus.ACTIVE || entry.status == ConnectionStatus.CONNECTING
        binding.tvLiveIndicator.visibility = if (isLive) View.VISIBLE else View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}
