package com.example.adminmemo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

class TodayTtsActivity : BaseActivity() {

    private var selectedDate = TodayTtsStore.todayKey()
    private val ttsListener: (StudyTtsState) -> Unit = { refreshNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_today_tts)
        bindTodaySplit()

        findViewById<TextView>(R.id.btnCountMinus).setOnClickListener {
            AppPrefs.setTodayTtsCount(this, AppPrefs.getTodayTtsCount(this) - 1)
            bind()
        }
        findViewById<TextView>(R.id.btnCountPlus).setOnClickListener {
            AppPrefs.setTodayTtsCount(this, AppPrefs.getTodayTtsCount(this) + 1)
            bind()
        }
        findViewById<TextView>(R.id.btnRepeatMinus).setOnClickListener {
            AppPrefs.setTodayTtsRepeat(this, AppPrefs.getTodayTtsRepeat(this) - 1)
            refreshLabels()
        }
        findViewById<TextView>(R.id.btnRepeatPlus).setOnClickListener {
            AppPrefs.setTodayTtsRepeat(this, AppPrefs.getTodayTtsRepeat(this) + 1)
            refreshLabels()
        }
        findViewById<Button>(R.id.btnTodayPlay).setOnClickListener { startShow() }
        findViewById<Button>(R.id.btnTodayStop).setOnClickListener { StudyTtsService.stop(this) }
        findViewById<Button>(R.id.btnTodayReshuffle).setOnClickListener {
            if (selectedDate != TodayTtsStore.todayKey()) {
                Toast.makeText(this, "지난 날짜는 그대로 보관해요. 오늘만 다시 뽑을 수 있어요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            StudyTtsService.stop(this)
            TodayTtsStore.current(this, forceNew = true)
            bind()
            Toast.makeText(this, "오늘 카드를 다시 뽑았어요", Toast.LENGTH_SHORT).show()
        }
        StudyTtsHub.addListener(ttsListener)
        bind()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        bindTodaySplit()
    }

    private fun bindTodaySplit() {
        bindLandscapeSplit(R.id.layoutTodaySplit)
        val split = findViewById<LinearLayout>(R.id.layoutTodaySplit) ?: return
        val d = resources.displayMetrics.density
        val land = isLandscape()
        val gutter = (40 * d).toInt()
        for (i in 0 until split.childCount) {
            val child = split.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            lp.marginStart = if (land && i > 0) gutter else 0
            lp.marginEnd = 0
            child.layoutParams = lp
        }
        val header = findViewById<TextView>(R.id.tvTodayCardHeader)
        val headerLp = header.layoutParams as LinearLayout.LayoutParams
        headerLp.topMargin = if (land) 0 else (22 * d).toInt()
        header.layoutParams = headerLp
    }

    override fun onDestroy() {
        StudyTtsHub.removeListener(ttsListener)
        super.onDestroy()
    }

    private fun bind() {
        refreshLabels()
        bindDateChips()
        val isToday = selectedDate == TodayTtsStore.todayKey()
        listOf(R.id.btnCountMinus, R.id.btnCountPlus).forEach { id ->
            val v = findViewById<TextView>(id)
            v.isEnabled = isToday
            v.alpha = if (isToday) 1f else 0.35f
        }
        findViewById<Button>(R.id.btnTodayReshuffle).isEnabled = isToday
        val pick = TodayTtsStore.get(this, selectedDate)
        findViewById<TextView>(R.id.tvTodayTtsReason).text =
            pick?.reason ?: "이 날짜에 보관한 듣기가 없어요"
        findViewById<TextView>(R.id.tvTodayCardHeader).text =
            "${TodayTtsStore.displayDate(selectedDate)} 카드"
        bindCards(pick?.ids.orEmpty())
        refreshNow()
    }

    private fun bindDateChips() {
        val box = findViewById<LinearLayout>(R.id.layoutTodayDates)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        TodayTtsStore.dates(this).forEach { key ->
            box.addView(
                pill(
                    TodayTtsStore.displayDate(key),
                    key == selectedDate,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (8 * d).toInt() }
                ) {
                    selectedDate = key
                    bind()
                }
            )
        }
    }

    private fun refreshLabels() {
        findViewById<TextView>(R.id.tvTodayCount).text =
            "${AppPrefs.getTodayTtsCount(this)}장"
        findViewById<TextView>(R.id.tvTodayRepeat).text =
            "${AppPrefs.getTodayTtsRepeat(this)}번"
    }

    private fun pill(
        label: String,
        on: Boolean,
        lp: LinearLayout.LayoutParams,
        click: () -> Unit
    ): TextView {
        val d = resources.displayMetrics.density
        val tv = TextView(this)
        tv.layoutParams = lp
        tv.gravity = Gravity.CENTER
        tv.text = label
        tv.textSize = 13f
        tv.setTypeface(tv.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        tv.setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        tv.background = ContextCompat.getDrawable(
            this,
            if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off
        )
        tv.setTextColor(
            ContextCompat.getColor(this, if (on) android.R.color.white else R.color.text_main)
        )
        tv.setOnClickListener { click() }
        return tv
    }

    private fun bindCards(ids: List<String>) {
        val box = findViewById<LinearLayout>(R.id.layoutTodayCards)
        box.removeAllViews()
        val cards = CardStore.getAllCards(this).associateBy { it.id }
        val d = resources.displayMetrics.density
        ids.forEachIndexed { i, id ->
            val card = cards[id] ?: return@forEachIndexed
            val cv = CardView(this)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * d).toInt()
            cv.layoutParams = lp
            cv.radius = 16 * d
            cv.cardElevation = 1 * d
            cv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))
            cv.setOnClickListener { launchConceptDetail(this, card) }

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val pad = (14 * d).toInt()
            col.setPadding(pad, pad, pad, pad)

            val sub = TextView(this)
            sub.text = "${i + 1}  ·  ${card.subject}"
            sub.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
            sub.textSize = 12f

            val title = TextView(this)
            title.text = card.topicTitle.ifBlank { card.title }
            title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            title.textSize = 16f
            title.setTypeface(title.typeface, Typeface.BOLD)
            val tlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            tlp.topMargin = (4 * d).toInt()
            title.layoutParams = tlp

            col.addView(sub)
            col.addView(title)
            cv.addView(col)
            box.addView(cv)
        }
    }

    private fun refreshNow() {
        val pick = TodayTtsStore.get(this, selectedDate)
        val st = StudyTtsHub.state
        val tv = findViewById<TextView>(R.id.tvTodayTtsNow)
        val ids = pick?.ids.orEmpty()
        val onThis = st.host && st.ids == ids && ids.isNotEmpty() && (st.playing || st.paused)
        tv.text = if (onThis) {
            val bit = if (st.paused) "일시정지" else "지금 읽는 중"
            "$bit  ${st.index + 1}/${st.ids.size}  ·  ${st.pass}/${st.repeat}회  ·  ${st.title}"
        } else if (selectedDate == TodayTtsStore.todayKey()) {
            "제목을 듣고 ${AppPrefs.getTtsThinkSeconds(this)}초 쉰 뒤, 본문을 읽어 드려요"
        } else {
            "이 날짜 묶음을 다시 들을 수 있어요"
        }
        findViewById<Button>(R.id.btnTodayPlay).text =
            if (onThis && st.playing) "일시정지" else "사연처럼 듣기"
    }

    private fun startShow() {
        val ids = TodayTtsStore.get(this, selectedDate)?.ids.orEmpty()
        if (ids.isEmpty()) {
            Toast.makeText(this, "들을 카드가 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val st = StudyTtsHub.state
        if (st.host && st.ids == ids && (st.playing || st.paused)) {
            StudyTtsService.toggle(this)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4401)
            return
        }
        StudyTtsService.play(
            this,
            ids,
            0,
            host = true,
            repeat = AppPrefs.getTodayTtsRepeat(this),
            hostDate = selectedDate
        )
        if (selectedDate == TodayTtsStore.todayKey()) DailyQuestStore.markListenDone(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4401) startShow()
    }
}
