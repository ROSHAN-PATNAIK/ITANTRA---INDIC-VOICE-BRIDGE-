package com.example.indicvoicebridge.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.indicvoicebridge.R
import com.example.indicvoicebridge.model.BridgeMessage
import com.example.indicvoicebridge.model.MessageType

class ChatAdapter(
    private val messages: MutableList<BridgeMessage> = mutableListOf(),
    private val onReplay: (BridgeMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SENT = 0
        private const val TYPE_RECEIVED = 1
        private const val TYPE_ALERT = 2
    }

    fun addMessage(msg: BridgeMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        return when {
            msg.type == MessageType.ALERT -> TYPE_ALERT
            msg.isOutgoing -> TYPE_SENT
            else -> TYPE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SENT -> SentViewHolder(inflater.inflate(R.layout.item_msg_sent, parent, false))
            TYPE_ALERT -> AlertViewHolder(inflater.inflate(R.layout.item_msg_alert, parent, false))
            else -> ReceivedViewHolder(inflater.inflate(R.layout.item_msg_received, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is SentViewHolder -> holder.bind(msg)
            is ReceivedViewHolder -> holder.bind(msg, onReplay)
            is AlertViewHolder -> holder.bind(msg)
        }
    }

    override fun getItemCount() = messages.size

    class SentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTranslated: TextView = view.findViewById(R.id.tvTranslated)
        private val tvOriginal: TextView = view.findViewById(R.id.tvOriginal)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        fun bind(msg: BridgeMessage) {
            tvTranslated.text = msg.translatedText
            tvOriginal.text = "Original: ${msg.originalText}"
            tvTime.text = formatTime(msg.timestamp)
        }
    }

    class ReceivedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSender: TextView = view.findViewById(R.id.tvSender)
        private val tvTranslated: TextView = view.findViewById(R.id.tvTranslated)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val btnReplay: ImageButton = view.findViewById(R.id.btnReplay)
        fun bind(msg: BridgeMessage, onReplay: (BridgeMessage) -> Unit) {
            tvSender.text = "📱 ${msg.senderIp}"
            tvTranslated.text = msg.translatedText
            tvTime.text = formatTime(msg.timestamp)
            btnReplay.setOnClickListener { onReplay(msg) }
        }
    }

    class AlertViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvAlert: TextView = view.findViewById(R.id.tvAlertText)
        private val tvSender: TextView = view.findViewById(R.id.tvAlertSender)
        fun bind(msg: BridgeMessage) {
            tvAlert.text = msg.translatedText
            tvSender.text = "⚠️ ALERT from ${msg.senderIp}"
        }
    }

    private companion object {
        fun formatTime(ts: Long): String {
            val d = java.util.Date(ts)
            return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(d)
        }
    }
}

