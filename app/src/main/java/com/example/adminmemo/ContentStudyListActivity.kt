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
    private lateinit var allConcepts: List<Card>
    private lateinit var subject: String
    private var outlineMode = false

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
            subtitleFn = { if (it.mnemonic.isNotBlank()) "두문자: ${it.mnemonic}" else "" },
            onClick = { card -> openDetail(card.id) }
        )
        rv.adapter = adapter

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<android.widget.Button>(R.id.btnGoExam).setOnClickListener {
            val intent = Intent(this, ExamSessionActivity::class.java)
            intent.putExtra(EXTRA_SUBJECT, subject)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        allConcepts = sortConceptCards(
            this,
            CardStore.getAllCards(this).filter { it.type == "concept" && it.subject == subject },
            subject
        )
        applyFilter(currentQuery)
    }

    private var currentQuery: String = ""

    private fun applyFilter(query: String) {
        currentQuery = query
        val q = query.trim()
        val filtered = if (q.isEmpty()) {
            allConcepts
        } else {
            allConcepts.filter { it.topicTitle.contains(q, ignoreCase = true) || it.back.contains(q, ignoreCase = true) }
        }
        adapter.submitList(filtered)
    }

    private fun openDetail(cardId: String) {
        val ids = adapter.currentList().map { it.id }
        val idx = ids.indexOf(cardId)
        if (outlineMode) {
            val intent = Intent(this, OutlineDrillActivity::class.java)
            intent.putExtra(OutlineDrillActivity.EXTRA_CARD_ID, cardId)
            startActivity(intent)
            return
        }
        val intent = Intent(this, ContentDetailActivity::class.java)
        intent.putExtra(ContentDetailActivity.EXTRA_IDS, ArrayList(ids))
        intent.putExtra(ContentDetailActivity.EXTRA_INDEX, idx)
        startActivity(intent)
    }
}
