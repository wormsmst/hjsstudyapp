package com.example.adminmemo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
        findViewById<TextView>(R.id.btnComplyMinus).setOnClickListener {
            StudyLoadStore.stepComplyPct(this, -1)
            bindAll()
        }
        findViewById<TextView>(R.id.btnComplyPlus).setOnClickListener {
            StudyLoadStore.stepComplyPct(this, 1)
            bindAll()
        }
        findViewById<TextView>(R.id.btnTargetMinus).setOnClickListener {
            StudyLoadStore.stepRecallTarget(this, -1)
            bindAll()
        }
        findViewById<TextView>(R.id.btnTargetPlus).setOnClickListener {
            StudyLoadStore.stepRecallTarget(this, 1)
            bindAll()
        }
        findViewById<TextView>(R.id.btnNewMinus).setOnClickListener {
            StudyLoadStore.stepNewPerDay(this, -1)
            bindAll()
        }
        findViewById<TextView>(R.id.btnNewPlus).setOnClickListener {
            StudyLoadStore.stepNewPerDay(this, 1)
            bindAll()
        }
        bindDays()
        bindAll()
    }

    override fun onResume() {
        super.onResume()
        CardRepository.invalidateCache()
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
            "2시간이면 인출 ${n * 4}장입니다."
        val box = findViewById<LinearLayout>(R.id.layoutLoadDays)
        for (i in 0 until box.childCount) {
            val row = box.getChildAt(i)
            val dow = row.tag as? Int ?: continue
            bindRow(row, dow)
        }
        bindForecastSteppers()
        bindSummary()
    }

    private fun bindForecastSteppers() {
        val pct = StudyLoadStore.complyPct(this)
        findViewById<TextView>(R.id.tvLoadComply).text = "${pct}%"
        val pctIdx = StudyLoadStore.COMPLY_PCTS.indexOf(pct)
        setStepperEnabled(R.id.btnComplyMinus, pctIdx > 0)
        setStepperEnabled(R.id.btnComplyPlus, pctIdx in 0 until StudyLoadStore.COMPLY_PCTS.lastIndex)
        val target = StudyLoadStore.recallTarget(this)
        findViewById<TextView>(R.id.tvLoadTarget).text = "${target}회"
        val tIdx = StudyLoadStore.RECALL_TARGETS.indexOf(target)
        setStepperEnabled(R.id.btnTargetMinus, tIdx > 0)
        setStepperEnabled(R.id.btnTargetPlus, tIdx in 0 until StudyLoadStore.RECALL_TARGETS.lastIndex)
        val neu = StudyLoadStore.newPerDay(this)
        findViewById<TextView>(R.id.tvNewPerDay).text = "${neu}장"
        val nIdx = StudyLoadStore.NEW_PER_DAYS.indexOf(neu)
        setStepperEnabled(R.id.btnNewMinus, nIdx > 0)
        setStepperEnabled(R.id.btnNewPlus, nIdx in 0 until StudyLoadStore.NEW_PER_DAYS.lastIndex)
    }

    private fun setStepperEnabled(id: Int, on: Boolean) {
        val tv = findViewById<TextView>(id)
        tv.isEnabled = on
        tv.alpha = if (on) 1f else 0.35f
    }

    private fun bindRow(row: View, dow: Int) {
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
        val f = StudyLoadStore.forecast(this)
        val week = if (f.weekPlan == f.weekEffective) {
            "주 인출 ${f.weekPlan}장"
        } else {
            "주 인출 계획 ${f.weekPlan}장  ·  이행 ${f.weekEffective}장"
        }
        val body = when {
            f.topics <= 0 -> "인출할 주제가 없어요"
            f.alreadyDone ->
                "$week\n전 주제 ${f.topics}장  ·  목표 ${f.target}회\n이미 목표에 도달했습니다"
            f.days == null || f.doneAt == null ->
                "$week\n전 주제 ${f.topics}장  ·  목표 ${f.target}회  ·  남은 ${f.remaining}회\n공부하는 요일이 없어 날짜를 계산할 수 없어요"
            else ->
                "$week\n전 주제 ${f.topics}장  ·  목표 ${f.target}회  ·  남은 ${f.remaining}회\n약 ${f.days}일  ·  ${DateFormatters.dateOnly(f.doneAt)}"
        }
        findViewById<TextView>(R.id.tvLoadForecast).text = body
    }
}
