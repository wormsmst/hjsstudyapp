package com.example.adminmemo

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

class HomeActivity : BaseActivity() {

    private val tipHandler = Handler(Looper.getMainLooper())
    private val tipTick = Runnable { refreshHomeHeader(); scheduleTipRefresh() }
    private val tabKeys = listOf("today", "review", "tools")
    private var tabIndex = 0
    private val tabEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private var pagerWidth = 0
    private lateinit var gestureDetector: GestureDetector
    private var swipeLocked = false
    private var tileDragging = false
    private var showExamDate = false
    private var downX = 0f
    private var downY = 0f
    private val onCloudProgress: () -> Unit = { refreshHomeHeader() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        gestureDetector = GestureDetector(this, HomeSwipeListener())

        findViewById<CardView>(R.id.cardDday).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.tvDdayMain).setOnClickListener {
            val d2 = AppPrefs.getExam2DateMillis(this)
            if (DdayCalculator.isUpcoming(d2)) {
                showExamDate = !showExamDate
                refreshHomeHeader()
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        findViewById<CardView>(R.id.tileRecall).setOnClickListener {
            startActivity(Intent(this, RecallPickActivity::class.java))
        }
        findViewById<CardView>(R.id.tileQuests).setOnClickListener {
            startActivity(Intent(this, QuestActivity::class.java))
        }
        findViewById<CardView>(R.id.tileOutlineFade).setOnClickListener {
            goToSubjectSelect(PURPOSE_OUTLINE)
        }
        findViewById<CardView>(R.id.tileExtraStudy).setOnClickListener {
            startActivity(Intent(this, ExtraStudyActivity::class.java))
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
        findViewById<CardView>(R.id.tileStudyLoad).setOnClickListener {
            startActivity(Intent(this, StudyLoadActivity::class.java))
        }
        findViewById<View>(R.id.rowHomeQuestSummary).setOnClickListener {
            startActivity(Intent(this, QuestActivity::class.java))
        }
        findViewById<TextView>(R.id.tabHomeToday).setOnClickListener { showHomeTab("today", animate = true) }
        findViewById<TextView>(R.id.tabHomeReview).setOnClickListener { showHomeTab("review", animate = true) }
        findViewById<TextView>(R.id.tabHomeSettings).setOnClickListener { showHomeTab("tools", animate = true) }
        wireTileReorder()
        val host = findViewById<View>(R.id.homePages)
        host.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val w = right - left
            if (w > 0 && w != pagerWidth) layoutHomePager(w)
        }
        showHomeTab(AppPrefs.getHomeTab(this), animate = false)
        applyDdayQuestRow()
        host.post { layoutHomePager(host.width) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyHomeTileHeights()
        applyDdayQuestRow()
        pagerWidth = 0
        val host = findViewById<View>(R.id.homePages)
        host.post { layoutHomePager(host.width) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) bumpKeepScreen()
        if (tileDragging) {
            return super.dispatchTouchEvent(ev)
        }
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
        scheduleTipRefresh()
        window.decorView.post {
            if (isDestroyed) return@post
            refreshHomeHeader()
        }
    }

    override fun onResume() {
        super.onResume()
        FirebaseSyncManager.addUiListener(onCloudProgress)
        tipHandler.postDelayed({
            if (isDestroyed) return@postDelayed
            DailyQuestStore.addDueCurveQuests(this)
            refreshHomeHeader()
        }, 120)
        if (FirebaseSyncManager.isSignedIn()) {
            FirebaseSyncManager.syncProgressNow(this) { _, _ ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    DailyQuestStore.addDueCurveQuests(this)
                    refreshHomeHeader()
                }
            }
        }
    }

    override fun onPause() {
        FirebaseSyncManager.removeUiListener(onCloudProgress)
        super.onPause()
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
        val pulse = SkillPulseStore.homeLine(this)
        findViewById<TextView>(R.id.tvHomeTip).text = pulse.ifBlank { StudyTips.current() }
        val dday = DdayCalculator.homeDisplay(this)
        val d2 = AppPrefs.getExam2DateMillis(this)
        val upcoming = DdayCalculator.isUpcoming(d2)
        if (!upcoming) showExamDate = false
        findViewById<TextView>(R.id.tvDdayMain).text =
            if (showExamDate && upcoming) DateFormatters.examPeekLine(d2) else dday.main
        val tvSecond = findViewById<TextView>(R.id.tvDdaySecond)
        if (dday.second.isNullOrBlank()) {
            tvSecond.visibility = View.GONE
        } else {
            tvSecond.visibility = View.VISIBLE
            tvSecond.text = dday.second
        }
        val tvSub = findViewById<TextView>(R.id.tvDdaySub)
        if (upcoming) {
            tvSub.visibility = View.GONE
        } else {
            tvSub.visibility = View.VISIBLE
            tvSub.text = dday.sub
        }
        bindHomeStats()
        val graded = DailyQuestStore.gradedToday(this)
        val recallDone = DailyQuestStore.recallDoneToday(this)
        val recallSnap = RecallSessionStore.load(this)
        val recallOpen = RecallSessionStore.openToday(this)
        val remaining = if (recallOpen && recallSnap != null) {
            (recallSnap.queueIds.size - recallSnap.index).coerceAtLeast(0)
        } else {
            0
        }
        val recallBusy = !recallDone && (graded >= 1 || recallOpen)
        findViewById<TextView>(R.id.tvTileRecallLabel).text = "인출학습"
        bindStatusBadge(R.id.tvTileRecallBadge, done = recallDone, inProgress = recallBusy, n = remaining)
        val boostN = DailyQuestStore.openBoostCount(this)
        findViewById<TextView>(R.id.tvTileExtraLabel).text = "보충학습"
        bindTileBadge(R.id.tvTileExtraBadge, boostN)
        findViewById<TextView>(R.id.tvTileWrongLabel).text = "오답노트"
        findViewById<TextView>(R.id.tvTileProgressLabel).text = "학습진도"
        bindHomeQuests()
    }

    private fun showHomeTab(tab: String, animate: Boolean) {
        val key = when (tab) {
            "review" -> "review"
            "tools" -> "tools"
            else -> "today"
        }
        val next = tabKeys.indexOf(key).coerceAtLeast(0)
        if (next == tabIndex && pagerWidth > 0) {
            bindTabPills(key)
            return
        }
        tabIndex = next
        AppPrefs.setHomeTab(this, key)
        bindTabPills(key)
        slideHomePager(animate)
    }

    private fun tabPages(): List<View> = listOf(
        findViewById(R.id.layoutPageToday),
        findViewById(R.id.layoutTilesReview),
        findViewById(R.id.layoutTilesTools)
    )

    private fun layoutHomePager(width: Int) {
        if (width <= 0) return
        pagerWidth = width
        val pages = tabPages()
        pages.forEach { page ->
            page.visibility = View.VISIBLE
            page.alpha = 1f
            page.translationX = 0f
            page.minimumWidth = width
            val lp = page.layoutParams
            if (lp.width != width) {
                lp.width = width
                page.layoutParams = lp
            }
        }
        val strip = findViewById<View>(R.id.homePagesStrip)
        val stripW = width * pages.size
        strip.minimumWidth = stripW
        val slp = strip.layoutParams
        if (slp.width != stripW) {
            slp.width = stripW
            strip.layoutParams = slp
        }
        val host = findViewById<View>(R.id.homePages)
        val maxH = pages.maxOf { measurePageHeight(it, width) }
        if (host.layoutParams.height != maxH) {
            host.layoutParams.height = maxH
            host.requestLayout()
        }
        slideHomePager(animate = false)
    }

    private fun measurePageHeight(page: View, width: Int): Int {
        val wSpec = View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        page.measure(wSpec, hSpec)
        return page.measuredHeight.coerceAtLeast(1)
    }

    private fun slideHomePager(animate: Boolean) {
        val strip = findViewById<View>(R.id.homePagesStrip)
        val w = pagerWidth.let {
            if (it > 0) it else findViewById<View>(R.id.homePages).width
        }
        if (w <= 0) return
        val target = -tabIndex * w.toFloat()
        strip.animate().cancel()
        if (!animate) {
            strip.translationX = target
            return
        }
        strip.animate()
            .translationX(target)
            .setDuration(240)
            .setInterpolator(tabEase)
            .withLayer()
            .start()
    }

    private val tileTabGrids = listOf(
        "today" to R.id.layoutTilesToday,
        "review" to R.id.layoutTilesReview,
        "tools" to R.id.layoutTilesTools
    )

    private fun defaultTileNames(tab: String): List<String> = when (tab) {
        "today" -> listOf(
            "tileTodayTts", "tileStudy", "tileRecall",
            "tileQuests", "tileOutlineFade", "tileExtraStudy"
        )
        "review" -> listOf("tileWrongNotes", "tileExam", "tileProgress")
        else -> listOf("tileManage", "tileSettings", "tileStudyLoad")
    }

    private fun tileGridLp(): GridLayout.LayoutParams {
        val d = resources.displayMetrics.density
        return GridLayout.LayoutParams().apply {
            width = 0
            height = resources.getDimensionPixelSize(R.dimen.home_screen_tile_min)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            setGravity(Gravity.FILL)
            setMargins(
                (5 * d).roundToInt(),
                (8 * d).roundToInt(),
                (5 * d).roundToInt(),
                (8 * d).roundToInt()
            )
        }
    }

    private fun applySavedTileOrder(grid: GridLayout, tab: String) {
        val defaults = defaultTileNames(tab)
        val saved = AppPrefs.getHomeTileOrder(this, tab)
        val names = saved.filter { it in defaults } + defaults.filter { it !in saved }
        val kids = (0 until grid.childCount).map { grid.getChildAt(it) }
        val byName = kids.associateBy { resources.getResourceEntryName(it.id) }
        val ordered = names.mapNotNull { byName[it] }
        if (ordered.size != kids.size || ordered.zip(kids).any { it.first !== it.second }) {
            placeTiles(grid, ordered)
        }
    }

    private fun placeTiles(grid: GridLayout, tiles: List<View>) {
        grid.removeAllViews()
        tiles.forEach { tile -> grid.addView(tile, tileGridLp()) }
    }

    private fun saveTileOrder(grid: GridLayout, tab: String) {
        val names = (0 until grid.childCount).map {
            resources.getResourceEntryName(grid.getChildAt(it).id)
        }
        AppPrefs.setHomeTileOrder(this, tab, names)
    }

    private fun swapTiles(grid: GridLayout, a: View, b: View) {
        val kids = (0 until grid.childCount).map { grid.getChildAt(it) }.toMutableList()
        val ia = kids.indexOf(a)
        val ib = kids.indexOf(b)
        if (ia < 0 || ib < 0 || ia == ib) return
        kids[ia] = b
        kids[ib] = a
        placeTiles(grid, kids)
    }

    private fun wireTileReorder() {
        tileTabGrids.forEach { (tab, id) ->
            val grid = findViewById<GridLayout>(id)
            applySavedTileOrder(grid, tab)
            for (i in 0 until grid.childCount) {
                val tile = grid.getChildAt(i)
                tile.setOnLongClickListener { v ->
                    val name = resources.getResourceEntryName(v.id)
                    val data = ClipData.newPlainText("home_tile", name)
                    tileDragging = v.startDragAndDrop(data, View.DragShadowBuilder(v), v, 0)
                    true
                }
                tile.setOnDragListener { v, e ->
                    when (e.action) {
                        DragEvent.ACTION_DRAG_STARTED ->
                            e.clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                        DragEvent.ACTION_DRAG_ENTERED -> {
                            if (v !== e.localState) {
                                v.animate().scaleX(0.96f).scaleY(0.96f).duration = 80
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_EXITED -> {
                            v.animate().scaleX(1f).scaleY(1f).duration = 80
                            true
                        }
                        DragEvent.ACTION_DROP -> {
                            val src = e.localState as? View ?: return@setOnDragListener false
                            if (src.parent !== grid || v.parent !== grid || src === v) {
                                return@setOnDragListener true
                            }
                            swapTiles(grid, src, v)
                            saveTileOrder(grid, tab)
                            applyHomeTileHeights()
                            val host = findViewById<View>(R.id.homePages)
                            if (host.width > 0) layoutHomePager(host.width)
                            true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> {
                            v.animate().scaleX(1f).scaleY(1f).duration = 80
                            tileDragging = false
                            true
                        }
                        else -> true
                    }
                }
            }
        }
    }

    private fun applyHomeTileHeights() {
        val h = resources.getDimensionPixelSize(R.dimen.home_screen_tile_min)
        val ids = intArrayOf(
            R.id.tileTodayTts, R.id.tileStudy, R.id.tileRecall, R.id.tileQuests,
            R.id.tileOutlineFade, R.id.tileExtraStudy, R.id.tileWrongNotes, R.id.tileExam,
            R.id.tileProgress, R.id.tileManage, R.id.tileSettings, R.id.tileStudyLoad
        )
        ids.forEach { id ->
            val v = findViewById<View>(id)
            val lp = v.layoutParams
            if (lp.height != h) {
                lp.height = h
                v.layoutParams = lp
            }
        }
        val inner = findViewById<View>(R.id.layoutDdayInner)
        val padV = resources.getDimensionPixelSize(R.dimen.home_dday_padding_v)
        if (inner.paddingTop != padV || inner.paddingBottom != padV) {
            inner.setPadding(inner.paddingLeft, padV, inner.paddingRight, padV)
        }
        val dday = findViewById<View>(R.id.cardDday)
        val mlp = dday.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val top = resources.getDimensionPixelSize(R.dimen.home_dday_margin_top)
        if (mlp.topMargin != top) {
            mlp.topMargin = top
            dday.layoutParams = mlp
        }
    }

    private fun applyDdayQuestRow() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val row = findViewById<LinearLayout>(R.id.rowDdayQuest)
        row.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        row.gravity = if (land) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        val gap = (12 * resources.displayMetrics.density).toInt()
        val dday = findViewById<View>(R.id.tvDdayMain)
        val dlp = dday.layoutParams as LinearLayout.LayoutParams
        dlp.width = if (land) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        dlp.weight = if (land) 1f else 0f
        dlp.marginEnd = if (land) gap else 0
        dday.layoutParams = dlp
        val quest = findViewById<View>(R.id.rowHomeQuestSummary)
        val qlp = quest.layoutParams as LinearLayout.LayoutParams
        qlp.width = if (land) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        qlp.weight = 0f
        qlp.topMargin = if (land) 0 else (6 * resources.displayMetrics.density).toInt()
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        qlp.marginEnd = when {
            land && tablet -> (116 * resources.displayMetrics.density).toInt()
            land -> (16 * resources.displayMetrics.density).toInt()
            else -> 0
        }
        quest.minimumWidth = 0
        quest.layoutParams = qlp
        val summary = findViewById<TextView>(R.id.tvHomeQuestSummary)
        val slp = summary.layoutParams as LinearLayout.LayoutParams
        slp.width = if (land) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        slp.weight = if (land) 0f else 1f
        summary.layoutParams = slp
        val ddayTv = findViewById<TextView>(R.id.tvDdayMain)
        ddayTv.maxLines = 1
        ddayTv.ellipsize = android.text.TextUtils.TruncateAt.END
        summary.maxLines = if (land) 1 else 2
        val screen = resources.displayMetrics.widthPixels
        summary.maxWidth = if (land) (screen * 0.42f).toInt() else Integer.MAX_VALUE
        summary.ellipsize = android.text.TextUtils.TruncateAt.END
        summary.gravity = Gravity.CENTER
        val padH = ((if (land) 22 else 10) * resources.displayMetrics.density).toInt()
        quest.setPadding(padH, quest.paddingTop, padH, quest.paddingBottom)
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
        val questSummary = findViewById<TextView>(R.id.tvHomeQuestSummary)
        val waitRecall = !DailyQuestStore.recallDoneToday(this) &&
            DailyQuestStore.todayQuests(this).isEmpty() &&
            DailyQuestStore.gradedToday(this) <= 0
        questSummary.text = when {
            waitRecall -> "오늘의 퀘스트 · 먼저 인출학습을 하세요."
            n == 0 -> "오늘의 퀘스트 완료"
            else -> "오늘의 퀘스트 ${n}개"
        }
        questSummary.textSize = if (waitRecall) 15f else 20f
        val questDone = DailyQuestStore.questsCompleteToday(this)
        val questRow = findViewById<View>(R.id.rowHomeQuestSummary)
        if (questDone) {
            questRow.setBackgroundResource(R.drawable.bg_home_quest_chip_done)
            questSummary.setTextColor(0xFF5A3E08.toInt())
        } else {
            questRow.setBackgroundResource(R.drawable.bg_home_quest_chip)
            questSummary.setTextColor(0xFF3A3D42.toInt())
        }
        val questBusy = !questDone && !waitRecall &&
            DailyQuestStore.todayQuests(this).any { it.done || it.progress > 0 }
        findViewById<TextView>(R.id.tvTileQuestLabel).text = "퀘스트"
        bindStatusBadge(
            R.id.tvTileQuestBadge,
            done = questDone,
            inProgress = questBusy,
            n = n
        )
        applyDdayQuestRow()
    }

    private fun bindTileBadge(id: Int, n: Int) {
        bindStatusBadge(id, done = false, inProgress = false, n = n)
    }

    private fun bindStatusBadge(id: Int, done: Boolean, inProgress: Boolean, n: Int) {
        bindStatusBadge(findViewById(id), done, inProgress, n)
    }

    private fun bindHomeStats() {
        val snap = StudyProgressStore.mastery(this)
        val week = SkillPulseStore.weekPctDelta(this)
        bindHomeStatCell(
            R.id.cellHomeStatAchieve,
            R.id.tvHomeStatAchieveValue,
            AppPrefs.showAchievePct(this),
            "${snap.fillPct}%"
        )
        bindPctDelta(R.id.tvHomeStatAchieveValue, R.id.tvHomeStatAchieveDelta, week.achieve, "%")
        bindHomeStatCell(
            R.id.cellHomeStatStable,
            R.id.tvHomeStatStableValue,
            AppPrefs.showStablePct(this),
            "${snap.stablePct}%"
        )
        bindPctDelta(R.id.tvHomeStatStableValue, R.id.tvHomeStatStableDelta, week.stable)
        val newN = StudyWeekStore.last7NewCount(this)
        bindHomeStatCell(
            R.id.cellHomeStatNew,
            R.id.tvHomeStatNewValue,
            true,
            "${newN}장"
        )
        findViewById<TextView>(R.id.tvHomeStatNewValue).setTextColor(
            ContextCompat.getColor(
                this,
                if (newN > 0) R.color.stat_delta_up else R.color.text_on_header
            )
        )
        val streak = StudyWeekStore.studyStreak(this)
        bindHomeStatCell(
            R.id.cellHomeStatStreak,
            R.id.tvHomeStatStreakValue,
            true,
            "${streak}일"
        )
        findViewById<TextView>(R.id.tvHomeStatStreakValue).setTextColor(
            ContextCompat.getColor(
                this,
                if (streak > 0) R.color.stat_delta_up else R.color.text_on_header
            )
        )
    }

    private fun bindHomeStatCell(cellId: Int, valueId: Int, visible: Boolean, value: String) {
        val cell = findViewById<View>(cellId)
        if (!visible) {
            cell.visibility = View.GONE
            return
        }
        cell.visibility = View.VISIBLE
        findViewById<TextView>(valueId).text = value
    }

    private fun bindPctDelta(valueId: Int, deltaId: Int, delta: Int?, suffix: String = "") {
        val tv = findViewById<TextView>(deltaId)
        val value = findViewById<TextView>(valueId)
        if (delta == null || delta == 0) {
            tv.visibility = View.GONE
            tv.translationY = 0f
            return
        }
        tv.visibility = View.VISIBLE
        tv.textSize = 15f
        tv.text = if (delta > 0) "↑$delta$suffix" else "↓${-delta}$suffix"
        tv.setTextColor(
            ContextCompat.getColor(
                this,
                if (delta > 0) R.color.stat_delta_up else R.color.stat_delta_down
            )
        )
        alignBaselines(value, tv)
    }

    private fun alignBaselines(anchor: TextView, target: TextView) {
        target.translationY = 0f
        target.post {
            val a = anchor.baseline
            val b = target.baseline
            if (a < 0 || b < 0) return@post
            val aParent = anchor.parent as View
            val bParent = target.parent as View
            val aY = aParent.top + anchor.top + a
            val bY = bParent.top + target.top + b
            target.translationY = (aY - bY).toFloat()
        }
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
            if (e1 == null) return false
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