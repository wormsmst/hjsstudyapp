package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SubjectSelectActivity : BaseActivity() {

    private class SubjectAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SubjectAdapter.VH>() {
        private val tileColors = listOf(
            R.color.tile_study,
            R.color.tile_outline,
            R.color.tile_exam,
            R.color.tile_quiz
        )
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: CardView = v as CardView
            val tvTitle: TextView = v.findViewById(R.id.tvRowTitle)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_subject_tile, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val subject = items[position]
            holder.tvTitle.text = when (subject) {
                ALL_SUBJECTS_KEY -> "🌐 전체 과목\n(4과목 랜덤)"
                WEAK_STUDY_KEY -> "약점학습\n최근 인출에서 막힌 장"
                else -> subject
            }
            val colorRes = if (subject == WEAK_STUDY_KEY) R.color.tile_wrong
            else tileColors[position % tileColors.size]
            holder.card.setCardBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, colorRes)
            )
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
            PURPOSE_QUIZ -> "✍️ 인출학습 — 과목 선택"
            PURPOSE_MANAGE -> "📄 본문내용관리 — 과목 선택"
            PURPOSE_MANAGE_CASE -> "⚖️ 사례문제 관리 — 과목 선택"
            PURPOSE_OUTLINE -> "🗺️ 목차학습 — 과목 선택"
            PURPOSE_EXAM -> "⏱️ 모의고사 — 과목 선택"
            PURPOSE_RECALL -> "✍️ 인출학습 — 과목 선택"
            else -> "📖 본문학습 — 과목 선택"
        }
        findViewById<TextView>(R.id.tvSubjectHint).text = when (purpose) {
            PURPOSE_STUDY -> "일하는 동안엔 약점학습으로, 최근 인출에서 막힌 장만 이어서 읽어요. 과목은 섞입니다."
            else -> "과목은 「학습내용 관리」에서 본문을 넣으면 늘어납니다."
        }

        val subjects = if (purpose == PURPOSE_MANAGE_CASE) {
            orderedSubjects(
                MockExamStore.getAll(this)
                    .map { it.subject }
                    .filter { !canonicalizeSubject(it).contains("사무관리") }
            )
        } else {
            CardStore.getSubjects(this)
        }
        val rv = findViewById<RecyclerView>(R.id.rvSubjects)
        val grid = GridLayoutManager(this, 2)
        val items = if (purpose == PURPOSE_STUDY) listOf(WEAK_STUDY_KEY) + subjects else subjects
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (items.getOrNull(position) == WEAK_STUDY_KEY) 2 else 1
        }
        rv.layoutManager = grid

        if (subjects.isEmpty()) {
            Toast.makeText(this, "등록된 과목이 없어요", Toast.LENGTH_SHORT).show()
        }

        rv.adapter = SubjectAdapter(items) { subject ->
            if (subject == WEAK_STUDY_KEY) {
                val n = RecallStore.weakStudyCards(this).size
                if (n == 0) {
                    Toast.makeText(this, "아직 인출에서 막힌 장이 없어요. 저녁 인출을 하면 여기 쌓입니다.", Toast.LENGTH_LONG).show()
                    return@SubjectAdapter
                }
                startActivity(
                    Intent(this, ContentStudyListActivity::class.java)
                        .putExtra(ContentStudyListActivity.EXTRA_WEAK_STUDY, true)
                )
                return@SubjectAdapter
            }
            if (purpose == PURPOSE_EXAM) {
                val intent = Intent(this, ExamSessionActivity::class.java)
                intent.putExtra(EXTRA_SUBJECT, subject)
                startActivity(intent)
                return@SubjectAdapter
            }
            if (purpose == PURPOSE_RECALL || purpose == PURPOSE_QUIZ) {
                startActivity(
                    Intent(this, RecallActivity::class.java)
                        .putExtra(EXTRA_SUBJECT, subject)
                        .putExtra(EXTRA_RECALL_PERIOD, 0)
                )
                return@SubjectAdapter
            }
            val target = when (purpose) {
                PURPOSE_MANAGE -> ContentManageActivity::class.java
                PURPOSE_MANAGE_CASE -> CaseContentManageActivity::class.java
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
