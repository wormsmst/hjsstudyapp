package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView

class RecallPickActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recall_pick)
        findViewById<CardView>(R.id.tileRecallAll).setOnClickListener { startRecall(ALL_SUBJECTS_KEY, 0) }
        findViewById<CardView>(R.id.tileRecallPeriod1).setOnClickListener { startRecall(ALL_SUBJECTS_KEY, 1) }
        findViewById<CardView>(R.id.tileRecallPeriod2).setOnClickListener { startRecall(ALL_SUBJECTS_KEY, 2) }
        findViewById<CardView>(R.id.tileRecallSubject).setOnClickListener {
            val subject = RecallStore.takeOneSubject(this)
            Toast.makeText(this, "오늘은 $subject 부터 꺼냅니다", Toast.LENGTH_SHORT).show()
            startRecall(subject, 0)
        }
        findViewById<CardView>(R.id.tileRecallCase).setOnClickListener {
            startRecall(ALL_SUBJECTS_KEY, 0, caseMode = true)
        }
        findViewById<CardView>(R.id.tileRecallUnseen).setOnClickListener {
            startRecall(ALL_SUBJECTS_KEY, 0, unseen = true)
        }
    }

    override fun onStart() {
        super.onStart()
        val next = RecallStore.peekOneSubject(this)
        findViewById<TextView>(R.id.tvRecallSubjectNext).text = "다음  $next  ·  약한 과목은 더 자주"
        findViewById<TextView>(R.id.tvRecallPickCoach).text = CoachHints.PICK
    }

    private fun startRecall(subject: String, period: Int, caseMode: Boolean = false, unseen: Boolean = false) {
        startActivity(
            Intent(this, RecallActivity::class.java)
                .putExtra(EXTRA_SUBJECT, subject)
                .putExtra(EXTRA_RECALL_PERIOD, period)
                .putExtra(EXTRA_RECALL_CASE, caseMode)
                .putExtra(EXTRA_RECALL_UNSEEN, unseen)
        )
    }
}
