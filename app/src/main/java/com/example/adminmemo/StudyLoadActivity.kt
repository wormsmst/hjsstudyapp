package com.example.adminmemo

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class StudyLoadActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_load)
        findViewById<TextView>(R.id.btnHalfMinus).setOnClickListener {
            StudyLoadStore.setCardsPerHalfHour(this, StudyLoadStore.cardsPerHalfHour(this) - 1)
            bindAll()
        }
        findViewById<TextView>(R.id.btnHalfPlus).setOnClickListener {
            StudyLoadStore.setCardsPerHalfHour(this, StudyLoadStore.cardsPerHalfHour(this) + 1)
            bindAll()
        }
        bindDays()
        bindAll()
    }

    private fun bindDays() {
        val box = findViewById<LinearLayout>(R.id.layoutLoadDays)
        box.removeAllViews()
        val inf = LayoutInflater.from(this)
        StudyLoadStore.weekDays.forEach { (dow, label) ->
            val row = inf.inflate(R.layout.item_study_load_day, box, false)
            row.tag = dow
            row.findViewById<TextView>(R.id.tvLoadDay).text = label
            row.findViewById<CheckBox>(R.id.chkLoadRest).setOnCheckedChangeListener { _, rest ->
                StudyLoadStore.setRest(this, dow, rest)
                bindRow(row, dow)
                bindSummary()
            }
            row.findViewById<TextView>(R.id.btnLoadMinus).setOnClickListener {
                val next = StudyLoadStore.minutesFor(this, dow) - StudyLoadStore.STEP_MIN
                StudyLoadStore.setMinutes(this, dow, next)
                bindRow(row, dow)
                bindSummary()
            }
            row.findViewById<TextView>(R.id.btnLoadPlus).setOnClickListener {
                val next = StudyLoadStore.minutesFor(this, dow) + StudyLoadStore.STEP_MIN
                StudyLoadStore.setMinutes(this, dow, next)
                bindRow(row, dow)
                bindSummary()
            }
            box.addView(row)
        }
    }

    private fun bindAll() {
        val n = StudyLoadStore.cardsPerHalfHour(this)
        findViewById<TextView>(R.id.tvCardsPerHalf).text = "${n}장"
        findViewById<TextView>(R.id.tvHalfHint).text =
            "30분당 ${n}장입니다. 2시간이면 인출 ${n * 4}장입니다."
        val box = findViewById<LinearLayout>(R.id.layoutLoadDays)
        for (i in 0 until box.childCount) {
            val row = box.getChildAt(i)
            val dow = row.tag as? Int ?: continue
            bindRow(row, dow)
        }
        bindSummary()
    }

    private fun bindRow(row: android.view.View, dow: Int) {
        val rest = StudyLoadStore.isRest(this, dow)
        val chk = row.findViewById<CheckBox>(R.id.chkLoadRest)
        if (chk.isChecked != rest) chk.isChecked = rest
        val minutes = StudyLoadStore.minutesFor(this, dow)
        row.findViewById<TextView>(R.id.tvLoadMinutes).text =
            if (rest) "휴식" else StudyLoadStore.formatMinutes(minutes)
        val canEdit = !rest
        row.findViewById<TextView>(R.id.btnLoadMinus).isEnabled = canEdit && minutes > 0
        row.findViewById<TextView>(R.id.btnLoadPlus).isEnabled =
            canEdit && minutes < StudyLoadStore.MAX_MIN
        val alpha = if (canEdit) 1f else 0.35f
        row.findViewById<TextView>(R.id.btnLoadMinus).alpha = alpha
        row.findViewById<TextView>(R.id.btnLoadPlus).alpha = alpha
        row.findViewById<TextView>(R.id.tvLoadMinutes).alpha = if (rest) 0.55f else 1f
    }

    private fun bindSummary() {
        findViewById<TextView>(R.id.tvLoadToday).text = StudyLoadStore.todayLine(this)
    }
}
