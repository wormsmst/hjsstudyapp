package com.example.adminmemo

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class CaseContentManageActivity : BaseActivity() {

    companion object {
        const val EXTRA_OPEN_CASE_ID = "extra_open_case_id"
    }

    private lateinit var subject: String
    private lateinit var adapter: CaseRowAdapter
    private var allItems: List<MockExamItem> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_case_manage)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""
        findViewById<TextView>(R.id.tvCaseManageTitle).text = "⚖️ 사례문제 관리 · $subject"

        val rv = findViewById<RecyclerView>(R.id.rvCaseList)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = CaseRowAdapter { showEditDialog(it) }
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabCaseAdd).setOnClickListener {
            showEditDialog(null)
        }

        findViewById<EditText>(R.id.etCaseSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString() ?: ""
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
        refreshList()
        val openId = intent.getStringExtra(EXTRA_OPEN_CASE_ID)
        if (!openId.isNullOrBlank()) {
            allItems.firstOrNull { it.id == openId }?.let { showEditDialog(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        loadData()
        refreshList()
    }

    private fun loadData() {
        allItems = MockExamStore.getAll(this)
            .filter { canonicalizeSubject(it.subject) == canonicalizeSubject(subject) }
            .sortedWith(
                compareBy<MockExamItem> { naturalSortKey(it.title) }
                    .thenBy { it.id }
            )
    }

    private fun refreshList() {
        val q = query.trim()
        val filtered = if (q.isEmpty()) allItems else allItems.filter { item ->
            item.title.contains(q, true) ||
                item.question.contains(q, true) ||
                item.topic.contains(q, true) ||
                item.keywordList().any { it.contains(q, true) }
        }
        adapter.submit(filtered)
    }

    private fun showEditDialog(existing: MockExamItem?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_case, null)
        val etTitle = view.findViewById<EditText>(R.id.etCaseTitle)
        val etTopic = view.findViewById<EditText>(R.id.etCaseTopic)
        val etBody = view.findViewById<EditText>(R.id.etCaseBody)
        val etExpl = view.findViewById<EditText>(R.id.etCaseExplanation)
        val etKw = view.findViewById<EditText>(R.id.etCaseKeywords)

        if (existing != null) {
            etTitle.setText(existing.title)
            etTopic.setText(existing.topic.ifBlank { existing.keywordList().firstOrNull().orEmpty() })
            etBody.setText(existing.question)
            etExpl.setText(existing.explanation)
            etKw.setText(existing.keywordList().joinToString(", "))
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "새 사례문제" else "사례문제 수정")
            .setView(view)

        builder.showWithEditConfirms(
            this,
            onSave = {
                val title = etTitle.text.toString().trim().ifEmpty { "무제" }
                val question = etBody.text.toString().trim()
                if (question.isEmpty()) {
                    Toast.makeText(this, "문제 내용을 입력해주세요", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    val keywords = parseKeywords(etKw.text.toString())
                    val topic = etTopic.text.toString().trim().ifBlank { keywords.firstOrNull().orEmpty() }
                    val item = MockExamItem(
                        id = existing?.id ?: "",
                        subject = subject,
                        title = title,
                        question = question,
                        explanation = etExpl.text.toString().trim(),
                        keywords = keywords,
                        issues = keywords,
                        topic = topic
                    )
                    if (existing == null) MockExamStore.add(this, item)
                    else MockExamStore.update(this, item)
                    loadData()
                    refreshList()
                    Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
                    true
                }
            },
            onDelete = if (existing == null) null else ({
                MockExamStore.delete(this, existing.id)
                loadData()
                refreshList()
            })
        )
    }

    private fun parseKeywords(raw: String): List<String> =
        raw.split(',', '，', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private class CaseRowAdapter(
        private val onClick: (MockExamItem) -> Unit
    ) : RecyclerView.Adapter<CaseRowAdapter.VH>() {

        private val items = mutableListOf<MockExamItem>()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvRowTitle)
            val tvSubtitle: TextView = v.findViewById(R.id.tvRowSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_content_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            val kws = item.keywordList().take(4).joinToString(" · ")
            val preview = item.question.replace("\n", " ").take(50)
            holder.tvSubtitle.text = if (kws.isNotBlank()) kws else preview
            holder.tvSubtitle.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size

        fun submit(newItems: List<MockExamItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
    }
}
