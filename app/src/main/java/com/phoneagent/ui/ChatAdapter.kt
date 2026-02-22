package com.phoneagent.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.phoneagent.R

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: String, // "user", "agent", "system", "step"
    val content: String,
    val thinkingContent: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AGENT = 1
        private const val TYPE_SYSTEM = 2
    }

    private val messages = mutableListOf<ChatMessage>()
    private val expandedThinking = mutableSetOf<Long>()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(content: String) {
        if (messages.isNotEmpty()) {
            val last = messages.last()
            messages[messages.size - 1] = last.copy(content = content)
            notifyItemChanged(messages.size - 1)
        }
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            "user" -> TYPE_USER
            "system" -> TYPE_SYSTEM
            else -> TYPE_AGENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_message_user, parent, false)
                UserMessageViewHolder(view)
            }
            TYPE_SYSTEM -> {
                val view = inflater.inflate(R.layout.item_message_system, parent, false)
                SystemMessageViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_message_agent, parent, false)
                AgentMessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserMessageViewHolder -> holder.bind(message)
            is AgentMessageViewHolder -> holder.bind(message, message.id in expandedThinking) {
                if (message.id in expandedThinking) {
                    expandedThinking.remove(message.id)
                } else {
                    expandedThinking.add(message.id)
                }
                notifyItemChanged(position)
            }
            is SystemMessageViewHolder -> holder.bind(message)
        }
    }

    override fun getItemCount(): Int = messages.size
}

class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val textView: TextView = view.findViewById(R.id.tvMessage)

    fun bind(message: ChatMessage) {
        textView.text = message.content
    }
}

class AgentMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val textView: TextView = view.findViewById(R.id.tvMessage)
    private val thinkingCard: CardView = view.findViewById(R.id.cardThinking)
    private val thinkingText: TextView = view.findViewById(R.id.tvThinking)
    private val expandBtn: TextView = view.findViewById(R.id.btnExpandThinking)

    fun bind(message: ChatMessage, isExpanded: Boolean, onToggle: () -> Unit) {
        textView.text = message.content

        if (message.thinkingContent.isNotBlank()) {
            expandBtn.visibility = View.VISIBLE
            expandBtn.text = if (isExpanded) "▲ Hide thinking" else "▼ Show thinking"
            expandBtn.setOnClickListener { onToggle() }

            thinkingCard.visibility = if (isExpanded) View.VISIBLE else View.GONE
            thinkingText.text = message.thinkingContent
        } else {
            expandBtn.visibility = View.GONE
            thinkingCard.visibility = View.GONE
        }
    }
}

class SystemMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val textView: TextView = view.findViewById(R.id.tvMessage)

    fun bind(message: ChatMessage) {
        textView.text = message.content
    }
}
