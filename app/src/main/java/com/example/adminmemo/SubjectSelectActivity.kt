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
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SubjectSelectActivity : BaseActivity() {

    private data class SubjectRow(
        val key: String,
        val title: String,
        val sub: String = "",
        val recommend: Boolean = false,
        val remain: Int = 0,
        val continuing: Boolean = false
    )

    private class SubjectAdapter(
        private val items: List<SubjectRow>,
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
            val tvSub: TextView = v.findViewById(R.id.tvRowSub)
            val tvBadge: TextView = v.findViewById(R.id.tvRowBadge)
            val tvContinue: TextView = v.findViewById(R.id.tvRowContinue)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_subject_tile, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.tvTitle.text = row.title
            holder.tvSub.isVisible = row.sub.isNotBlank()
            holder.tvSub.text = row.sub
            holder.tvContinue.isVisible = row.continuing
            when {
                row.continuing || row.remain > 0 -> {
                    bindStatusBadge(holder.tvBadge, done = false, inProgress = row.continuing, n = row.remain)
                    holder.tvBadge.setTextColor(0xFFFFFFFF.toInt())
                }
                row.recommend -> {
                    holder.tvBadge.visibility = View.VISIBLE
                    holder.tvBadge.text = "추천"
                    holder.tvBadge.setBackgroundResource(R.drawable.bg_recommend_badge)
                    holder.tvBadge.setTextColor(0xFF5A3E08.toInt())
                }
                else -> holder.tvBadge.visibility = View.GONE
            }
            val colorRes = if (row.key == WEAK_STUDY_KEY) R.color.tile_wrong
            else tileColors[position % tileColors.size]
            holder.card.setCardBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, colorRes)
            )
            holder.itemView.setOnClickListener { onClick(row.key) }
        }
        override fun getItemCount() = items.size
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject_select)

        val purpose = intent.getStringExtra(EXTRA_PURPOSE) ?: PURPOSE_STUDY
        val header = findViewById<TextView>(R.id.tvSubjectHeader)
        val headerIcon = findViewById<android.widget.ImageView>(R.id.ivSubjectHeaderIcon)
        header.text = when (purpose) {
            PURPOSE_QUIZ -> "✍️ 인출학습 — 과목 선택"
            PURPOSE_MANAGE -> "📄 본문내용관리 — 과목 선택"
            PURPOSE_MANAGE_CASE -> "⚖️ 사례문제 관리 — 과목 선택"
            PURPOSE_OUTLINE -> "목차학습 — 과목 선택"
            PURPOSE_EXAM -> "⏱️ 모의고사 — 과목 선택"
            PURPOSE_RECALL -> "한 과목 인출 — 과목 선택"
            else -> "본문학습 — 과목 선택"
        }
        when (purpose) {
            PURPOSE_STUDY -> {
                headerIcon.visibility = View.VISIBLE
                headerIcon.setImageResource(R.drawable.ic_tile_study)
            }
            PURPOSE_OUTLINE -> {
                headerIcon.visibility = View.VISIBLE
                headerIcon.setImageResource(R.drawable.ic_tile_outline)
            }
            else -> headerIcon.visibility = View.GONE
        }
        findViewById<TextView>(R.id.tvSubjectHint).text = when (purpose) {
            PURPOSE_STUDY -> "일하는 동안엔 약점학습으로, 최근 인출에서 막힌 장만 이어서 읽어요. 과목은 섞입니다."
            PURPOSE_OUTLINE -> "과목을 고르면 그 과목 전체를 목차로 외워요. 약점학습은 최근 인출에서 막힌 장만 모읍니다."
            PURPOSE_RECALL -> "아직 안 연 장이 있으면 그 비율이 높은 과목, 다 열었으면 약점이 가장 많은 과목을 추천합니다."
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
        val coverage = if (purpose == PURPOSE_RECALL) {
            RecallStore.coverageBySubject(this).associateBy { it.subject }
        } else {
            emptyMap()
        }
        val recommendKey = if (purpose == PURPOSE_RECALL) {
            RecallStore.recommendSubject(coverage.values.toList())
        } else {
            null
        }
        val allOpened = purpose == PURPOSE_RECALL &&
            coverage.values.filter { it.total > 0 }.all { it.unseen <= 0 }
        val contSnap = if (purpose == PURPOSE_RECALL) {
            val snap = RecallSessionStore.load(this)
            if (
                RecallSessionStore.unfinished(snap) &&
                snap != null &&
                RecallSessionStore.isToday(snap) &&
                RecallSessionStore.pickTile(snap) == "one"
            ) snap else null
        } else {
            null
        }
        val subjectRows = subjects.map { sub ->
            val cov = coverage[canonicalizeSubject(sub)]
            val unseen = cov?.unseen ?: 0
            val weak = cov?.weak ?: 0
            val continuing = contSnap != null &&
                canonicalizeSubject(contSnap.subject) == canonicalizeSubject(sub)
            SubjectRow(
                key = sub,
                title = sub,
                sub = when {
                    purpose != PURPOSE_RECALL -> ""
                    allOpened || unseen <= 0 -> "약함 ${weak}장"
                    else -> "미인출 ${unseen}장"
                },
                recommend = purpose == PURPOSE_RECALL && canonicalizeSubject(sub) == recommendKey,
                remain = if (continuing && contSnap != null) {
                    (contSnap.queueIds.size - contSnap.index).coerceAtLeast(0)
                } else {
                    0
                },
                continuing = continuing
            )
        }
        val items = if (purpose == PURPOSE_STUDY || purpose == PURPOSE_OUTLINE) {
            listOf(SubjectRow(WEAK_STUDY_KEY, "약점학습\n최근 인출에서 막힌 장")) + subjectRows
        } else {
            subjectRows
        }
        val rv = findViewById<RecyclerView>(R.id.rvSubjects)
        val grid = GridLayoutManager(this, 2)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (items.getOrNull(position)?.key == WEAK_STUDY_KEY) 2 else 1
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
                        .putExtra(
                            ContentStudyListActivity.EXTRA_OUTLINE_MODE,
                            purpose == PURPOSE_OUTLINE
                        )
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
