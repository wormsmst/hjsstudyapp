package com.example.adminmemo

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

class HomeActivity : BaseActivity() {

    private val tipHandler = Handler(Looper.getMainLooper())
    private val tipTick = Runnable { refreshHomeHeader(); scheduleTipRefresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindLandscapeSplit(R.id.layoutHomeSplit)

        findViewById<CardView>(R.id.cardDday).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<CardView>(R.id.tileRecall).setOnClickListener {
            startActivity(Intent(this, RecallPickActivity::class.java))
        }
        findViewById<CardView>(R.id.tileQuests).setOnClickListener {
            startActivity(Intent(this, QuestActivity::class.java))
        }
        findViewById<CardView>(R.id.tileStudy).setOnClickListener {
            goToSubjectSelect(PURPOSE_STUDY)
        }
        findViewById<CardView>(R.id.tileExam).setOnClickListener {
            startActivity(Intent(this, ExamPickActivity::class.java))
        }
        findViewById<CardView>(R.id.tileWrongNotes).setOnClickListener {
            startActivity(Intent(this, WrongNotesActivity::class.java))
        }
        findViewById<CardView>(R.id.tileProgress).setOnClickListener {
            startActivity(Intent(this, ProgressActivity::class.java))
        }
        findViewById<CardView>(R.id.tileTodayTts).setOnClickListener {
            startActivity(Intent(this, TodayTtsActivity::class.java))
        }
        findViewById<CardView>(R.id.tileManage).setOnClickListener {
            startActivity(Intent(this, ContentManageHubActivity::class.java))
        }
        findViewById<CardView>(R.id.tileSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.tvHomeCoach).text = CoachHints.HOME
        findViewById<TextView>(R.id.tabHomeToday).setOnClickListener { showHomeTab("today") }
        findViewById<TextView>(R.id.tabHomeReview).setOnClickListener { showHomeTab("review") }
        findViewById<TextView>(R.id.tabHomeSettings).setOnClickListener { showHomeTab("tools") }
        showHomeTab(AppPrefs.getHomeTab(this))
    }

    override fun onStart() {
        super.onStart()
        refreshHomeHeader()
        scheduleTipRefresh()
        if (FirebaseSyncManager.isSignedIn()) {
            FirebaseSyncManager.syncProgressNow(this) { _, _ ->
                runOnUiThread { refreshHomeHeader() }
            }
        }
    }

    override fun onStop() {
        tipHandler.removeCallbacks(tipTick)
        super.onStop()
    }

    private fun scheduleTipRefresh() {
        tipHandler.removeCallbacks(tipTick)
        tipHandler.postDelayed(tipTick, StudyTips.millisUntilNext())
    }

    private fun refreshHomeHeader() {
        findViewById<TextView>(R.id.tvHomeTip).text = StudyTips.current()
        val dday = DdayCalculator.homeDisplay(this)
        findViewById<TextView>(R.id.tvDdayMain).text = dday.main
        val tvSecond = findViewById<TextView>(R.id.tvDdaySecond)
        if (dday.second.isNullOrBlank()) {
            tvSecond.visibility = View.GONE
        } else {
            tvSecond.visibility = View.VISIBLE
            tvSecond.text = dday.second
        }
        findViewById<TextView>(R.id.tvDdaySub).text = dday.sub
        val due = RecallStore.dueCount(this)
        val graded = DailyQuestStore.gradedToday(this)
        findViewById<TextView>(R.id.tvTileRecallLabel).text =
            if (due > 0) "인출학습\n오늘 ${due}장" else "인출학습"
        val wrongN = WrongNoteStore.all(this).size
        findViewById<TextView>(R.id.tvTileWrongLabel).text =
            if (wrongN > 0) "오답노트\n${wrongN}건" else "오답노트"
        val goal = StudyProgressStore.dailyGoal(this)
        findViewById<TextView>(R.id.tvTileProgressLabel).text = "학습진도\n$graded/$goal"
        DailyQuestStore.addDueCurveQuests(this)
        bindHomeQuests()
        val account = findViewById<TextView>(R.id.tvHomeAccount)
        val user = FirebaseSyncManager.currentUser
        val email = user?.email?.trim().orEmpty()
        if (email.isBlank()) {
            account.visibility = View.GONE
        } else {
            account.visibility = View.VISIBLE
            account.text = email
        }
    }

    private fun showHomeTab(tab: String) {
        val key = when (tab) {
            "review" -> "review"
            "tools" -> "tools"
            else -> "today"
        }
        AppPrefs.setHomeTab(this, key)
        findViewById<View>(R.id.layoutTilesToday).visibility =
            if (key == "today") View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutTilesReview).visibility =
            if (key == "review") View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutTilesTools).visibility =
            if (key == "tools") View.VISIBLE else View.GONE
        findViewById<CardView>(R.id.cardHomeQuests).visibility =
            if (key == "today") View.VISIBLE else View.GONE
        styleHomeTab(findViewById(R.id.tabHomeToday), key == "today")
        styleHomeTab(findViewById(R.id.tabHomeReview), key == "review")
        styleHomeTab(findViewById(R.id.tabHomeSettings), key == "tools")
    }

    private fun styleHomeTab(tab: TextView, on: Boolean) {
        tab.setTextColor(ContextCompat.getColor(this, if (on) R.color.primary else R.color.text_sub))
        tab.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun bindHomeQuests() {
        val card = findViewById<CardView>(R.id.cardHomeQuests)
        if (AppPrefs.getHomeTab(this) != "today") {
            card.visibility = View.GONE
        } else {
            card.visibility = View.VISIBLE
        }
        val open = DailyQuestStore.openQuests(this)
        val backlog = DailyQuestStore.backlog(this).size
        findViewById<TextView>(R.id.tvHomeQuests).text = when {
            !DailyQuestStore.recallDoneToday(this) -> "오늘 인출을 먼저 하면 퀘스트가 생깁니다."
            open.isEmpty() && backlog == 0 -> "오늘의 퀘스트를 모두 끝냈어요."
            else -> buildString {
                open.forEach { q ->
                    val kind = DailyQuestStore.kindLabel(q)
                    val hint = if (q.hint.isBlank()) "" else " · ${q.hint}"
                    append("· ${q.title}  ${q.progress}/${q.target}  ($kind)$hint\n")
                }
                if (backlog > 0) append("밀린 퀘스트 ${backlog}개")
            }.trim()
        }
        val questLabel = findViewById<TextView>(R.id.tvTileQuestLabel)
        val n = open.size + backlog
        questLabel.text = if (n > 0) "퀘스트\n${n}개" else "퀘스트"
        card.setOnClickListener {
            startActivity(Intent(this, QuestActivity::class.java))
        }
    }

    private fun goToSubjectSelect(purpose: String) {
        val intent = Intent(this, SubjectSelectActivity::class.java)
        intent.putExtra(EXTRA_PURPOSE, purpose)
        startActivity(intent)
    }
}
