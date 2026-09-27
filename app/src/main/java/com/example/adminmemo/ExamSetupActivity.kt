package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

class ExamSetupActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam_setup)

        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ALL_SUBJECTS_KEY
        findViewById<TextView>(R.id.tvExamSubjectLabel).text =
            if (subject == ALL_SUBJECTS_KEY) "🌐 전체 과목에서 랜덤 출제" else "📚 $subject"

        val tvCount = findViewById<TextView>(R.id.tvQuestionCount)
        val seekCount = findViewById<SeekBar>(R.id.seekQuestionCount)
        seekCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvCount.text = "${progress + 1}문제"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val tvTime = findViewById<TextView>(R.id.tvTimeLimit)
        val seekTime = findViewById<SeekBar>(R.id.seekTimeLimit)
        seekTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTime.text = "${(progress + 2) * 5}분"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnStartExam).setOnClickListener {
            val count = seekCount.progress + 1
            val minutes = (seekTime.progress + 2) * 5
            val intent = Intent(this, ExamSessionActivity::class.java)
            intent.putExtra(EXTRA_SUBJECT, subject)
            intent.putExtra(ExamSessionActivity.EXTRA_COUNT, count)
            intent.putExtra(ExamSessionActivity.EXTRA_MINUTES, minutes)
            startActivity(intent)
        }
    }
}
