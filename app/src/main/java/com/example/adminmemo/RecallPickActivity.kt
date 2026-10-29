package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.cardview.widget.CardView

class RecallPickActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recall_pick)
        findViewById<CardView>(R.id.tileRecallAll).setOnClickListener { startRecall(ALL_SUBJECTS_KEY, 0) }
        findViewById<CardView>(R.id.tileRecallPeriod1).setOnClickListener { startRecall(ALL_SUBJECTS_KEY, 1) }
        findViewById<CardView>(R.id.tileRecallPeriod2).setOnClickListener { startRecall(ALL_SUBJECTS_KEY, 2) }
        findViewById<CardView>(R.id.tileRecallSubject).setOnClickListener {
            val snap = todayUnfinished()
            if (snap != null && RecallSessionStore.pickTile(snap) == "one") {
                startRecall(snap.subject, 0)
                return@setOnClickListener
            }
            startActivity(
                Intent(this, SubjectSelectActivity::class.java)
                    .putExtra(EXTRA_PURPOSE, PURPOSE_RECALL)
            )
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
        bindCoach(R.id.tvRecallPickCoach, "pick", CoachHints.PICK)
        bindContinueBadges()
    }

    private fun todayUnfinished(): RecallSessionSnap? {
        val snap = RecallSessionStore.load(this) ?: return null
        if (!RecallSessionStore.unfinished(snap) || !RecallSessionStore.isToday(snap)) return null
        return snap
    }

    private fun bindContinueBadges() {
        val defaults = mapOf(
            R.id.tvRecallP1Sub to StudyProgressStore.periodLabel(this, 1),
            R.id.tvRecallP2Sub to StudyProgressStore.periodLabel(this, 2),
            R.id.tvRecallCaseSub to "결론 → 이유 → 목차",
            R.id.tvRecallUnseenSub to "아직 한 번도 안 쓴 주제"
        )
        defaults.forEach { (id, text) ->
            findViewById<TextView>(id).apply {
                this.text = text
                visibility = View.VISIBLE
            }
        }
        findViewById<TextView>(R.id.tvRecallSubjectNext).visibility = View.GONE
        findViewById<TextView>(R.id.tvRecallAllSub).visibility = View.GONE
        val snap = todayUnfinished()
        val tile = snap?.let { RecallSessionStore.pickTile(it) }
        val left = if (snap != null) (snap.queueIds.size - snap.index).coerceAtLeast(0) else 0
        listOf(
            Triple(R.id.tvRecallSubjectBadge, R.id.tvRecallSubjectCont, "one"),
            Triple(R.id.tvRecallAllBadge, R.id.tvRecallAllCont, "all"),
            Triple(R.id.tvRecallP1Badge, R.id.tvRecallP1Cont, "p1"),
            Triple(R.id.tvRecallP2Badge, R.id.tvRecallP2Cont, "p2"),
            Triple(R.id.tvRecallCaseBadge, R.id.tvRecallCaseCont, "case"),
            Triple(R.id.tvRecallUnseenBadge, R.id.tvRecallUnseenCont, "unseen")
        ).forEach { (badgeId, contId, key) ->
            val busy = tile == key
            bindStatusBadge(
                findViewById(badgeId),
                done = false,
                inProgress = busy,
                n = if (busy) left else 0
            )
            findViewById<TextView>(contId).visibility = if (busy) View.VISIBLE else View.GONE
        }
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
