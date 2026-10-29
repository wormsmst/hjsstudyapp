package com.example.adminmemo

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.Calendar

class QuestActivity : BaseActivity() {

    private var year = 0
    private var month1 = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quest)
        bindLandscapeSplit(R.id.layoutQuestSplit, stackPortrait = true)
        bindCoach(R.id.tvQuestCoach, "quest", CoachHints.QUEST)
        val cal = Calendar.getInstance()
        year = cal.get(Calendar.YEAR)
        month1 = cal.get(Calendar.MONTH) + 1
        findViewById<View>(R.id.btnQuestPrevMonth).setOnClickListener {
            month1--
            if (month1 < 1) {
                month1 = 12
                year--
            }
            bindAll()
        }
        findViewById<View>(R.id.btnQuestNextMonth).setOnClickListener {
            month1++
            if (month1 > 12) {
                month1 = 1
                year++
            }
            bindAll()
        }
        findViewById<View>(R.id.btnQuestHome).setOnClickListener {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        findViewById<View>(R.id.btnQuestGoRecall).setOnClickListener {
            startActivity(Intent(this, RecallPickActivity::class.java))
        }
        findViewById<View>(R.id.btnQuestUnseen).setOnClickListener {
            startActivity(
                Intent(this, RecallActivity::class.java)
                    .putExtra(EXTRA_SUBJECT, ALL_SUBJECTS_KEY)
                    .putExtra(EXTRA_RECALL_UNSEEN, true)
            )
        }
        findViewById<View>(R.id.btnQuestRetrain).setOnClickListener { startRetrain() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindLandscapeSplit(R.id.layoutQuestSplit, stackPortrait = true)
        findViewById<View>(R.id.layoutQuestRight).post { bindCalendar() }
    }

    override fun onStart() {
        super.onStart()
        DailyQuestStore.addDueCurveQuests(this)
        bindAll()
    }

    override fun onResume() {
        super.onResume()
        if (FirebaseSyncManager.isSignedIn()) {
            FirebaseSyncManager.syncProgressNow(this) { _, _ ->
                runOnUiThread { bindAll() }
            }
        }
    }

    private fun bindAll() {
        findViewById<TextView>(R.id.tvQuestMonth).text = "${year}년 ${month1}월"
        bindToday()
        bindBacklog()
        bindUnseen()
        findViewById<View>(R.id.layoutQuestRight).post { bindCalendar() }
    }

    private fun bindToday() {
        val empty = findViewById<LinearLayout>(R.id.layoutQuestEmpty)
        val list = findViewById<LinearLayout>(R.id.layoutQuestToday)
        list.removeAllViews()
        val quests = DailyQuestStore.todayQuests(this)
        if (!DailyQuestStore.recallDoneToday(this) && quests.isEmpty()) {
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            bindRetrainButton()
            return
        }
        empty.visibility = View.GONE
        list.visibility = View.VISIBLE
        if (quests.isEmpty()) {
            addHint(list, "오늘은 막힌 장이 없어 퀘스트가 없어요.")
            bindRetrainButton()
            return
        }
        quests.forEach { addQuestRow(list, it, editable = true) }
        bindRetrainButton()
    }

    private fun bindRetrainButton() {
        val btn = findViewById<View>(R.id.btnQuestRetrain)
        val ids = DailyQuestStore.retrainIds(this)
        val openWrite = DailyQuestStore.openQuests(this)
            .any { it.type == DailyQuestStore.TYPE_WRITE || it.type == DailyQuestStore.TYPE_BODY }
        btn.visibility = if (ids.isNotEmpty() && !openWrite) View.VISIBLE else View.GONE
    }

    private fun startRetrain() {
        val ids = DailyQuestStore.retrainIds(this)
        if (ids.isEmpty()) return
        startActivity(
            Intent(this, RecallActivity::class.java)
                .putStringArrayListExtra(EXTRA_RECALL_CARD_IDS, ArrayList(ids))
                .putExtra(EXTRA_RECALL_RETRAIN, true)
        )
    }

    private fun bindBacklog() {
        val box = findViewById<LinearLayout>(R.id.layoutQuestBacklog)
        box.removeAllViews()
        val items = DailyQuestStore.backlog(this)
        items.forEach { addQuestRow(box, it, editable = true) }
    }

    private fun bindUnseen() {
        val n = RecallStore.unseenThisMonth(this).size
        findViewById<TextView>(R.id.tvQuestUnseenTitle).text = "이번달 미인출 ${n}개"
        findViewById<View>(R.id.btnQuestUnseen).visibility = if (n == 0) View.GONE else View.VISIBLE
    }

    private fun bindCalendar() {
        val grid = findViewById<GridLayout>(R.id.gridQuestCal)
        grid.removeAllViews()
        val snaps = DailyQuestStore.monthSnaps(this, year, month1).associateBy { it.date }
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month1 - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = TodayTtsStore.todayKey()
        val cell = calendarCellSize()
        listOf("일", "월", "화", "수", "목", "금", "토").forEach { name ->
            grid.addView(weekdayCell(name, cell))
        }
        repeat(firstDow) { grid.addView(dayCell("", Color.TRANSPARENT, cell, null)) }
        for (d in 1..days) {
            val key = TodayTtsStore.canonKey("$year-$month1-$d")
            val snap = snaps[key] ?: snaps.entries.firstOrNull {
                TodayTtsStore.canonKey(it.key) == key
            }?.value
            val bg = when {
                snap == null -> ContextCompat.getColor(this, R.color.bg_card)
                snap.allDone() -> ContextCompat.getColor(this, R.color.quest_cal_done)
                snap.hasRecall() -> ContextCompat.getColor(this, R.color.quest_cal_recall)
                else -> ContextCompat.getColor(this, R.color.bg_card)
            }
            val selected = key == today
            grid.addView(dayCell(d.toString(), bg, cell) {
                showDay(key, snap)
            }.also { tv ->
                if (selected) tv.setTypeface(tv.typeface, Typeface.BOLD)
            })
        }
    }

    private fun showDay(key: String, snap: QuestDaySnap?) {
        val box = findViewById<LinearLayout>(R.id.layoutQuestDay)
        box.removeAllViews()
        val label = TodayTtsStore.displayDate(key)
        addHint(box, label, bold = true)
        if (snap == null || !snap.hasRecall()) {
            addHint(box, "이 날은 인출 기록이 없어요.")
            return
        }
        val saved = snap.gradedCount()
        if (saved > 0) addHint(box, "저장한 인출 ${saved}장")
        if (snap.quests.isEmpty()) {
            addHint(
                box,
                if (snap.recallDone) "인출은 했지만 퀘스트가 없었어요."
                else "세션을 끝까지 마치기 전이라 퀘스트는 없어요."
            )
            return
        }
        snap.quests.forEach { addQuestRow(box, it, editable = key == TodayTtsStore.todayKey()) }
    }

    private fun addQuestRow(parent: LinearLayout, q: DailyQuest, editable: Boolean) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        row.layoutParams = lp
        val cb = CheckBox(this)
        val box = dp(40)
        cb.layoutParams = LinearLayout.LayoutParams(box, box)
        cb.minWidth = box
        cb.minHeight = box
        cb.scaleX = 1.35f
        cb.scaleY = 1.35f
        cb.text = ""
        cb.isChecked = q.done
        cb.isEnabled = editable
        if (editable) {
            cb.setOnClickListener {
                DailyQuestStore.setDone(this, q.id, cb.isChecked)
                bindAll()
            }
        }
        row.addView(cb)
        val title = TextView(this)
        val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        titleLp.marginStart = dp(4)
        title.layoutParams = titleLp
        title.text = q.title
        title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        title.textSize = 15f
        row.addView(title)
        if (q.type == DailyQuestStore.TYPE_WRITE || q.type == DailyQuestStore.TYPE_BODY) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = "인출하기"
            btn.textSize = 12f
            btn.minimumWidth = 0
            btn.minWidth = 0
            btn.insetTop = 0
            btn.insetBottom = 0
            val btnLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            btn.layoutParams = btnLp
            btn.setOnClickListener { openQuest(q) }
            row.addView(btn)
        }
        parent.addView(row)
        if (editable) {
            row.setOnClickListener { openQuest(q) }
        }
    }

    private fun openQuest(q: DailyQuest) {
        if (q.type == DailyQuestStore.TYPE_LISTEN) {
            startActivity(Intent(this, TodayTtsActivity::class.java))
            return
        }
        if (q.cardId.isBlank()) return
        startActivity(
            Intent(this, RecallActivity::class.java)
                .putStringArrayListExtra(EXTRA_RECALL_CARD_IDS, arrayListOf(q.cardId))
                .putExtra(EXTRA_RECALL_BODY, q.type == DailyQuestStore.TYPE_BODY)
                .putExtra(EXTRA_RECALL_FROM_QUEST, true)
        )
    }

    private fun addHint(parent: LinearLayout, text: String, bold: Boolean = false) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
        tv.textSize = 13f
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        tv.layoutParams = lp
        parent.addView(tv)
    }

    private fun weekdayCell(name: String, size: Int): TextView {
        val tv = TextView(this)
        tv.text = name
        tv.gravity = Gravity.CENTER
        tv.textSize = 12f
        tv.includeFontPadding = false
        tv.maxLines = 1
        tv.setTextColor(
            ContextCompat.getColor(
                this,
                if (name == "토" || name == "일") R.color.text_sub else R.color.text_main
            )
        )
        val lp = GridLayout.LayoutParams()
        lp.width = size
        lp.height = (size * 0.55f).toInt().coerceAtLeast(dp(22))
        lp.setMargins(dp(1), dp(1), dp(1), dp(4))
        tv.layoutParams = lp
        return tv
    }

    private fun dayCell(text: String, bg: Int, size: Int, click: (() -> Unit)?): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.textSize = 11f
        tv.includeFontPadding = false
        tv.maxLines = 1
        tv.minHeight = size
        tv.maxHeight = size
        tv.minWidth = size
        tv.maxWidth = size
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        tv.setBackgroundColor(bg)
        val lp = GridLayout.LayoutParams()
        lp.width = size
        lp.height = size
        lp.setMargins(dp(1), dp(1), dp(1), dp(1))
        tv.layoutParams = lp
        if (click != null) tv.setOnClickListener { click() }
        return tv
    }

    private fun calendarCellSize(): Int {
        val pane = findViewById<View>(R.id.layoutQuestRight)
        val w = when {
            pane.width > 0 -> pane.width
            isLandscape() -> resources.displayMetrics.widthPixels / 2
            else -> resources.displayMetrics.widthPixels
        }
        return ((w - dp(24)) / 7).coerceAtLeast(dp(32))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
