package com.example.adminmemo

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import kotlin.math.abs

class HomeActivity : BaseActivity() {

    private val tipHandler = Handler(Looper.getMainLooper())
    private val tipTick = Runnable { refreshHomeHeader(); scheduleTipRefresh() }
    private val tabKeys = listOf("today", "review", "tools")
    private var tabIndex = 0
    private var tabAnimating = false
    private lateinit var gestureDetector: GestureDetector
    private var swipeLocked = false
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindLandscapeSplit(R.id.layoutHomeSplit)
        gestureDetector = GestureDetector(this, HomeSwipeListener())

        findViewById<CardView>(R.id.cardDday).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<CardView>(R.id.tileRecall).setOnClickListener {
            startActivity(Intent(this, RecallPickActivity::class.java))
        }
        findViewById<CardView>(R.id.tileQuests).setOnClickListener {
            startActivity(Intent(this, QuestActivity::class.java))
        }
        findViewById<CardView>(R.id.tileOutlineFade).setOnClickListener {
            startActivity(Intent(this, OutlineFadeActivity::class.java))
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
        findViewById<View>(R.id.rowHomeQuestSummary).setOnClickListener {
            startActivity(Intent(this, QuestActivity::class.java))
        }
        findViewById<TextView>(R.id.tabHomeToday).setOnClickListener { showHomeTab("today", animate = true) }
        findViewById<TextView>(R.id.tabHomeReview).setOnClickListener { showHomeTab("review", animate = true) }
        findViewById<TextView>(R.id.tabHomeSettings).setOnClickListener { showHomeTab("tools", animate = true) }
        showHomeTab(AppPrefs.getHomeTab(this), animate = false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeLocked = false
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeLocked) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.15f) {
                        swipeLocked = true
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
            }
        }
        val swiped = gestureDetector.onTouchEvent(ev)
        if (swipeLocked || swiped) {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                swipeLocked = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
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

    private fun showHomeTab(tab: String, animate: Boolean) {
        val key = when (tab) {
            "review" -> "review"
            "tools" -> "tools"
            else -> "today"
        }
        val next = tabKeys.indexOf(key).coerceAtLeast(0)
        val prev = tabIndex
        if (next == prev && findViewById<View>(pageId(key)).visibility == View.VISIBLE) {
            bindTabPills(key)
            return
        }
        AppPrefs.setHomeTab(this, key)
        if (!animate || prev == next) {
            applyTabPages(key)
            tabIndex = next
            bindTabPills(key)
            return
        }
        animateTabChange(prev, next)
    }

    private fun applyTabPages(key: String) {
        findViewById<View>(R.id.layoutPageToday).apply {
            visibility = if (key == "today") View.VISIBLE else View.GONE
            translationX = 0f
            alpha = 1f
        }
        findViewById<View>(R.id.layoutTilesReview).apply {
            visibility = if (key == "review") View.VISIBLE else View.GONE
            translationX = 0f
            alpha = 1f
        }
        findViewById<View>(R.id.layoutTilesTools).apply {
            visibility = if (key == "tools") View.VISIBLE else View.GONE
            translationX = 0f
            alpha = 1f
        }
    }

    private fun animateTabChange(from: Int, to: Int) {
        if (tabAnimating) return
        val outgoing = findViewById<View>(pageId(tabKeys[from]))
        val incoming = findViewById<View>(pageId(tabKeys[to]))
        val width = findViewById<View>(R.id.homePages).width.let {
            if (it > 0) it.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        }
        val dir = if (to > from) 1f else -1f
        tabAnimating = true
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.visibility = View.VISIBLE
        incoming.translationX = dir * width
        incoming.alpha = 0.88f
        val ease = DecelerateInterpolator()
        outgoing.animate()
            .translationX(-dir * width * 0.35f)
            .alpha(0.35f)
            .setDuration(240)
            .setInterpolator(ease)
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationX = 0f
                outgoing.alpha = 1f
            }
            .start()
        incoming.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(240)
            .setInterpolator(ease)
            .withEndAction {
                tabAnimating = false
                tabIndex = to
                AppPrefs.setHomeTab(this, tabKeys[to])
                applyTabPages(tabKeys[to])
            }
            .start()
        bindTabPills(tabKeys[to])
    }

    private fun pageId(key: String): Int = when (key) {
        "review" -> R.id.layoutTilesReview
        "tools" -> R.id.layoutTilesTools
        else -> R.id.layoutPageToday
    }

    private fun bindTabPills(key: String) {
        styleHomeTab(findViewById(R.id.tabHomeToday), key == "today")
        styleHomeTab(findViewById(R.id.tabHomeReview), key == "review")
        styleHomeTab(findViewById(R.id.tabHomeSettings), key == "tools")
    }

    private fun styleHomeTab(tab: TextView, on: Boolean) {
        tab.setBackgroundResource(if (on) R.drawable.bg_home_tab_on else R.drawable.bg_home_tab_off)
        tab.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.text_on_header else R.color.text_sub)
        )
        tab.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun bindHomeQuests() {
        val open = DailyQuestStore.openQuests(this)
        val backlog = DailyQuestStore.backlog(this).size
        val n = open.size + backlog
        findViewById<TextView>(R.id.tvHomeQuestSummary).text = when {
            !DailyQuestStore.recallDoneToday(this) -> "오늘의 퀘스트 · 인출하면 열려요"
            n == 0 -> "오늘의 퀘스트 완료"
            else -> "오늘의 퀘스트 ${n}개"
        }
        val questLabel = findViewById<TextView>(R.id.tvTileQuestLabel)
        questLabel.text = if (n > 0) "퀘스트\n${n}개" else "퀘스트"
    }

    private fun goToSubjectSelect(purpose: String) {
        val intent = Intent(this, SubjectSelectActivity::class.java)
        intent.putExtra(EXTRA_PURPOSE, purpose)
        startActivity(intent)
    }

    private inner class HomeSwipeListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null || tabAnimating) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            val min = 72f * resources.displayMetrics.density
            if (abs(dx) < min || abs(dx) < abs(dy) * 1.15f) return false
            if (abs(velocityX) < 380) return false
            val next = if (dx < 0) tabIndex + 1 else tabIndex - 1
            if (next !in tabKeys.indices) return false
            showHomeTab(tabKeys[next], animate = true)
            return true
        }
    }
}
