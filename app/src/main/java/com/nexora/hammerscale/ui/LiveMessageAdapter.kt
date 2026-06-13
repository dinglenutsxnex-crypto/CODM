package com.nexora.hammerscale.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexora.hammerscale.R
import com.nexora.hammerscale.model.LiveMessage

class LiveMessageAdapter : ListAdapter<LiveMessage, LiveMessageAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LiveMessage>() {
            override fun areItemsTheSame(a: LiveMessage, b: LiveMessage) =
                a.timestamp == b.timestamp && a.direction == b.direction
            override fun areContentsTheSame(a: LiveMessage, b: LiveMessage) =
                a.timestamp == b.timestamp
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDirection: TextView = view.findViewById(R.id.tv_direction)
        val tvTime: TextView      = view.findViewById(R.id.tv_msg_time)
        val tvData: TextView      = view.findViewById(R.id.tv_msg_data)
        val tvHex: TextView       = view.findViewById(R.id.tv_msg_hex)
        val tvSize: TextView      = view.findViewById(R.id.tv_msg_size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = getItem(position)
        val ctx = holder.itemView.context
        val isOut = msg.direction == LiveMessage.Direction.OUTBOUND

        holder.tvDirection.text = if (isOut) "▲ OUT" else "▼ IN"
        holder.tvDirection.setTextColor(
            ContextCompat.getColor(ctx, if (isOut) R.color.outbound_color else R.color.inbound_color)
        )
        holder.tvTime.text   = msg.timeStr
        holder.tvSize.text   = "${msg.data.size} bytes"
        holder.tvData.text   = msg.displayText
        holder.tvHex.text    = msg.hexDump

        holder.itemView.setBackgroundColor(
            ContextCompat.getColor(
                ctx,
                if (isOut) R.color.bg_outbound else R.color.bg_inbound
            )
        )
    }
}
