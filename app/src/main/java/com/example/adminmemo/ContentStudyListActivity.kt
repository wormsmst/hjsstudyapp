package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ContentStudyListActivity : BaseActivity() {

    companion object {
        const val EXTRA_OUTLINE_MODE = "extra_outline_mode"
    }

    private lateinit var adapter: ContentAdapter
    private var allConcepts: List<Card> = emptyList()
    private lateinit var subject: String
    private var outlineMode = false
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_study_list)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""
        outlineMode = intent.getBooleanExtra(EXTRA_OUTLINE_MODE, false)
        findViewById<android.widget.TextView>(R.id.tvStudyListHeader)?.text =
            if (outlineMode) "🗺️ 목차학습" else "📖 본문학습"

        val rv = findViewById<RecyclerView>(R.id.rvContentList)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ContentAdapter(
            titleFn = { it.topicTitle },
            subtitleFn = {
                if (outlineMode) ""
                else if (it.mnemonic.isNotBlank()) "두문자: ${it.mnemonic}"
                else ""
            },
            memoryFn = if (outlineMode) null else { card ->
                CardStore.memoryStarsLabel(CardStore.getMemoryLevel(this, card.subject, card.topicTitle))
            },
            onClick = { card -> openCard(card.id) }
        )
        rv.adapter = adapter

        loadConcepts()

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        loadConcepts()
        applyFilter(currentQuery)
    }

    private fun loadConcepts() {
        allConcepts = sortConceptCards(
            this,
            CardStore.getAllCards(this).filter { it.type == "concept" && it.subject == subject },
            subject
        )
    }

    private fun applyFilter(query: String) {
        currentQuery = query
        val q = query.trim()
        val filtered = if (q.isEmpty()) {
            allConcepts
        } else {
            allConcepts.filter {
                it.topicTitle.contains(q, ignoreCase = true) || it.back.contains(q, ignoreCase = true)
            }
        }
        adapter.submitList(filtered)
    }

    private fun openCard(cardId: String) {
        if (outlineMode) {
            startActivity(
                Intent(this, OutlineDrillActivity::class.java)
                    .putExtra(OutlineDrillActivity.EXTRA_CARD_ID, cardId)
            )
            return
        }
        val ids = adapter.currentList().map { it.id }
        val idx = ids.indexOf(cardId)
        val intent = Intent(this, ContentDetailActivity::class.java)
        intent.putExtra(ContentDetailActivity.EXTRA_IDS, ArrayList(ids))
        intent.putExtra(ContentDetailActivity.EXTRA_INDEX, idx)
        startActivity(intent)
    }
}
