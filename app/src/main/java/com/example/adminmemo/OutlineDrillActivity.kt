package com.example.adminmemo

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 목차학습: 최상위 목차 숨김(Active Recall) 및 RecyclerView를 통한 부드럽고 깜빡임 없는 아코디언 학습 화면.
 */
class OutlineDrillActivity : BaseActivity() {

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"
    }

    private lateinit var card: Card
    private lateinit var roots: List<OutlineNode>
    private val expandedKeys = mutableSetOf<String>()
    private val revealedKeys = mutableSetOf<String>()

    private lateinit var tvBreadcrumb: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: OutlineAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_outline_drill)

        tvBreadcrumb = findViewById(R.id.tvOutlineBreadcrumb)
        recyclerView = findViewById(R.id.rvOutlineList)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = OutlineAdapter { item ->
            val key = item.key
            if (item.isLocked) {
                revealedKeys.add(key)
            } else {
                if (item.isExpanded) {
                    expandedKeys.remove(key)
                } else {
                    expandedKeys.add(key)
                }
            }
            render()
        }
        recyclerView.adapter = adapter

        val cardId = intent.getStringExtra(EXTRA_CARD_ID)
        val found = CardStore.getAllCards(this).firstOrNull { it.id == cardId }
        if (found == null) {
            Toast.makeText(this, "카드를 찾을 수 없어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        card = found
        roots = parseOutline(card.back).filter { it.level >= 0 }

        findViewById<ImageButton>(R.id.btnOutlineBack).setOnClickListener { finish() }

        render()
    }

    private fun render() {
        tvBreadcrumb.text = card.topicTitle
        val listItems = mutableListOf<OutlineListItem>()

        if (roots.isEmpty()) {
            adapter.submitList(emptyList())
            return
        }

        fun buildList(node: OutlineNode, path: String) {
            val key = "$path|${node.label}"
            val isRoot = node.level == 0
            val isRevealed = !isRoot || (key in revealedKeys)
            val isExpanded = key in expandedKeys
            val hasChildren = node.children.isNotEmpty()
            val hasBody = node.bodyText.isNotBlank()

            val displayLabel = if (isRoot && !isRevealed) {
                "🔒 [상위목차 숨김 — 탭하여 확인]"
            } else {
                node.label
            }

            listItems.add(
                OutlineListItem.RowItem(
                    node = node,
                    path = path,
                    key = key,
                    isExpanded = isExpanded,
                    isLocked = isRoot && !isRevealed,
                    displayLabel = displayLabel,
                    hasExpandable = hasChildren || hasBody
                )
            )

            if (isExpanded && isRevealed) {
                if (hasBody) {
                    listItems.add(
                        OutlineListItem.BodyItem(
                            bodyText = node.bodyText,
                            level = node.level,
                            key = "$key|body"
                        )
                    )
                }
                if (hasChildren) {
                    node.children.forEach { child ->
                        buildList(child, key)
                    }
                }
            }
        }

        roots.forEach { root ->
            buildList(root, card.topicTitle)
        }

        adapter.submitList(listItems)
    }
}
