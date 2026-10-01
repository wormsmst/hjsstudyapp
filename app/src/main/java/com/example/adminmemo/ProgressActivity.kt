package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

class ProgressActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress)
        bind()
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        val today = DailyQuestStore.gradedToday(this)
        val goal = StudyProgressStore.dailyGoal(this)
        val openQ = DailyQuestStore.openQuests(this).size
        findViewById<TextView>(R.id.tvProgressToday).text =
            if (openQ > 0) "오늘 인출 ${today}장  /  목표 ${goal}장  ·  퀘스트 ${openQ}개 남음"
            else "오늘 인출 ${today}장  /  목표 ${goal}장"
        val pb = findViewById<ProgressBar>(R.id.pbTodayGoal)
        pb.max = 100
        pb.progress = if (goal <= 0) 0 else (today * 100 / goal).coerceAtMost(100)

        val last = StudyProgressStore.lastAt(this)
        findViewById<TextView>(R.id.tvProgressLast).text =
            if (last <= 0L) "아직 학습 기록이 없어요"
            else "마지막 학습  ${DateFormatters.dateTime(last)}"
        findViewById<TextView>(R.id.tvProgressPace).text = StudyProgressStore.paceLine(this)
        bindWeek()

        val exams = StudyProgressStore.examLogs(this).takeLast(3).reversed()
        findViewById<TextView>(R.id.tvRecentExams).text =
            if (exams.isEmpty()) "모의고사 기록이 아직 없어요"
            else "최근 모의고사  " + exams.joinToString("  ·  ") {
                "${it.subject} ${DateFormatters.dateTime(it.at)}"
            }

        val box = findViewById<LinearLayout>(R.id.layoutSubjectBars)
        box.removeAllViews()
        StudyProgressStore.subjectBars(this).forEach { bar ->
            box.addView(makeBar(bar))
        }

        val weakBox = findViewById<LinearLayout>(R.id.layoutWeakTopics)
        weakBox.removeAllViews()
        val weak = StudyProgressStore.weakTopics(this)
        val empty = findViewById<TextView>(R.id.tvWeakEmpty)
        if (weak.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = "별 1~2인 약점 주제가 없어요"
        } else {
            empty.visibility = View.GONE
            weak.forEach { topic ->
                weakBox.addView(makeWeakRow(topic))
            }
        }
    }

    private fun bindWeek() {
        val box = findViewById<LinearLayout>(R.id.layoutWeekCal)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val flags = StudyProgressStore.last7Flags(this)
        val labels = StudyProgressStore.weekDayLabels()
        val todayIdx = flags.lastIndex
        flags.forEachIndexed { i, day ->
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = android.view.Gravity.CENTER_HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            cell.layoutParams = lp
            val pad = (4 * d).toInt()
            cell.setPadding(pad / 2, pad, pad / 2, pad)

            val wd = TextView(this)
            wd.text = labels.getOrNull(i) ?: ""
            wd.textSize = 12f
            wd.gravity = android.view.Gravity.CENTER
            wd.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (i == todayIdx) R.color.primary else R.color.text_sub
                )
            )
            wd.setTypeface(wd.typeface, android.graphics.Typeface.BOLD)

            val marks = TextView(this)
            val bits = buildList {
                if (day.study) add("본")
                if (day.quiz) add("퀴")
                if (day.exam) add("모")
            }
            marks.text = bits.joinToString(" ").ifBlank { "·" }
            marks.textSize = 11f
            marks.gravity = android.view.Gravity.CENTER
            marks.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (bits.isEmpty()) R.color.text_sub else R.color.text_main
                )
            )
            val mlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mlp.topMargin = (4 * d).toInt()
            marks.layoutParams = mlp
            cell.addView(wd)
            cell.addView(marks)
            box.addView(cell)
        }
    }

    private fun makeBar(bar: StudyProgressStore.SubjectBar): CardView {
        val d = resources.displayMetrics.density
        val cv = CardView(this)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = (10 * d).toInt()
        cv.layoutParams = lp
        cv.radius = 16 * d
        cv.cardElevation = 1 * d
        cv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))
        cv.setOnClickListener {
            startActivity(
                Intent(this, ContentStudyListActivity::class.java)
                    .putExtra(EXTRA_SUBJECT, bar.subject)
            )
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = (16 * d).toInt()
        col.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = bar.subject
        title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        title.textSize = 16f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)

        val sub = TextView(this)
        sub.text = "미학습 ${bar.unseen} · 약함 ${bar.weak} · 보통 ${bar.mid} · 숙달 ${bar.master}  (${bar.masteredPct}%)"
        sub.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
        sub.textSize = 13f
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = (4 * d).toInt()
        slp.bottomMargin = (8 * d).toInt()
        sub.layoutParams = slp

        val stack = LinearLayout(this)
        stack.orientation = LinearLayout.HORIZONTAL
        val h = (10 * d).toInt()
        stack.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            h
        )
        stack.weightSum = bar.total.coerceAtLeast(1).toFloat()
        fun seg(n: Int, color: Int) {
            if (n <= 0) return
            val v = View(this)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, n.toFloat())
            v.layoutParams = lp
            v.setBackgroundColor(ContextCompat.getColor(this, color))
            stack.addView(v)
        }
        if (bar.total == 0) {
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            v.setBackgroundColor(ContextCompat.getColor(this, R.color.mem_unseen))
            stack.addView(v)
        } else {
            seg(bar.unseen, R.color.mem_unseen)
            seg(bar.weak, R.color.mem_weak)
            seg(bar.mid, R.color.mem_mid)
            seg(bar.master, R.color.mem_master)
        }
        col.addView(title)
        col.addView(sub)
        col.addView(stack)
        cv.addView(col)
        return cv
    }

    private fun makeWeakRow(topic: WeakTopic): CardView {
        val d = resources.displayMetrics.density
        val cv = CardView(this)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = (8 * d).toInt()
        cv.layoutParams = lp
        cv.radius = 14 * d
        cv.cardElevation = 1 * d
        cv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))
        cv.setOnClickListener {
            val card = CardStore.getAllCards(this).firstOrNull { it.id == topic.cardId } ?: return@setOnClickListener
            launchConceptDetail(this, card)
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val pad = (14 * d).toInt()
        row.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        title.text = topic.topicTitle
        title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        title.textSize = 15f

        val meta = TextView(this)
        meta.text = "${topic.subject}  ${CardStore.memoryStarsLabel(topic.memory)}"
        meta.setTextColor(ContextCompat.getColor(this, R.color.accent))
        meta.textSize = 13f
        row.addView(title)
        row.addView(meta)
        cv.addView(row)
        return cv
    }
}
