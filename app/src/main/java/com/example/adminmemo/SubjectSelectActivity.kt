package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SubjectSelectActivity : BaseActivity() {

    private class SubjectAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SubjectAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvRowTitle)
            val tvSubtitle: TextView = v.findViewById(R.id.tvRowSubtitle)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_content_row, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val subject = items[position]
            holder.tvTitle.text = if (subject == ALL_SUBJECTS_KEY) "🌐 전체 과목 (4과목 랜덤)" else "📚 $subject"
            holder.tvSubtitle.visibility = View.GONE
            holder.itemView.setOnClickListener { onClick(subject) }
        }
        override fun getItemCount() = items.size
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject_select)

        val purpose = intent.getStringExtra(EXTRA_PURPOSE) ?: PURPOSE_STUDY
        val header = findViewById<TextView>(R.id.tvSubjectHeader)
        header.text = when (purpose) {
            PURPOSE_QUIZ -> "🎲 학습퀴즈 — 과목 선택"
            PURPOSE_MANAGE -> "🗂️ 학습내용관리 — 과목 선택"
            PURPOSE_OUTLINE -> "🗺️ 목차학습 — 과목 선택"
            PURPOSE_EXAM -> "⏱️ 모의고사 — 과목 선택"
            else -> "📖 본문학습 — 과목 선택"
        }

        val subjects = CardStore.getSubjects(this)
        val rv = findViewById<RecyclerView>(R.id.rvSubjects)
        rv.layoutManager = LinearLayoutManager(this)

        if (subjects.isEmpty()) {
            Toast.makeText(this, "등록된 과목이 없어요", Toast.LENGTH_SHORT).show()
        }

        rv.adapter = SubjectAdapter(subjects) { subject ->
            if (purpose == PURPOSE_EXAM) {
                val intent = Intent(this, ExamSessionActivity::class.java)
                intent.putExtra(EXTRA_SUBJECT, subject)
                startActivity(intent)
                return@SubjectAdapter
            }
            val target = when (purpose) {
                PURPOSE_QUIZ -> QuizSessionActivity::class.java
                PURPOSE_MANAGE -> ContentManageActivity::class.java
                else -> ContentStudyListActivity::class.java
            }
            val intent = Intent(this, target)
            intent.putExtra(EXTRA_SUBJECT, subject)
            if (purpose == PURPOSE_OUTLINE) {
                intent.putExtra(ContentStudyListActivity.EXTRA_OUTLINE_MODE, true)
            }
            startActivity(intent)
        }
    }
}
