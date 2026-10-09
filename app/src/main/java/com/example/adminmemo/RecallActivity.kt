package com.example.adminmemo

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class RecallActivity : BaseActivity() {

    override val showGeminiFab = true

    override fun geminiStudyContext(): String {
        val card = queue.getOrNull(index) ?: return ""
        return "${card.topicTitle}\n${card.back}"
    }

    private var queue: List<Card> = emptyList()
    private var index = 0
    private var revealed = false
    private var bodyOpen = false
    private var items: List<RecallCheckItem> = emptyList()
    private var keyStates = intArrayOf()
    private var remainSec = THINK_SEC
    private var caseMode = false
    private var fromCards = false
    private var bodyWriteForced = false
    private var retrainMode = false
    private var boostMode = false
    private var unseenMode = false
    private var sessionSubject = ALL_SUBJECTS_KEY
    private var sessionPeriod = 0
    private var resumedSession = false
    private var originalForcedIds: List<String> = emptyList()
    private var baseCount = 0
    private var caseStage = 0
    private var pendingGrade: Int? = null
    private var pendingFailKind = RecallStore.FAIL_NONE
    private var missLoop = false
    private var missQueue: List<RecallCheckItem> = emptyList()
    private var missIndex = 0
    private var missPeekOpen = false
    private val results = mutableListOf<Pair<Card, Int>>()
    private val pendingFlush = mutableListOf<PendingGrade>()
    private val selfMissByCard = mutableMapOf<String, Int>()
    private val failKindByCard = mutableMapOf<String, Int>()
    private val retried = mutableSetOf<String>()
    private val pendingRetry = mutableListOf<Card>()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (revealed || isFinishing) return
            remainSec--
            bindTimer()
            if (remainSec <= 0) {
                findViewById<TextView>(R.id.tvRecallHint).text =
                    "시간이 지났어요. 항목을 고르면 채점됩니다. 잘 떠올림은 할 수 없어요."
                return
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ALL_SUBJECTS_KEY
        val period = intent.getIntExtra(EXTRA_RECALL_PERIOD, 0)
        caseMode = intent.getBooleanExtra(EXTRA_RECALL_CASE, false)
        val unseen = intent.getBooleanExtra(EXTRA_RECALL_UNSEEN, false)
        val forcedIds = intent.getStringArrayListExtra(EXTRA_RECALL_CARD_IDS).orEmpty()
        fromCards = forcedIds.isNotEmpty()
        bodyWriteForced = intent.getBooleanExtra(EXTRA_RECALL_BODY, false)
        retrainMode = intent.getBooleanExtra(EXTRA_RECALL_RETRAIN, false)
        boostMode = intent.getBooleanExtra(EXTRA_RECALL_BOOST, false)
        unseenMode = unseen
        sessionSubject = subject
        sessionPeriod = period
        originalForcedIds = forcedIds
        val snap = RecallSessionStore.load(this)
        when {
            RecallSessionStore.unfinished(snap) && snap != null && !RecallSessionStore.isToday(snap) -> {
                askResumeOrFresh(snap)
                return
            }
            RecallSessionStore.unfinished(snap) && snap != null &&
                RecallSessionStore.matches(
                    snap, subject, period, caseMode, unseen, bodyWriteForced, retrainMode, originalForcedIds
                ) -> startResumed(snap)
            else -> startFresh(clearSnap = false)
        }
    }

    private fun askResumeOrFresh(snap: RecallSessionSnap) {
        AlertDialog.Builder(this)
            .setTitle("남은 인출이 있어요")
            .setMessage(
                "${RecallSessionStore.scopeLabel(snap)}\n\n" +
                    "이어서 풀면 남은 큐를 계속 풉니다. 오늘 새로 시작하면 남은 큐만 버리고, 이미 채점한 장은 그대로 둡니다."
            )
            .setPositiveButton("이어서 풀기") { _, _ -> startResumed(snap) }
            .setNegativeButton("오늘 새로 시작") { _, _ -> startFresh(clearSnap = true) }
            .setNeutralButton("돌아가기") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startResumed(snap: RecallSessionSnap) {
        sessionSubject = snap.subject
        sessionPeriod = snap.period
        caseMode = snap.caseMode
        unseenMode = snap.unseen
        bodyWriteForced = snap.bodyWrite
        retrainMode = snap.retrain
        originalForcedIds = snap.forcedIds
        fromCards = snap.forcedIds.isNotEmpty()
        queue = restoreSession(snap)
        resumedSession = queue.isNotEmpty()
        if (queue.isEmpty()) startFresh(clearSnap = true) else afterQueueReady()
    }

    private fun startFresh(clearSnap: Boolean) {
        if (clearSnap) RecallSessionStore.clear(this)
        resumedSession = false
        val forcedIds = originalForcedIds
        queue = when {
            forcedIds.isNotEmpty() -> {
                val byId = CardStore.getAllCards(this).associateBy { it.id }
                forcedIds.mapNotNull { byId[it] }
                    .ifEmpty { RecallStore.pickQueue(this, sessionSubject, sessionPeriod) }
            }
            caseMode -> MockExamRepository.pickCaseQueue(
                this,
                sessionSubject,
                sessionPeriod,
                if (RecallStore.isWeekend()) 6 else 4
            )
            else -> RecallStore.pickQueue(this, sessionSubject, sessionPeriod, unseenOnly = unseenMode)
        }
        afterQueueReady()
    }

    private fun afterQueueReady() {
        if (queue.isEmpty()) {
            Toast.makeText(
                this,
                if (unseenMode) "이번 달에 아직 인출하지 않은 주제가 없어요" else "인출할 주제가 없어요",
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }
        if (baseCount <= 0) baseCount = queue.size
        if (shouldWarmup()) {
            showWarmup()
            return
        }
        bindSessionUi()
        if (resumedSession && index > 0) {
            Toast.makeText(this, "${index + 1}번부터 이어서 풉니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shouldWarmup(): Boolean {
        if (resumedSession || fromCards || retrainMode || caseMode) return false
        return RecallStore.warmupCards(this).isNotEmpty()
    }

    private fun showWarmup() {
        setContentView(R.layout.activity_recall_warmup)
        val box = findViewById<LinearLayout>(R.id.layoutWarmupCards)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        RecallStore.warmupCards(this).forEachIndexed { i, card ->
            val tv = TextView(this)
            tv.text = "${i + 1}. 「${card.topicTitle.ifBlank { card.title }}」\n    ${canonicalizeSubject(card.subject)}"
            tv.textSize = 17f
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            tv.setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            box.addView(tv)
        }
        findViewById<View>(R.id.btnWarmupStart).setOnClickListener { bindSessionUi() }
        findViewById<View>(R.id.btnWarmupSkip).setOnClickListener { bindSessionUi() }
    }

    private fun bindSessionUi() {
        setContentView(R.layout.activity_recall)
        bindRecallSplit()
        confirmLeaveOnBack("인출을 그만둘까요? 푼 장은 저장되어 다음에 이어서 풀 수 있어요.") {
            index < queue.size && results.size < queue.size
        }
        wireRecallGeminiBody()
        findViewById<View>(R.id.btnRecallReveal).setOnClickListener {
            if (!missLoop) reveal()
        }
        findViewById<View>(R.id.btnRecallReveal).setOnTouchListener { v, ev ->
            if (!missLoop) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    missPeekOpen = true
                    bindMissPeek()
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    missPeekOpen = false
                    bindMissPeek()
                    v.isPressed = false
                    true
                }
                else -> true
            }
        }
        findViewById<View>(R.id.btnRecallBody).setOnClickListener {
            when {
                missLoop && missIndex >= missQueue.size - 1 -> toggleBody()
                missLoop -> confirmSkipMissLoop()
                else -> toggleBody()
            }
        }
        findViewById<View>(R.id.btnRecallConfirm).setOnClickListener {
            if (missLoop) {
                advanceMissLoop()
                return@setOnClickListener
            }
            val g = computedGrade()
            if (g == null) {
                Toast.makeText(this, "모든 항목을 고르세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (g == RecallStore.GRADE_GOOD) continueAfterGrade(g, RecallStore.FAIL_NONE)
            else showFailKind(g)
        }
        findViewById<View>(R.id.btnFailStructure).setOnClickListener {
            continueAfterGrade(pendingGrade ?: return@setOnClickListener, RecallStore.FAIL_STRUCTURE)
        }
        findViewById<View>(R.id.btnFailTip).setOnClickListener {
            continueAfterGrade(pendingGrade ?: return@setOnClickListener, RecallStore.FAIL_TIP)
        }
        showCard()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (findViewById<View?>(R.id.layoutRecallSplit) != null) bindRecallSplit()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun showCard() {
        handler.removeCallbacks(tick)
        if (index >= queue.size) {
            if (pendingRetry.isNotEmpty()) {
                retried.addAll(pendingRetry.map { it.id })
                queue = queue + pendingRetry.toList()
                pendingRetry.clear()
            } else {
                showResult()
                return
            }
        }
        revealed = false
        bodyOpen = false
        caseStage = 0
        missLoop = false
        missQueue = emptyList()
        missIndex = 0
        missPeekOpen = false
        pendingFailKind = RecallStore.FAIL_NONE
        orderMissActions(missMode = false)
        items = emptyList()
        keyStates = intArrayOf()
        val card = queue[index]
        val bodyWrite = isBodyWrite(card)
        remainSec = thinkSecondsFor(card, bodyWrite)
        val modeLabel = when {
            retrainMode -> "다시 인출"
            boostMode -> "보강 인출"
            caseMode -> "사례 인출"
            bodyWrite -> "본문 전체 쓰기"
            fromCards -> "퀘스트 인출"
            else -> "인출"
        }
        val progress = recallProgressLabel()
        findViewById<TextView>(R.id.tvRecallProgress).text =
            if (bodyWrite) "$modeLabel  $progress  ·  제목만 보고 본문까지 쓰세요"
            else "$modeLabel  $progress  ·  제목만 보고 쓰세요"
        findViewById<TextView>(R.id.tvRecallSubject).text = canonicalizeSubject(card.subject)
        findViewById<TextView>(R.id.tvRecallTitle).text = if (caseMode) {
            card.front.ifBlank { "「${card.topicTitle}」 사례" }
        } else {
            "「${card.topicTitle.ifBlank { card.title }}」"
        }
        findViewById<TextView>(R.id.tvRecallHint).text = when {
            caseMode ->
                "① 결론 한 문장 ② 이유 한 문장 ③ 목차 순으로 쓰세요. ${remainSec}초."
            bodyWrite ->
                "제목만 보고 목차와 본문을 처음부터 끝까지 문장으로 쓰세요. 막힌 항목만 골라 쓰지 마세요. ${remainSec}초."
            else ->
                "1. 큰 덩어리가 몇 개인지  2. 제목을 쓰고  3. 제목만 보고 목차를 펼치세요. ${remainSec}초."
        }
        findViewById<LinearLayout>(R.id.layoutRecallRubric).visibility = View.GONE
        findViewById<View>(R.id.btnRecallReveal).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.btnRecallReveal).text =
            when {
                caseMode -> "결론 보기"
                bodyWrite -> "쓴 뒤 대조하기"
                else -> "목차 대조하기"
            }
        findViewById<View>(R.id.btnRecallBody).visibility = View.GONE
        findViewById<View>(R.id.btnRecallConfirm).visibility = View.GONE
        findViewById<View>(R.id.layoutRecallFailKind).visibility = View.GONE
        pendingGrade = null
        findViewById<LinearLayout>(R.id.layoutRecallChecks).removeAllViews()
        bindBodyOpen(false)
        findViewById<TextView>(R.id.tvRecallGradeHint).text = ""
        bindTimer()
        bindCoach()
        handler.postDelayed(tick, 1000L)
    }

    private fun bindTimer() {
        val tv = findViewById<TextView>(R.id.tvRecallTimer)
        if (revealed && (!caseMode || caseStage >= 3)) {
            tv.text = "대조 중"
            return
        }
        val m = remainSec / 60
        val s = remainSec % 60
        tv.text = if (remainSec <= 0) "시간 종료" else String.format("%d:%02d", m, s)
    }

    private fun reveal() {
        val card = queue.getOrNull(index) ?: return
        if (caseMode && caseStage < 3) {
            handler.removeCallbacks(tick)
            caseStage++
            val chunk = caseRecallItemChunks(card).getOrNull(caseStage - 1).orEmpty()
            appendChecks(chunk)
            findViewById<LinearLayout>(R.id.layoutRecallRubric).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvRecallHint).text = when (caseStage) {
                1 -> "결론이 있었는지 고르세요. 이어서 이유를 쓰세요."
                2 -> "이유의 핵심을 고른 뒤, 목차를 쓰세요."
                else -> "이 목차가 있었는지 고르세요. 본문은 채점 뒤에 펼치세요."
            }
            findViewById<MaterialButton>(R.id.btnRecallReveal).text = when (caseStage) {
                1 -> "이유 보기"
                2 -> "목차 대조하기"
                else -> "목차 대조하기"
            }
            if (caseStage >= 3) finishReveal(card)
            bindCoach()
            return
        }
        handler.removeCallbacks(tick)
        findViewById<LinearLayout>(R.id.layoutRecallChecks).removeAllViews()
        val chunk = outlineRecallItems(card).ifEmpty {
            listOf(
                RecallCheckItem(
                    "쉬운 말로 몇 덩어리인지부터 나눠 보세요.",
                    listOf("덩어리"),
                    "큰 덩어리가 몇 개인지부터 나눠 보세요."
                )
            )
        }
        appendChecks(chunk)
        finishReveal(card)
    }

    private fun finishReveal(card: Card) {
        revealed = true
        bindTimer()
        findViewById<LinearLayout>(R.id.layoutRecallRubric).visibility = View.VISIBLE
        findViewById<View>(R.id.btnRecallReveal).visibility = View.GONE
        findViewById<View>(R.id.btnRecallBody).visibility = View.VISIBLE
        findViewById<View>(R.id.btnRecallConfirm).visibility = View.VISIBLE
        findViewById<View>(R.id.layoutRecallFailKind).visibility = View.GONE
        pendingGrade = null
        findViewById<TextView>(R.id.tvRecallBody).text = styledStudyAnswer(
            this,
            card.back,
            CardStore.isLocallyEdited(this, card.id)
        )
        wireRecallGeminiBody()
        bindBodyOpen(false)
        bindGradeHint()
        bindCoach()
    }

    private fun appendChecks(chunk: List<RecallCheckItem>) {
        val startKeys = keyStates.size
        items = items + chunk
        val newKeys = chunk.size
        keyStates = IntArray(startKeys + newKeys) { i ->
            if (i < startKeys && i < keyStates.size) keyStates[i] else MARK_YES
        }
        val box = findViewById<LinearLayout>(R.id.layoutRecallChecks)
        val d = resources.displayMetrics.density
        var keyIndex = startKeys
        chunk.forEach { item ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val tv = TextView(this)
            tv.text = item.heading
            tv.textSize = 15f
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            tv.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            col.addView(tv)
            val hint = item.keys.firstOrNull().orEmpty().trim()
            val title = outlineHeadingText(item.heading)
            if (item.allowGap && hint.isNotBlank() && hint != title) {
                val hintTv = TextView(this)
                hintTv.text = "힌트  $hint"
                hintTv.textSize = 13f
                hintTv.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
                hintTv.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (4 * d).toInt())
                col.addView(hintTv)
            }
            val cap = TextView(this)
            cap.text = "이 목차가 있었나요?"
            cap.textSize = 12f
            cap.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
            cap.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (4 * d).toInt())
            col.addView(cap)
            val i = keyIndex
            keyIndex++
            val group = RadioGroup(this)
            group.orientation = LinearLayout.VERTICAL
            group.setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (6 * d).toInt())
            val yes = RadioButton(this)
            yes.id = View.generateViewId()
            yes.text = "있음"
            yes.textSize = 13f
            val no = RadioButton(this)
            no.id = View.generateViewId()
            no.text = "없음"
            no.textSize = 13f
            group.addView(yes)
            group.addView(no)
            val gapId = if (item.allowGap) {
                val gapBtn = RadioButton(this)
                gapBtn.id = View.generateViewId()
                gapBtn.text = "핵심이 빠짐"
                gapBtn.textSize = 13f
                group.addView(gapBtn)
                gapBtn.id
            } else -1
            group.setOnCheckedChangeListener { _, checkedId ->
                keyStates[i] = when (checkedId) {
                    yes.id -> MARK_YES
                    no.id -> MARK_NO
                    gapId -> MARK_GAP
                    else -> MARK_NONE
                }
                bindGradeHint()
            }
            yes.isChecked = true
            col.addView(group)
            val cv = CardView(this)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * d).toInt()
            cv.layoutParams = lp
            cv.radius = 12 * d
            cv.cardElevation = 1 * d
            cv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))
            cv.addView(col)
            box.addView(cv)
        }
        bindGradeHint()
    }

    private fun recallBodyText(): CharSequence {
        val card = queue.getOrNull(index) ?: return ""
        return styledStudyAnswer(this, card.back, CardStore.isLocallyEdited(this, card.id))
    }

    private fun bindBodyOpen(open: Boolean) {
        bodyOpen = open
        val land = isLandscape()
        val left = findViewById<TextView?>(R.id.tvRecallBodyLeft)
        val right = findViewById<TextView>(R.id.tvRecallBody)
        val src = recallBodyText()
        if (bodyOpen) {
            if (land && left != null) {
                left.text = src
                left.visibility = View.VISIBLE
                right.visibility = View.GONE
            } else {
                left?.visibility = View.GONE
                right.text = src
                right.visibility = View.VISIBLE
            }
        } else {
            left?.visibility = View.GONE
            right.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btnRecallBody).text = when {
            missLoop && missIndex >= missQueue.size - 1 ->
                if (bodyOpen) "본문 접기" else "본문내용 보기"
            missLoop -> "다음 문제로 건너뛰기"
            bodyOpen -> "본문 접기"
            else -> "본문 보기"
        }
        if (bodyOpen) wireRecallGeminiBody()
        bindCoach()
    }

    private fun wireRecallGeminiBody() {
        val ctx = { geminiStudyContext() }
        listOfNotNull(
            findViewById<TextView?>(R.id.tvRecallBody),
            findViewById<TextView?>(R.id.tvRecallBodyLeft)
        ).forEach { tv ->
            tv.setTextIsSelectable(true)
            enableGeminiSelection(this, tv, ctx)
        }
    }

    private fun toggleBody() {
        bindBodyOpen(!bodyOpen)
    }

    private fun thinkSecondsFor(card: Card, bodyWrite: Boolean): Int {
        val letters = card.back.count { !it.isWhitespace() }
        fun walk(nodes: List<OutlineNode>): Int =
            nodes.sumOf { n -> if (n.level >= 0) 1 + walk(n.children) else walk(n.children) }
        val heads = walk(parseOutline(card.back))
        val base: Int
        val cap: Int
        val charOver: Int
        val charStep: Int
        val charBonus: Int
        val headOver: Int
        val headBonus: Int
        when {
            caseMode -> {
                base = CASE_THINK_SEC; cap = 360
                charOver = 450; charStep = 220; charBonus = 18
                headOver = 6; headBonus = 8
            }
            bodyWrite -> {
                base = BODY_THINK_SEC; cap = 540
                charOver = 450; charStep = 160; charBonus = 25
                headOver = 5; headBonus = 12
            }
            else -> {
                base = THINK_SEC; cap = 240
                charOver = 450; charStep = 200; charBonus = 15
                headOver = 5; headBonus = 8
            }
        }
        val extraChars = ((letters - charOver).coerceAtLeast(0) / charStep) * charBonus
        val extraHeads = (heads - headOver).coerceAtLeast(0) * headBonus
        return (base + extraChars + extraHeads).coerceIn(base, cap)
    }

    private fun isBodyWrite(card: Card): Boolean {
        if (caseMode) return false
        if (bodyWriteForced) return true
        return DailyQuestStore.questForCard(this, card.id, DailyQuestStore.TYPE_BODY) != null
    }

    private fun bindCoach() {
        val card = queue.getOrNull(index)
        findViewById<TextView>(R.id.tvRecallCoach).text = when {
            missLoop -> CoachHints.MISS_PEEK
            card != null && isBodyWrite(card) -> CoachHints.BODY_FULL
            bodyOpen -> CoachHints.BODY
            caseMode && caseStage in 1..2 -> CoachHints.CASE_MID
            caseMode && !revealed -> CoachHints.CASE_THINK
            revealed -> CoachHints.REVEAL
            caseMode -> CoachHints.CASE_THINK
            else -> CoachHints.THINK
        }
    }

    private fun selfMissCount(): Int {
        val n = keyStates.size
        if (n == 0) return 0
        val miss = keyStates.count { it == MARK_NO || it == MARK_GAP }
        return when {
            miss * 2 >= n -> 2
            miss > 0 -> 1
            else -> 0
        }
    }

    private fun computedGrade(): Int? {
        if (keyStates.isEmpty()) return RecallStore.GRADE_HALF
        if (keyStates.any { it == MARK_NONE }) return null
        val n = keyStates.size
        val yes = keyStates.count { it == MARK_YES }
        val raw = when {
            yes * 100 / n >= 70 -> RecallStore.GRADE_GOOD
            yes * 100 / n >= 35 -> RecallStore.GRADE_HALF
            else -> RecallStore.GRADE_MISS
        }
        return if (remainSec <= 0 && raw == RecallStore.GRADE_GOOD) RecallStore.GRADE_HALF else raw
    }

    private fun gradeLabel(g: Int): String = when (g) {
        RecallStore.GRADE_GOOD -> "잘 떠올림"
        RecallStore.GRADE_HALF -> "반쯤"
        else -> "거의 못함"
    }

    private fun bindGradeHint() {
        val btn = findViewById<MaterialButton>(R.id.btnRecallConfirm)
        val n = keyStates.size
        val yes = keyStates.count { it == MARK_YES }
        val no = keyStates.count { it == MARK_NO }
        val gap = keyStates.count { it == MARK_GAP }
        val unset = keyStates.count { it == MARK_NONE }
        val g = computedGrade()
        val parts = mutableListOf<String>()
        if (n > 0) parts.add("있음 $yes  ·  없음 $no  ·  빠짐 $gap / $n")
        if (unset > 0) parts.add("남은 항목 ${unset}개")
        if (remainSec <= 0) parts.add("시간 종료 · 잘 떠올림 없음")
        if (g != null) parts.add("확정 등급 「${gradeLabel(g)}」")
        val hint = findViewById<TextView>(R.id.tvRecallGradeHint)
        hint.text = parts.joinToString("  ·  ")
        hint.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        btn.isEnabled = g != null
        btn.text = if (g == null) "항목을 모두 고르세요" else "채점 확정  ·  ${gradeLabel(g)}"
    }

    private fun bindRecallSplit() {
        val split = findViewById<LinearLayout>(R.id.layoutRecallSplit) ?: return
        val left = split.getChildAt(0)
        val right = split.getChildAt(1)
        if (isLandscape()) {
            split.orientation = LinearLayout.HORIZONTAL
            left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            right.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f)
        } else {
            split.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            right.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (bodyOpen) bindBodyOpen(true)
        else if (missLoop) bindMissPeek()
    }

    private fun missedCheckItems(): List<RecallCheckItem> {
        var i = 0
        val out = mutableListOf<RecallCheckItem>()
        for (item in items) {
            val hit = i < keyStates.size && (keyStates[i] == MARK_NO || keyStates[i] == MARK_GAP)
            i += 1
            if (hit) out.add(item)
        }
        return out.take(MISS_CAP)
    }

    private fun continueAfterGrade(g: Int, failKind: Int) {
        pendingGrade = g
        pendingFailKind = failKind
        val missed = missedCheckItems()
        if (missed.isNotEmpty()) startMissLoop(g, missed)
        else grade(g, failKind)
    }

    private fun startMissLoop(grade: Int, missed: List<RecallCheckItem>) {
        pendingGrade = grade
        missLoop = true
        missQueue = missed
        missIndex = 0
        missPeekOpen = false
        bodyOpen = false
        findViewById<View>(R.id.layoutRecallFailKind).visibility = View.GONE
        bindMissStep()
    }

    private fun bindMissStep() {
        val item = missQueue.getOrNull(missIndex) ?: run {
            finishMissLoop()
            return
        }
        val n = missQueue.size
        val last = missIndex >= n - 1
        val card = queue.getOrNull(index)
        val topic = card?.topicTitle.orEmpty().ifBlank { card?.title.orEmpty() }
        findViewById<LinearLayout>(R.id.layoutRecallRubric).visibility = View.GONE
        findViewById<View>(R.id.layoutRecallFailKind).visibility = View.GONE
        findViewById<TextView>(R.id.tvRecallSubject).text =
            listOfNotNull(
                card?.subject?.let { canonicalizeSubject(it) }?.ifBlank { null },
                topic.ifBlank { null }?.let { "「$it」" }
            ).joinToString("  ·  ")
        val headingTv = findViewById<TextView>(R.id.tvRecallTitle)
        headingTv.text = missStepTitle(item.heading)
        headingTv.animate().cancel()
        headingTv.alpha = 0.15f
        headingTv.animate().alpha(1f).setDuration(220).start()
        findViewById<TextView>(R.id.tvRecallHint).text =
            "빠진 항목 다시 쓰기  ${missIndex + 1} / $n  ·  이 목차만 다시 쓰세요"
        findViewById<TextView>(R.id.tvRecallGradeHint).text = ""
        findViewById<View>(R.id.tvRecallGradeHint).visibility = View.GONE
        val reveal = findViewById<MaterialButton>(R.id.btnRecallReveal)
        reveal.visibility = View.VISIBLE
        reveal.text = "이 항목 보기"
        val skip = findViewById<MaterialButton>(R.id.btnRecallBody)
        skip.visibility = View.VISIBLE
        skip.text = if (last) {
            if (bodyOpen) "본문 접기" else "본문내용 보기"
        } else {
            "다음 문제로 건너뛰기"
        }
        val next = findViewById<MaterialButton>(R.id.btnRecallConfirm)
        next.visibility = View.VISIBLE
        next.isEnabled = true
        next.text = if (last) "다음 문제 인출하기" else "다음 빈칸"
        orderMissActions(missMode = true)
        if (bodyOpen) bindBodyOpen(true) else bindMissPeek()
        bindCoach()
    }

    private fun View.detachFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun orderMissActions(missMode: Boolean) {
        val reveal = findViewById<View>(R.id.btnRecallReveal)
        val skip = findViewById<MaterialButton>(R.id.btnRecallBody)
        val next = findViewById<MaterialButton>(R.id.btnRecallConfirm)
        val row = findViewById<LinearLayout>(R.id.layoutRecallMissNav)
        val fail = findViewById<View>(R.id.layoutRecallFailKind)
        val parent = reveal.parent as? LinearLayout ?: return
        val floor = resources.getDimensionPixelSize(R.dimen.btn_min_height)
        val h = floor * 2
        val gap = (8 * resources.displayMetrics.density).toInt()
        reveal.detachFromParent()
        skip.detachFromParent()
        next.detachFromParent()
        row.detachFromParent()
        fail.detachFromParent()
        parent.addView(reveal)
        val revealBtn = reveal as MaterialButton
        revealBtn.insetTop = 0
        revealBtn.insetBottom = 0
        revealBtn.textSize = if (missMode) 20f else 15f
        val tint = ContextCompat.getColor(this, if (missMode) R.color.miss_peek_btn else R.color.primary)
        revealBtn.backgroundTintList = ColorStateList.valueOf(tint)
        revealBtn.setTextColor(ContextCompat.getColor(this, R.color.text_on_header))
        val revealH = if (missMode) h * 2 else h
        revealBtn.minHeight = revealH
        revealBtn.minimumHeight = revealH
        val vPad = if (missMode) h / 3 else (12 * resources.displayMetrics.density).toInt()
        revealBtn.setPaddingRelative(
            revealBtn.paddingStart,
            vPad,
            revealBtn.paddingEnd,
            vPad
        )
        revealBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            revealH
        )
        if (missMode) {
            next.minHeight = h
            next.minimumHeight = h
            skip.minHeight = h
            skip.minimumHeight = h
            next.textSize = 15f
            skip.textSize = 15f
            next.maxLines = 2
            skip.maxLines = 2
            val lastMiss = missIndex >= missQueue.size - 1
            if (lastMiss) {
                skip.layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply { marginEnd = gap }
                next.layoutParams = LinearLayout.LayoutParams(0, h, 1f)
                row.addView(skip)
                row.addView(next)
            } else {
                next.layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply { marginEnd = gap }
                skip.layoutParams = LinearLayout.LayoutParams(0, h, 1f)
                row.addView(next)
                row.addView(skip)
            }
            row.visibility = View.VISIBLE
            parent.addView(row)
        } else {
            skip.minHeight = floor
            skip.minimumHeight = floor
            next.minHeight = floor
            next.minimumHeight = floor
            skip.textSize = 15f
            next.textSize = 15f
            val wrap = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = gap }
            skip.layoutParams = wrap
            next.layoutParams = LinearLayout.LayoutParams(wrap)
            parent.addView(skip)
            parent.addView(next)
            row.visibility = View.GONE
            parent.addView(row)
        }
        parent.addView(fail)
    }

    private fun confirmSkipMissLoop() {
        AlertDialog.Builder(this)
            .setMessage("남은 빠진 항목을 건너뛰고 다음 문제로 갈까요?")
            .setPositiveButton("건너뛰기") { _, _ -> finishMissLoop() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun advanceMissLoop() {
        if (missIndex >= missQueue.size - 1) {
            finishMissLoop()
            return
        }
        missIndex++
        missPeekOpen = false
        bodyOpen = false
        bindMissStep()
    }

    private fun finishMissLoop() {
        missLoop = false
        missPeekOpen = false
        val g = pendingGrade ?: computedGrade() ?: return
        val kind = if (g == RecallStore.GRADE_GOOD) RecallStore.FAIL_NONE else pendingFailKind
        grade(g, kind)
    }

    private fun missStepTitle(heading: String): String {
        val t = outlineHeadingText(heading).ifBlank { heading.trim() }
        if (t.length <= 24) return t
        val mark = outlineMarkerOf(heading)
        return if (mark != "□") "$mark  세부 항목" else "세부 항목"
    }

    private fun bindMissPeek() {
        if (bodyOpen && !missPeekOpen) {
            bindBodyOpen(true)
            return
        }
        val land = isLandscape()
        val left = findViewById<TextView?>(R.id.tvRecallBodyLeft)
        val right = findViewById<TextView>(R.id.tvRecallBody)
        val text = missQueue.getOrNull(missIndex)?.peekText
            ?.ifBlank { missQueue.getOrNull(missIndex)?.heading.orEmpty() }
            .orEmpty()
        if (missPeekOpen && text.isNotBlank()) {
            if (land && left != null) {
                left.text = text
                left.visibility = View.VISIBLE
                right.visibility = View.GONE
            } else {
                left?.visibility = View.GONE
                right.text = text
                right.visibility = View.VISIBLE
            }
        } else {
            left?.visibility = View.GONE
            right.visibility = View.GONE
        }
    }

    private fun showFailKind(g: Int) {
        pendingGrade = g
        findViewById<View>(R.id.btnRecallConfirm).visibility = View.GONE
        findViewById<View>(R.id.layoutRecallFailKind).visibility = View.VISIBLE
        val hint = findViewById<TextView>(R.id.tvRecallGradeHint)
        hint.visibility = View.VISIBLE
        hint.text = "확정 등급 「${gradeLabel(g)}」  ·  막힌 이유를 고르세요"
    }

    private fun grade(g: Int, failKind: Int = RecallStore.FAIL_NONE) {
        val card = queue.getOrNull(index) ?: return
        val miss = selfMissCount()
        pendingFlush.add(PendingGrade(card, g, failKind, miss))
        results.add(card to g)
        if (g == RecallStore.GRADE_MISS && card.id !in retried) pendingRetry.add(card)
        index++
        if (index % RecallSessionStore.CHECKPOINT == 0) flushCheckpoint()
        showCard()
    }

    private fun flushCheckpoint() {
        pendingFlush.forEach { persistGrade(it) }
        pendingFlush.clear()
        RecallSessionStore.save(
            this,
            RecallSessionSnap(
                subject = sessionSubject,
                period = sessionPeriod,
                caseMode = caseMode,
                unseen = unseenMode,
                bodyWrite = bodyWriteForced,
                retrain = retrainMode,
                forcedIds = originalForcedIds,
                queueIds = queue.map { it.id },
                index = index,
                resultIds = results.map { it.first.id },
                resultGrades = results.map { it.second },
                retried = retried.toList(),
                pendingRetry = pendingRetry.map { it.id },
                selfMissByCard = selfMissByCard.toMap(),
                failKindByCard = failKindByCard.toMap(),
                baseCount = baseCount,
                date = TodayTtsStore.todayKey()
            )
        )
    }

    private fun persistGrade(p: PendingGrade) {
        if (p.card.id.startsWith("case_")) return
        RecallStore.record(this, p.card, p.grade, p.failKind)
        selfMissByCard[p.card.id] = maxOf(selfMissByCard[p.card.id] ?: 0, p.selfMiss)
        if (p.failKind != RecallStore.FAIL_NONE) failKindByCard[p.card.id] = p.failKind
        val quest = DailyQuestStore.openQuestForCard(this, p.card.id)
        if (quest != null) {
            val bodyQuest = quest.type == DailyQuestStore.TYPE_BODY
            if (!bodyQuest || p.grade != RecallStore.GRADE_MISS) {
                DailyQuestStore.bump(this, quest.id)
            }
        }
    }

    private fun restoreSession(snap: RecallSessionSnap): List<Card> {
        val byId = recallCardsById()
        val restored = snap.queueIds.mapNotNull { byId[it] }
        if (restored.isEmpty()) return emptyList()
        index = snap.index.coerceIn(0, restored.size)
        results.clear()
        val n = minOf(snap.resultIds.size, snap.resultGrades.size)
        for (i in 0 until n) {
            val card = byId[snap.resultIds[i]] ?: continue
            results.add(card to snap.resultGrades[i])
        }
        retried.clear()
        retried.addAll(snap.retried)
        pendingRetry.clear()
        snap.pendingRetry.mapNotNull { byId[it] }.forEach { pendingRetry.add(it) }
        selfMissByCard.clear()
        selfMissByCard.putAll(snap.selfMissByCard)
        failKindByCard.clear()
        failKindByCard.putAll(snap.failKindByCard)
        pendingFlush.clear()
        baseCount = when {
            snap.baseCount > 0 -> snap.baseCount
            snap.retried.isNotEmpty() && snap.pendingRetry.isEmpty() &&
                restored.size > snap.retried.size -> restored.size - snap.retried.size
            else -> restored.size
        }
        return restored
    }

    private fun recallProgressLabel(): String {
        val base = if (baseCount > 0) baseCount else queue.size
        val retryN = (queue.size - base).coerceAtLeast(0)
        return if (index < base || retryN == 0) {
            "${index + 1} / $base"
        } else {
            "${base}장  ·  재시도 ${index - base + 1} / $retryN"
        }
    }

    private fun recallCardsById(): Map<String, Card> {
        val map = CardStore.getAllCards(this).associateBy { it.id }.toMutableMap()
        MockExamRepository.loadMockExams(this).forEach { item ->
            val card = MockExamRepository.toRecallCard(item)
            map.putIfAbsent(card.id, card)
        }
        return map
    }

    private fun showResult() {
        handler.removeCallbacks(tick)
        pendingFlush.forEach { persistGrade(it) }
        pendingFlush.clear()
        RecallSessionStore.clear(this)
        val conceptResults = results.filter { !it.first.id.startsWith("case_") }
        if (boostMode) {
            DailyQuestStore.appendBoostFromSession(this, conceptResults, selfMissByCard, failKindByCard)
        } else if (!caseMode && !fromCards && !retrainMode) {
            DailyQuestStore.rebuildFromSession(
                this,
                conceptResults,
                selfMissByCard,
                failKindByCard
            )
        }
        if (retrainMode) DailyQuestStore.clearRetrain(this)
        setContentView(R.layout.activity_recall_result)
        val miss = results.count { it.second == RecallStore.GRADE_MISS }
        val half = results.count { it.second == RecallStore.GRADE_HALF }
        val good = results.count { it.second == RecallStore.GRADE_GOOD }
        findViewById<TextView>(R.id.tvRecallResultSummary).text = when {
            retrainMode ->
                "다시 인출 ${good + half + miss}장  ·  잘 떠올림 ${good}  ·  반쯤 ${half}  ·  거의 못함 ${miss}\n오늘 훈련 세트가 끝났습니다."
            boostMode ->
                "보강 ${good + half + miss}장  ·  잘 떠올림 ${good}  ·  반쯤 ${half}  ·  거의 못함 ${miss}\n막힌 장만 보강 퀘스트로 넣었어요. 쓰고 나서 한 세트가 끝입니다."
            else ->
                "잘 떠올림 ${good}  ·  반쯤 ${half}  ·  거의 못함 ${miss}\n막힌 장은 퀘스트에서 다시 쓴 뒤, 그 장만 다시 인출하세요."
        }
        val quests = DailyQuestStore.todayQuests(this)
        val boostQuests = quests.filter { DailyQuestStore.isBoostQuest(it) && !it.done }
        findViewById<TextView>(R.id.tvRecallResultQuests).text = when {
            retrainMode -> "채운 장을 다시 풀어 본 결과입니다."
            boostMode && boostQuests.isEmpty() -> "이번 세트에서 막힌 장이 없어서 보강 퀘스트가 없어요. 더 하려면 보강학습을 한 번 더 열면 됩니다."
            boostMode -> boostQuests.joinToString("\n") { q ->
                val kind = DailyQuestStore.kindLabel(q)
                val hint = if (q.hint.isBlank()) "" else " (${q.hint})"
                "· ${q.title}$hint  —  $kind"
            }
            caseMode -> "사례 인출은 퀘스트를 새로 만들지 않아요. 약했던 사례는 모의고사에서 다시 쓰세요."
            quests.isEmpty() -> "오늘은 막힌 장이 없어서 추가 퀘스트가 없어요."
            else -> quests.joinToString("\n") { q ->
                val kind = DailyQuestStore.kindLabel(q)
                val hint = if (q.hint.isBlank()) "" else " (${q.hint})"
                "· ${q.title}$hint  —  $kind"
            }
        }
        val btnPr = findViewById<View>(R.id.btnRecallOpenMissed)
        btnPr.visibility = when {
            retrainMode -> View.GONE
            boostMode -> if (boostQuests.isEmpty()) View.GONE else View.VISIBLE
            quests.isEmpty() -> View.GONE
            else -> View.VISIBLE
        }
        btnPr.setOnClickListener {
            startActivity(Intent(this, QuestActivity::class.java))
            finish()
        }
        val listen = DailyQuestStore.listenIds(this)
        val btnTts = findViewById<View>(R.id.btnRecallOpenTts)
        btnTts.visibility = if (listen.isEmpty()) View.GONE else View.VISIBLE
        btnTts.setOnClickListener {
            startActivity(Intent(this, TodayTtsActivity::class.java))
        }
        val retrainIds = DailyQuestStore.retrainIds(this)
        val btnRetrain = findViewById<View>(R.id.btnRecallOpenQuests)
        btnRetrain.visibility = if (retrainMode || retrainIds.isEmpty()) View.GONE else View.VISIBLE
        btnRetrain.setOnClickListener {
            val openWrite = DailyQuestStore.openQuests(this)
                .any { it.type == DailyQuestStore.TYPE_WRITE || it.type == DailyQuestStore.TYPE_BODY }
            if (openWrite) {
                Toast.makeText(this, "먼저 퀘스트로 부족한 부분을 채우세요", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, QuestActivity::class.java))
                finish()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, RecallActivity::class.java)
                    .putStringArrayListExtra(EXTRA_RECALL_CARD_IDS, ArrayList(retrainIds))
                    .putExtra(EXTRA_RECALL_RETRAIN, true)
            )
            finish()
        }
        findViewById<View>(R.id.btnRecallHome).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvRecallResultCoach).text = when {
            retrainMode -> CoachHints.RESULT
            quests.any { it.type == DailyQuestStore.TYPE_BODY } -> CoachHints.RESULT_BODY
            else -> CoachHints.RESULT
        }
    }

    companion object {
        private const val MARK_NONE = 0
        private const val MARK_YES = 1
        private const val MARK_NO = 2
        private const val MARK_GAP = 3
        private const val THINK_SEC = 90
        private const val BODY_THINK_SEC = 240
        private const val CASE_THINK_SEC = 180
        private const val MISS_CAP = 5
    }

    private data class PendingGrade(
        val card: Card,
        val grade: Int,
        val failKind: Int,
        val selfMiss: Int
    )
}
