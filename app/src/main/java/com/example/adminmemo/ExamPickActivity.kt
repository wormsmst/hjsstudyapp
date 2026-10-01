package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import androidx.cardview.widget.CardView

class ExamPickActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam_pick)
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
