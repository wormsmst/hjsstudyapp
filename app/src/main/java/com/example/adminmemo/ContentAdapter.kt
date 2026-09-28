package com.example.adminmemo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 제목/부제 표시 방식을 람다로 주입받는 범용 리스트 어댑터 */
class ContentAdapter(
    private val items: MutableList<Card> = mutableListOf(),
    private val titleFn: (Card) -> String,
    private val subtitleFn: (Card) -> String,
    private val onClick: (Card) -> Unit
) : RecyclerView.Adapter<ContentAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvRowTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvRowSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_content_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = items[position]
        holder.tvTitle.text = titleFn(card)
        val sub = subtitleFn(card)
        holder.tvSubtitle.text = sub
        holder.tvSubtitle.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(card) }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<Card>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** 드래그 정렬: from 위치의 항목을 to 위치로 옮긴다. */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun currentList(): List<Card> = items
}
