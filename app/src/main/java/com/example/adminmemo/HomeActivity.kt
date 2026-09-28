package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import androidx.cardview.widget.CardView

class HomeActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<CardView>(R.id.tileStudy).setOnClickListener {
            goToSubjectSelect(PURPOSE_STUDY)
        }
        findViewById<CardView>(R.id.tileOutline).setOnClickListener {
            goToSubjectSelect(PURPOSE_OUTLINE)
        }
        findViewById<CardView>(R.id.tileExam).setOnClickListener {
            goToSubjectSelect(PURPOSE_EXAM)
        }
        findViewById<CardView>(R.id.tileQuiz).setOnClickListener {
            goToSubjectSelect(PURPOSE_QUIZ)
        }
        findViewById<CardView>(R.id.tileManage).setOnClickListener {
            goToSubjectSelect(PURPOSE_MANAGE)
        }
        findViewById<CardView>(R.id.tileSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun goToSubjectSelect(purpose: String) {
        val intent = Intent(this, SubjectSelectActivity::class.java)
        intent.putExtra(EXTRA_PURPOSE, purpose)
        startActivity(intent)
    }
}
