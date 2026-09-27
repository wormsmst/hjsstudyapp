package com.example.adminmemo

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ContentManageActivity : BaseActivity() {

    private lateinit var subject: String
    private lateinit var adapter: ContentAdapter
    private var query = ""

    private var conceptCards: List<Card> = emptyList()

    private lateinit var fab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_manage)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""

        val rv = findViewById<RecyclerView>(R.id.rvManageList)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ContentAdapter(
            titleFn = { it.topicTitle },
            subtitleFn = { card -> card.back.take(40).replace("\n", " ") },
            onClick = { card -> showEditConceptDialog(card) }
        )
        rv.adapter = adapter

        val touchCallback = object : ItemTouchHelper.SimpleCallback(0, 0) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (query.isBlank()) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    0
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val newOrder = adapter.currentList().map { it.id }
                CardStore.setCustomOrder(this@ContentManageActivity, subject, newOrder)
                conceptCards = sortConceptCards(this@ContentManageActivity, conceptCards, subject)
            }
        }
        ItemTouchHelper(touchCallback).attachToRecyclerView(rv)

        fab = findViewById(R.id.fabManageAdd)
        fab.setOnClickListener { showAddConceptDialog() }

        findViewById<EditText>(R.id.etManageSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString() ?: ""
                findViewById<TextView>(R.id.tvReorderHint).visibility =
                    if (query.isBlank()) View.VISIBLE else View.GONE
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        loadData()
        refreshList()
    }

    private fun loadData() {
        val all = CardStore.getAllCards(this).filter { it.subject == subject }
        val rawConcepts = all.filter { it.type == "concept" }
        conceptCards = sortConceptCards(this, rawConcepts, subject)
    }

    private fun refreshList() {
        val q = query.trim()
        val filtered = if (q.isEmpty()) conceptCards else conceptCards.filter {
            it.topicTitle.contains(q, true) || it.back.contains(q, true) || it.mnemonic.contains(q, true)
        }
        adapter.submitList(filtered)
    }

    // ---- 새 주제(개념카드) 추가 ----
    private fun showAddConceptDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_concept, null)
        val etTitle = view.findViewById<EditText>(R.id.etConceptTitle)
        val etBody = view.findViewById<EditText>(R.id.etConceptBody)

        AlertDialog.Builder(this)
            .setTitle("새 주제 추가")
            .setView(view)
            .setPositiveButton("추가") { _, _ ->
                val title = etTitle.text.toString().trim()
                val body = etBody.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(this, "제목을 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CardStore.addConceptCard(this, subject, title, body)
                loadData(); refreshList()
                Toast.makeText(this, "새 주제를 추가했어요. 맨 아래에 있어요 — 길게 눌러서 순서를 옮길 수 있어요", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---- 개념카드 편집 ----
    private fun showEditConceptDialog(card: Card) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_concept, null)
        val etTitle = view.findViewById<EditText>(R.id.etConceptTitle)
        val etBody = view.findViewById<EditText>(R.id.etConceptBody)
        etTitle.setText(card.topicTitle)
        etBody.setText(card.back)

        AlertDialog.Builder(this)
            .setTitle("개념카드 수정")
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                val newTitle = etTitle.text.toString().trim().ifEmpty { "(제목없음)" }
                val newBody = etBody.text.toString().trim()
                val updated = card.copy(
                    topicTitle = newTitle,
                    title = "${card.num} $newTitle".trim(),
                    front = if (card.grade.isNotBlank()) "$newTitle\n(${card.grade})" else newTitle,
                    back = newBody
                )
                CardStore.updateCard(this, updated)
                loadData(); refreshList()
                Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("삭제") { _, _ ->
                CardStore.deleteCard(this, card.id)
                loadData(); refreshList()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
