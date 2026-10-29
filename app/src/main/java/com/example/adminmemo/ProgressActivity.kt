package com.example.adminmemo

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

class ProgressActivity : BaseActivity() {

    private var weekShift = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress)
        findViewById<TextView>(R.id.btnWeekOlder).setOnClickListener {
            if (weekShift >= StudyWeekStore.MAX_SHIFT) return@setOnClickListener
            weekShift = (weekShift + StudyWeekStore.STEP).coerceAtMost(StudyWeekStore.MAX_SHIFT)
            bindWeeks()
        }
        findViewById<TextView>(R.id.btnWeekNewer).setOnClickListener {
            if (weekShift <= 0) return@setOnClickListener
            weekShift = (weekShift - StudyWeekStore.STEP).coerceAtLeast(0)
            bindWeeks()
        }
        bind()
    }

    override fun onResume() {
        super.onResume()
        if (FirebaseSyncManager.isSignedIn()) {
            FirebaseSyncManager.syncProgressNow(this) { _, _ ->
                runOnUiThread { bind() }
            }
        } else {
            bind()
        }
    }

    private fun bind() {
        findViewById<TextView>(R.id.tvProgressUnseenPace).text =
            StudyProgressStore.unseenLoadLine(this)
        bindShareLegend()
        val box = findViewById<LinearLayout>(R.id.layoutSubjectBars)
        box.removeAllViews()
        StudyProgressStore.subjectBars(this).forEach { bar ->
            box.addView(makeBar(bar))
        }
        bindWeeks()
    }

    private fun bindWeeks() {
        val box = findViewById<LinearLayout>(R.id.layoutWeekStats)
        box.removeAllViews()
        StudyWeekStore.window(this, weekShift).forEach { stat ->
            box.addView(makeWeekRow(stat))
        }
        val newer = findViewById<TextView>(R.id.btnWeekNewer)
        val older = findViewById<TextView>(R.id.btnWeekOlder)
        val canNewer = weekShift > 0
        val canOlder = weekShift < StudyWeekStore.MAX_SHIFT
        newer.isEnabled = canNewer
        newer.alpha = if (canNewer) 1f else 0.35f
        older.isEnabled = canOlder
        older.alpha = if (canOlder) 1f else 0.35f
    }

    private fun makeWeekRow(stat: WeekStudyStat): CardView {
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

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = (14 * d).toInt()
        col.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = if (stat.isCurrent) "${stat.rangeLabel}  이번 주" else stat.rangeLabel
        title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        title.textSize = 14f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)

        val meta = TextView(this)
        meta.text = "인출 ${stat.recalled}장  ·  신규 ${stat.news}장  ·  사례 ${stat.cases}회  ·  모의 ${stat.exams}회"
        meta.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
        meta.textSize = 13f
        val mlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        mlp.topMargin = (4 * d).toInt()
        meta.layoutParams = mlp

        col.addView(title)
        col.addView(meta)
        cv.addView(col)
        return cv
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

        val stack = LinearLayout(this)
        stack.orientation = LinearLayout.HORIZONTAL
        val h = (32 * d).toInt()
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            h
        )
        slp.topMargin = (10 * d).toInt()
        stack.layoutParams = slp
        stack.weightSum = bar.total.coerceAtLeast(1).toFloat()
        fun seg(n: Int, color: Int) {
            if (n <= 0) return
            val cell = FrameLayout(this)
            cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, n.toFloat())
            cell.setBackgroundColor(ContextCompat.getColor(this, color))
            val pct = bar.share(n)
            if (pct > 0) {
                val tv = TextView(this)
                tv.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                tv.gravity = Gravity.CENTER
                tv.includeFontPadding = false
                tv.maxLines = 1
                tv.text = "$pct%"
                tv.textSize = 11f
                tv.setTextColor(0xFFFFFFFF.toInt())
                cell.addView(tv)
            }
            stack.addView(cell)
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
            seg(bar.master, R.color.mem_master)
            seg(bar.mid, R.color.mem_mid)
            seg(bar.weak, R.color.mem_weak)
            seg(bar.unseen, R.color.mem_unseen)
        }
        col.addView(title)
        col.addView(stack)
        cv.addView(col)
        return cv
    }

    private fun colorChip(color: Int, d: Float): View {
        val v = View(this)
        val size = (12 * d).toInt()
        v.layoutParams = LinearLayout.LayoutParams(size, size)
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = 3 * d
        g.setColor(ContextCompat.getColor(this, color))
        v.background = g
        return v
    }

    private fun bindShareLegend() {
        val host = findViewById<LinearLayout>(R.id.layoutShareLegend)
        if (host.childCount > 0) return
        host.orientation = LinearLayout.VERTICAL
        val d = resources.displayMetrics.density
        val items = listOf(
            R.color.mem_master to "잘 됨",
            R.color.mem_mid to "보통",
            R.color.mem_weak to "약함",
            R.color.mem_unseen to "아직 안 함"
        )
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            pair.forEach { (color, label) ->
                val chip = colorChip(color, d)
                (chip.layoutParams as LinearLayout.LayoutParams).marginEnd = (6 * d).toInt()
                val tv = TextView(this)
                tv.text = label
                tv.textSize = 12f
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
                val tlp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tlp.marginEnd = (16 * d).toInt()
                tv.layoutParams = tlp
                row.addView(chip)
                row.addView(tv)
            }
            host.addView(row)
        }
    }
}
