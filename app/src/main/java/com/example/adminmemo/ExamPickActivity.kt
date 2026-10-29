package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import androidx.cardview.widget.CardView

class ExamPickActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam_pick)
        findViewById<android.widget.TextView>(R.id.tvExamP1Sub).text =
            "${StudyProgressStore.periodLabel(this, 1)}  ·  100분"
        findViewById<android.widget.TextView>(R.id.tvExamP2Sub).text =
            "${StudyProgressStore.periodLabel(this, 2)}  ·  100분"
        findViewById<CardView>(R.id.tileExamPeriod1).setOnClickListener { startPeriod(1) }
        findViewById<CardView>(R.id.tileExamPeriod2).setOnClickListener { startPeriod(2) }
        findViewById<CardView>(R.id.tileExamDrill).setOnClickListener {
            startActivity(
                Intent(this, SubjectSelectActivity::class.java)
                    .putExtra(EXTRA_PURPOSE, PURPOSE_EXAM)
            )
        }
    }

    private fun startPeriod(period: Int) {
        startActivity(
            Intent(this, ExamSessionActivity::class.java)
                .putExtra(ExamSessionActivity.EXTRA_PERIOD, period)
        )
    }
}
