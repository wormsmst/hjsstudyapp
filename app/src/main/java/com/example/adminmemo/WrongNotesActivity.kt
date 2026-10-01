package com.example.adminmemo

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class WrongNotesActivity : BaseActivity() {

    private lateinit var adapter: NoteAdapter
    private var todayOnly = false
    private var sortByCount = false
    private var filterSubject: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_notes)

        val rv = findViewById<RecyclerView>(R.id.rvWrongNotes)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = NoteAdapter(
            onClick = { openEntry(it) },
            onLong = { removeEntry(it) }
        )
        rv.adapter = adapter

        findViewById<Button>(R.id.btnWrongAll).setOnClickListener {
            todayOnly = false
            refresh()
        }
        findViewById<Button>(R.id.btnWrongToday).setOnClickListener {
            todayOnly = true
            refresh()
        }
        findViewById<Button>(R.id.btnWrongSort).setOnClickListener {
            sortByCount = !sortByCount
            findViewById<Button>(R.id.btnWrongSort).text = if (sortByCount) "틀린순" else "최근순"
            refresh()
        }
        findViewById<Button>(R.id.btnWrongQuiz).setOnClickListener { startReviewQuiz() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun currentList(): List<WrongEntry> {
        var list = if (todayOnly) WrongNoteStore.today(this) else WrongNoteStore.all(this)
        val want = filterSubject
        if (!want.isNullOrBlank()) {
            list = list.filter { canonicalizeSubject(it.subject) == want }
        }
        return if (sortByCount) list.sortedByDescending { it.wrongCount } else list
    }

    private fun refresh() {
        rebuildChips()
        val list = currentList()
        adapter.submit(list)
        val empty = findViewById<TextView>(R.id.tvWrongEmpty)
        if (list.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = if (todayOnly) "오늘 틀린 항목이 없어요" else "오답노트에 아직 없어요. 모의고사에서 약했던 문항이 쌓여요."
        } else {
            empty.visibility = View.GONE
        }
    }

    private fun rebuildChips() {
        val box = findViewById<LinearLayout>(R.id.layoutWrongChips)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val chips = listOf(null to "전체") + WrongNoteStore.subjects(this).map { it to it }
        chips.forEach { (key, label) ->
            val b = Button(this)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            b.layoutParams = lp
            b.minHeight = 0
            b.minimumHeight = 0
            b.text = label
            b.textSize = 13f
            val selected = filterSubject == key
            b.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.primary else R.color.text_main)
            )
            b.setOnClickListener {
                filterSubject = key
                refresh()
            }
            box.addView(b)
        }
    }

    private fun openEntry(entry: WrongEntry) {
        when (entry.kind) {
            "card" -> {
                val concept = resolveConceptCard(this, entry.targetId)
                if (concept == null) {
                    Toast.makeText(this, "이 카드의 본문을 찾지 못했어요", Toast.LENGTH_SHORT).show()
                } else {
                    launchConceptDetail(this, concept)
                }
            }
            "case" -> openCase(entry)
            else -> Toast.makeText(this, entry.title, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCase(entry: WrongEntry) {
        val item = MockExamStore.getAll(this).firstOrNull { it.id == entry.targetId }
        if (item == null) {
            startActivity(
                Intent(this, CaseContentManageActivity::class.java)
                    .putExtra(EXTRA_SUBJECT, entry.subject)
            )
            return
        }
        val body = buildString {
            append(item.question.trim())
            if (item.explanation.isNotBlank()) {
                append("\n\n—— 해답 ——\n")
                append(item.explanation.trim())
            }
        }
        AlertDialog.Builder(this)
            .setTitle(item.title.ifBlank { "사례문제" })
            .setMessage(body)
            .setPositiveButton("닫기", null)
            .setNeutralButton("수정") { _, _ ->
                startActivity(
                    Intent(this, CaseContentManageActivity::class.java)
                        .putExtra(EXTRA_SUBJECT, entry.subject)
                        .putExtra(CaseContentManageActivity.EXTRA_OPEN_CASE_ID, item.id)
                )
            }
            .show()
    }

    private fun removeEntry(entry: WrongEntry) {
        WrongNoteStore.remove(this, entry.id)
        refresh()
        Toast.makeText(this, "오답노트에서 뺐어요", Toast.LENGTH_SHORT).show()
    }

    private fun startReviewQuiz() {
        val list = currentList()
        val cardSubjects = list.filter { it.kind == "card" }.map { canonicalizeSubject(it.subject) }.distinct()
        if (cardSubjects.isEmpty()) {
            Toast.makeText(this, "퀴즈로 복습할 카드 오답이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val subject = if (cardSubjects.size == 1) cardSubjects.first() else ALL_SUBJECTS_KEY
        launchQuizSession(this, subject, true)
    }

    private class NoteAdapter(
        private val onClick: (WrongEntry) -> Unit,
        private val onLong: (WrongEntry) -> Unit
    ) : RecyclerView.Adapter<NoteAdapter.VH>() {
        private val items = mutableListOf<WrongEntry>()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvRowTitle)
            val sub: TextView = v.findViewById(R.id.tvRowSubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_content_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            val leech = if (e.wrongCount >= 3) "반복 · " else ""
            holder.title.text = e.title
            holder.sub.visibility = View.VISIBLE
            val whenText = if (e.lastWrongAt <= 0L) "" else " · ${DateFormatters.dateTime(e.lastWrongAt)}"
            holder.sub.text = "$leech${e.subject} · ${e.typeLabel} · ${e.wrongCount}회$whenText"
            holder.itemView.setOnClickListener { onClick(e) }
            holder.itemView.setOnLongClickListener {
                onLong(e)
                true
            }
        }

        override fun getItemCount() = items.size

        fun submit(list: List<WrongEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }
    }
}
