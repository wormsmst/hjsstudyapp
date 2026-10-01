package com.example.adminmemo

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

sealed class OutlineUiItem {
    data class Topic(val card: Card, val expanded: Boolean) : OutlineUiItem()
    data class Heading(
        val cardId: String,
        val node: OutlineNode,
        val key: String,
        val expanded: Boolean,
        val titleShown: Boolean,
        val canExpand: Boolean,
        val hasSubtree: Boolean
    ) : OutlineUiItem()
    data class Body(val text: String, val indentLevel: Int) : OutlineUiItem()
}

fun flattenOutlineTree(
    cards: List<Card>,
    rootsByCard: Map<String, List<OutlineNode>>,
    expandedTopics: Set<String>,
    expandedKeys: Set<String>,
    includeTopics: Boolean = true,
    revealedKeys: Set<String> = emptySet()
): List<OutlineUiItem> {
    val out = mutableListOf<OutlineUiItem>()
    for (card in cards) {
        val open = !includeTopics || card.id in expandedTopics
        if (includeTopics) {
            out.add(OutlineUiItem.Topic(card, open))
        }
        if (!open) continue
        appendNodes(card.id, rootsByCard[card.id].orEmpty(), "", expandedKeys, revealedKeys, out)
    }
    return out
}

private fun appendNodes(
    cardId: String,
    nodes: List<OutlineNode>,
    parentPath: String,
    expandedKeys: Set<String>,
    revealedKeys: Set<String>,
    out: MutableList<OutlineUiItem>
) {
    nodes.forEachIndexed { i, node ->
        val path = if (parentPath.isEmpty()) i.toString() else "$parentPath/$i"
        val key = "$cardId/$path"
        val hasSubtree = node.children.isNotEmpty() || node.bodyText.isNotBlank()
        val canExpand = node.level == 0 || hasSubtree
        val expanded = key in expandedKeys
        val titleShown = node.level != 0 || key in revealedKeys || expanded
        out.add(
            OutlineUiItem.Heading(
                cardId = cardId,
                node = node,
                key = key,
                expanded = expanded,
                titleShown = titleShown,
                canExpand = canExpand,
                hasSubtree = hasSubtree
            )
        )
        if (!expanded) return@forEachIndexed
        if (node.bodyText.isNotBlank()) {
            out.add(OutlineUiItem.Body(node.bodyText, node.level.coerceAtLeast(0) + 1))
        }
        appendNodes(cardId, node.children, path, expandedKeys, revealedKeys, out)
    }
}

class OutlineTreeAdapter(
    private val onTopicClick: (OutlineUiItem.Topic) -> Unit,
    private val onHeadingClick: (OutlineUiItem.Heading) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<OutlineUiItem>()

    fun submitList(newItems: List<OutlineUiItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is OutlineUiItem.Topic -> 0
        is OutlineUiItem.Heading -> 1
        is OutlineUiItem.Body -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cv = CardView(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            radius = dp(16).toFloat()
            cardElevation = 2f * d
        }
        return when (viewType) {
            0 -> TopicVH(cv)
            2 -> {
                val tv = TextView(context).apply {
                    setTextColor(ContextCompat.getColor(context, R.color.text_main))
                    textSize = 15f
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
                cv.addView(tv)
                BodyVH(cv, tv)
            }
            else -> HeadingVH(cv)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        when {
            holder is TopicVH && item is OutlineUiItem.Topic -> {
                val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
                lp.marginStart = dp(16)
                lp.marginEnd = dp(16)
                lp.topMargin = dp(8)
                lp.bottomMargin = dp(6)
                holder.itemView.layoutParams = lp
                holder.card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bg_card))
                holder.tvTitle.text = item.card.topicTitle
                holder.tvArrow.text = if (item.expanded) "▼" else "▶"
                holder.itemView.setOnClickListener { onTopicClick(item) }
            }
            holder is HeadingVH && item is OutlineUiItem.Heading -> {
                val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
                val indent = dp(16) + item.node.level.coerceAtLeast(0) * dp(12)
                lp.marginStart = indent
                lp.marginEnd = dp(16)
                lp.topMargin = dp(4)
                lp.bottomMargin = dp(4)
                holder.itemView.layoutParams = lp
                holder.card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bg_card))
                holder.tvLabel.text = if (item.node.level == 0 && !item.titleShown) {
                    outlineMarkerOf(item.node.label)
                } else {
                    item.node.label
                }
                holder.tvLabel.setTextColor(ContextCompat.getColor(context, R.color.text_main))
                holder.tvLabel.textSize = if (item.node.level == 0) 17f else 16f
                holder.tvLabel.setTypeface(null, if (item.node.level == 0) Typeface.BOLD else Typeface.NORMAL)
                holder.tvArrow.text = when {
                    item.node.level == 0 && !item.titleShown -> "▶"
                    item.node.level == 0 && item.titleShown && !item.expanded ->
                        if (item.hasSubtree) "▶" else ""
                    item.expanded -> "▼"
                    !item.canExpand -> ""
                    else -> "▶"
                }
                holder.itemView.setOnClickListener { onHeadingClick(item) }
            }
            holder is BodyVH && item is OutlineUiItem.Body -> {
                val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
                lp.marginStart = dp(16) + item.indentLevel * dp(12)
                lp.marginEnd = dp(16)
                lp.topMargin = dp(2)
                lp.bottomMargin = dp(8)
                holder.itemView.layoutParams = lp
                holder.card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bg_card))
                holder.tvBody.text = item.text
            }
        }
    }

    override fun getItemCount() = items.size

    class TopicVH(val card: CardView) : RecyclerView.ViewHolder(card) {
        val tvTitle: TextView
        val tvArrow: TextView
        init {
            val density = card.context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val row = LinearLayout(card.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(16), dp(16))
            }
            tvTitle = TextView(card.context).apply {
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(card.context, R.color.text_main))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvArrow = TextView(card.context).apply {
                textSize = 16f
                setTextColor(ContextCompat.getColor(card.context, R.color.primary))
            }
            row.addView(tvTitle)
            row.addView(tvArrow)
            card.addView(row)
        }
    }

    class HeadingVH(val card: CardView) : RecyclerView.ViewHolder(card) {
        val tvLabel: TextView
        val tvArrow: TextView
        init {
            val density = card.context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val row = LinearLayout(card.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(14), dp(14))
            }
            tvLabel = TextView(card.context).apply {
                setTextColor(ContextCompat.getColor(card.context, R.color.text_main))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvArrow = TextView(card.context).apply {
                textSize = 14f
                setTextColor(ContextCompat.getColor(card.context, R.color.primary))
            }
            row.addView(tvLabel)
            row.addView(tvArrow)
            card.addView(row)
        }
    }

    class BodyVH(val card: CardView, val tvBody: TextView) : RecyclerView.ViewHolder(card)
}
