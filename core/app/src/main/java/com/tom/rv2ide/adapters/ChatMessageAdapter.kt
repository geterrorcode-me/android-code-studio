/*
 *  This file is part of AndroidCodeStudio.
 *  chat-ux: daftar pesan chat ala messenger (bubble kamu vs agent)
 */
package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.R

class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.VH>() {

    data class Msg(val role: String, val text: String, val ts: Long = System.currentTimeMillis())

    private val items = mutableListOf<Msg>()

    fun add(msg: Msg) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val n = items.size
        items.clear()
        if (n > 0) notifyItemRangeRemoved(0, n)
    }

    fun lastText(): String? = items.lastOrNull()?.text

    // ganti isi bubble terakhir (dipakai untuk streaming status singkat bila perlu)
    fun updateLast(text: String) {
        if (items.isEmpty()) return
        items[items.size - 1] = items.last().copy(text = text)
        notifyItemChanged(items.size - 1)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val bubbleUser: LinearLayout = v.findViewById(R.id.bubbleUser)
        val bubbleAgent: LinearLayout = v.findViewById(R.id.bubbleAgent)
        val textUser: TextView = v.findViewById(R.id.textUser)
        val textAgent: TextView = v.findViewById(R.id.textAgent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        if (m.role == "user") {
            h.bubbleUser.visibility = View.VISIBLE
            h.bubbleAgent.visibility = View.GONE
            h.textUser.text = m.text
        } else {
            h.bubbleAgent.visibility = View.VISIBLE
            h.bubbleUser.visibility = View.GONE
            h.textAgent.text = m.text
        }
    }
}
