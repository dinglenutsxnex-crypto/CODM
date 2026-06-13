package com.nexora.hammerscale.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.nexora.hammerscale.R
import com.nexora.hammerscale.model.ConnectionEntry
import com.nexora.hammerscale.model.ConnectionStatus
import com.nexora.hammerscale.model.Protocol

class ConnectionAdapter(
    private val onClick: (ConnectionEntry) -> Unit
) : ListAdapter<ConnectionEntry, ConnectionAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ConnectionEntry>() {
            override fun areItemsTheSame(a: ConnectionEntry, b: ConnectionEntry) = a.id == b.id
            override fun areContentsTheSame(a: ConnectionEntry, b: ConnectionEntry) =
                a.status == b.status &&
                a.dstHost == b.dstHost &&
                a.bytesIn == b.bytesIn &&
                a.bytesOut == b.bytesOut &&
                a.messages.size == b.messages.size
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAddress: TextView    = view.findViewById(R.id.tv_address)
        val tvPort: TextView       = view.findViewById(R.id.tv_port)
        val tvStatus: TextView     = view.findViewById(R.id.tv_status)
        val tvTraffic: TextView    = view.findViewById(R.id.tv_traffic)
        val tvTime: TextView       = view.findViewById(R.id.tv_time)
        val tvDnsAnswer: TextView  = view.findViewById(R.id.tv_dns_answer)
        val chipProtocol: Chip     = view.findViewById(R.id.chip_protocol)
        val liveIndicator: View    = view.findViewById(R.id.live_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val ctx = holder.itemView.context

        holder.tvAddress.text = entry.displayAddress
        holder.tvPort.text    = "src:${entry.srcPort}"
        holder.tvTime.text    = entry.startTimeStr
        holder.tvTraffic.text = entry.trafficSummary

        // Protocol chip
        holder.chipProtocol.text = when {
            entry.isWebSocket -> "WS"
            entry.protocol == Protocol.DNS -> "DNS"
            entry.protocol == Protocol.UDP -> "UDP"
            else -> "TCP"
        }
        val chipColor = when {
            entry.isWebSocket -> R.color.ws_color
            entry.protocol == Protocol.DNS -> R.color.dns_color
            entry.protocol == Protocol.UDP -> R.color.udp_color
            else -> R.color.tcp_color
        }
        holder.chipProtocol.setChipBackgroundColorResource(chipColor)

        // Status
        holder.tvStatus.text = when (entry.status) {
            ConnectionStatus.CONNECTING -> "⟳ connecting"
            ConnectionStatus.ACTIVE     -> "● active"
            ConnectionStatus.CLOSING    -> "↓ closing"
            ConnectionStatus.CLOSED     -> "○ closed"
        }
        val statusColor = when (entry.status) {
            ConnectionStatus.ACTIVE     -> R.color.green_active
            ConnectionStatus.CONNECTING -> R.color.yellow_connecting
            ConnectionStatus.CLOSING    -> R.color.text_secondary
            ConnectionStatus.CLOSED     -> R.color.text_secondary
        }
        holder.tvStatus.setTextColor(ContextCompat.getColor(ctx, statusColor))

        // Live pulse indicator
        holder.liveIndicator.visibility = if (entry.isLive) View.VISIBLE else View.INVISIBLE
        holder.liveIndicator.setBackgroundColor(
            ContextCompat.getColor(ctx, if (entry.isLive) R.color.green_active else R.color.text_secondary)
        )

        // DNS answer
        if (entry.dnsAnswers.isNotEmpty()) {
            holder.tvDnsAnswer.visibility = View.VISIBLE
            holder.tvDnsAnswer.text = entry.dnsAnswers.take(3).joinToString("\n")
        } else {
            holder.tvDnsAnswer.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(entry) }
    }
}
