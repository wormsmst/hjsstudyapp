package com.example.adminmemo

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

sealed class OutlineListItem {
    data class RowItem(
        val node: OutlineNode,
        val path: String,
        val key: String,
        val isExpanded: Boolean,
        val isLocked: Boolean,
        val displayLabel: String,
        val hasExpandable: Boolean
    ) : OutlineListItem()

    data class BodyItem(
        val bodyText: String,
        val level: Int,
        val key: String
    ) : OutlineListItem()
}

class OutlineAdapter(
    private val onItemClick: (OutlineListItem.RowItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<OutlineListItem>()

    fun submitList(newItems: List<OutlineListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is OutlineListItem.RowItem -> 0
            is OutlineListItem.BodyItem -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        return if (viewType == 0) {
            val cv = CardView(context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(8) }
                radius = dp(14).toFloat()
                cardElevation = 1.5f * density
            }
            RowVH(cv)
        } else {
            val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val cv = CardView(context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(10) }
                radius = dp(12).toFloat()
                cardElevation = 0.5f * density
                setCardBackgroundColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#D2E3FC"))
            }
            val tv = TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 13.5f
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            cv.addView(tv)
            BodyVH(cv, tv)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        if (holder is RowVH && item is OutlineListItem.RowItem) {
            val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
            val indent = (item.node.level.coerceAtLeast(0) * dp(16))
            lp.marginStart = indent
            holder.itemView.layoutParams = lp

            val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            // 수준별 색상: 상위목차=조금 더 진한 미색/베이지, 중목차=조금 더 진한 하늘색, 세부=화이트
            val bgColor = when {
                item.isLocked -> if (isDark) Color.parseColor("#1E3A8A") else Color.parseColor("#C7D2FE")
                item.node.level == 0 -> if (isDark) Color.parseColor("#383431") else Color.parseColor("#E6E2D8") // 조금 더 진한 미색
                item.node.level == 1 -> if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#D2E3FC") // 조금 더 진한 하늘색
                else -> if (isDark) Color.parseColor("#1E2126") else Color.parseColor("#FFFFFF")
            }
            holder.cardView.setCardBackgroundColor(bgColor)

            holder.tvLabel.text = item.displayLabel
            val textColor = if (item.isLocked) {
                if (isDark) Color.parseColor("#93C5FD") else Color.parseColor("#1D4ED8")
            } else {
                ContextCompat.getColor(context, R.color.text_main)
            }
            holder.tvLabel.setTextColor(textColor)

            if (item.node.level == 0 || item.node.children.isNotEmpty()) {
                holder.tvLabel.setTypeface(null, Typeface.BOLD)
            } else {
                holder.tvLabel.setTypeface(null, Typeface.NORMAL)
            }

            holder.tvArrow.text = if (item.isLocked) "🔑" else if (!item.hasExpandable) "📄" else if (item.isExpanded) "▼" else "▶"
            holder.itemView.setOnClickListener { onItemClick(item) }
        } else if (holder is BodyVH && item is OutlineListItem.BodyItem) {
            val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
            lp.marginStart = (item.level.coerceAtLeast(0) * dp(16)) + dp(12)
            holder.itemView.layoutParams = lp
            holder.tvBody.text = item.bodyText
        }
    }

    override fun getItemCount() = items.size

    class RowVH(val cardView: CardView) : RecyclerView.ViewHolder(cardView) {
        val tvLabel: TextView
        val tvArrow: TextView
        init {
            val row = LinearLayout(cardView.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val d = cardView.context.resources.displayMetrics.density
                setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            }
            tvLabel = TextView(cardView.context).apply {
                textSize = 14.5f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvArrow = TextView(cardView.context).apply {
                textSize = 13f
                setTextColor(ContextCompat.getColor(cardView.context, R.color.primary))
            }
            row.addView(tvLabel)
            row.addView(tvArrow)
            cardView.addView(row)
        }
    }

    class BodyVH(cardView: CardView, val tvBody: TextView) : RecyclerView.ViewHolder(cardView)
}
