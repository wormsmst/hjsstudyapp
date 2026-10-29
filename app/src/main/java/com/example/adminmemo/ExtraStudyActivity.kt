package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView

class ExtraStudyActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extra_study)
        bindCoach(R.id.tvExtraCoach, "boost_topics", CoachHints.BOOST)
        findViewById<CardView>(R.id.tileExtra15).setOnClickListener { startBoost(15) }
        findViewById<CardView>(R.id.tileExtra30).setOnClickListener { startBoost(30) }
        findViewById<CardView>(R.id.tileExtra45).setOnClickListener { startBoost(45) }
        bindCounts()
    }

    override fun onStart() {
        super.onStart()
        bindCounts()
    }

    private fun bindCounts() {
        val sub = RecallStore.boostSubject(this)
        fun line(min: Int): String {
            val want = StudyLoadStore.extraCardCount(this, min)
            val n = RecallStore.boostQueue(this, want).size
            return when {
                n <= 0 -> "지금 뽑을 장이 없어요"
                sub.isNullOrBlank() -> "인출 ${n}장"
                else -> "$sub  ·  인출 ${n}장"
            }
        }
        findViewById<TextView>(R.id.tvExtra15).text = line(15)
        findViewById<TextView>(R.id.tvExtra30).text = line(30)
        findViewById<TextView>(R.id.tvExtra45).text = line(45)
        val open = DailyQuestStore.openBoostCount(this)
        val tv = findViewById<TextView>(R.id.tvExtraOpen)
        if (open > 0) {
            tv.visibility = View.VISIBLE
            tv.text = "남은 보강 퀘스트 ${open}개. 다음 세트를 열기 전에 먼저 쓰는 게 좋습니다."
            tv.setOnClickListener {
                startActivity(Intent(this, QuestActivity::class.java))
            }
        } else {
            tv.visibility = View.GONE
        }
    }

    private fun startBoost(minutes: Int) {
        fun go() {
            val n = StudyLoadStore.extraCardCount(this, minutes)
            val cards = RecallStore.boostQueue(this, n)
            if (cards.isEmpty()) {
                Toast.makeText(this, "지금 뽑을 장이 없어요. 오늘 푼 장과 내일 기한 장은 빼 둡니다.", Toast.LENGTH_LONG).show()
                return
            }
            startActivity(
                Intent(this, RecallActivity::class.java)
                    .putStringArrayListExtra(EXTRA_RECALL_CARD_IDS, ArrayList(cards.map { it.id }))
                    .putExtra(EXTRA_RECALL_BOOST, true)
            )
        }
        if (FirebaseSyncManager.isSignedIn()) {
            FirebaseSyncManager.syncProgressNow(this) { _, _ ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    go()
                }
            }
        } else {
            go()
        }
    }
}
