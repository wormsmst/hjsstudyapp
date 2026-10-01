package com.example.adminmemo

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 한 주제의 목차를 같은 화면에서 아코디언으로 펼친다. */
class OutlineDrillActivity : BaseActivity() {

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"
    }

    private val expandedTopics = mutableSetOf<String>()
    private val expandedKeys = mutableSetOf<String>()
    private val revealedKeys = mutableSetOf<String>()
    private lateinit var adapter: OutlineTreeAdapter
    private lateinit var card: Card
    private lateinit var rootsByCard: Map<String, List<OutlineNode>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_outline_drill)

        val cardId = intent.getStringExtra(EXTRA_CARD_ID)
        val found = CardStore.getAllCards(this).firstOrNull { it.id == cardId }
        if (found == null) {
            Toast.makeText(this, "카드를 찾을 수 없어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        card = found
        rootsByCard = mapOf(
            card.id to try {
                parseOutline(card.back).filter { it.level >= 0 }
            } catch (_: Exception) {
                emptyList()
            }
        )

        findViewById<TextView>(R.id.tvOutlineBreadcrumb).text = card.topicTitle
        StudyProgressStore.markTopic(this, card.subject, card.topicTitle)
        findViewById<ImageButton>(R.id.btnOutlineBack).setOnClickListener { finish() }

        adapter = OutlineTreeAdapter(
            onTopicClick = { item ->
                if (item.card.id in expandedTopics) {
                    expandedTopics.remove(item.card.id)
                    expandedKeys.removeAll { it.startsWith(item.card.id + "/") }
                } else {
                    expandedTopics.add(item.card.id)
                }
                refresh()
            },
            onHeadingClick = { item ->
                if (item.node.level == 0) {
                    when {
                        !item.titleShown -> revealedKeys.add(item.key)
                        !item.expanded -> {
                            if (item.hasSubtree) expandedKeys.add(item.key)
                            else revealedKeys.remove(item.key)
                        }
                        else -> {
                            expandedKeys.remove(item.key)
                            expandedKeys.removeAll { it.startsWith(item.key + "/") }
                        }
                    }
                    refresh()
                    return@OutlineTreeAdapter
                }
                if (!item.canExpand) return@OutlineTreeAdapter
                if (item.key in expandedKeys) {
                    expandedKeys.remove(item.key)
                    expandedKeys.removeAll { it.startsWith(item.key + "/") }
                } else {
                    expandedKeys.add(item.key)
                }
                refresh()
            }
        )
        val rv = findViewById<RecyclerView>(R.id.rvOutlineTree)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        refresh()
    }

    private fun refresh() {
        adapter.submitList(
            flattenOutlineTree(
                listOf(card),
                rootsByCard,
                expandedTopics,
                expandedKeys,
                includeTopics = false,
                revealedKeys = revealedKeys
            )
        )
    }
}
