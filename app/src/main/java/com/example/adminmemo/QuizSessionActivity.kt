package com.example.adminmemo

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

/**
 * 두문자퀴즈 통합 화면.
 * 매 라운드(문제)마다 6가지 유형(객관식/순서배열/OX/빈칸채우기/거꾸로/매칭) 중
 * 하나를 무작위로 고르되, 바로 이전 라운드와 같은 유형은 피한다.
 * 듀오링고 스타일: 두꺼운 알약형 버튼, 상단 진행바, 하단 고정 확인 버튼.
 */
class QuizSessionActivity : BaseActivity() {

    private enum class QType { CHOICE, ORDER, OX, FILLBLANK, REVERSE, MATCH, KEYWORD, OUTLINE, SENTENCE }

    private lateinit var subject: String
    private var reviewOnly = false

    private lateinit var basePool: List<Card>       // 중복 없는 두문자 카드 풀
    private lateinit var orderablePool: List<Card>   // 글자 2개 이상인 두문자만
    private lateinit var allTokens: List<String>
    private lateinit var conceptPool: List<Card>     // 키워드 맞추기용 개념카드 풀
    private lateinit var keywordPool: List<String>   // 키워드 맞추기 오답 후보 단어 풀
    private lateinit var outlineGroups: List<OutlineGroup>  // 목차퀴즈용 형제/하위 목차 묶음
    private lateinit var outlineLabelPool: List<String>     // 목차퀴즈 오답 후보 라벨 풀
    private lateinit var sentenceItems: List<SentenceQuizItem>  // 문장→목차 찾기 문제 풀
    private lateinit var eligibleTypes: List<QType>

    private var roundTypes: List<QType> = emptyList()
    private var roundIndex = 0
    private var totalPoints = 0.0
    private var totalPossible = 0.0
    private var roundAnswered = false
    private var currentRoundCards: List<Card> = emptyList()
    private var voiceTargetEditText: EditText? = null

    private lateinit var tvHeader: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvScore: TextView
    private lateinit var pbProgress: ProgressBar
    private lateinit var container: LinearLayout
    private lateinit var btnNext: Button

    private val handler = Handler(Looper.getMainLooper())
    private val REQ_VOICE = 4201

    // ---------- 색상 ----------
    private val colorCorrect = Color.parseColor("#58CC02")
    private val colorWrong = Color.parseColor("#FF4B4B")
    private val colorSelected = Color.parseColor("#DCE9FF")
    private val colorWhite = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz_session)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""
        reviewOnly = intent.getBooleanExtra(EXTRA_REVIEW_ONLY, false)

        tvHeader = findViewById(R.id.tvQuizHeader)
        tvHeader.text = if (reviewOnly) "🔁 오답 복습" else "🎲 학습퀴즈"
        tvProgress = findViewById(R.id.tvQuizProgress)
        tvScore = findViewById(R.id.tvQuizScore)
        pbProgress = findViewById(R.id.pbQuizProgress)
        container = findViewById(R.id.quizContainer)
        btnNext = findViewById(R.id.btnQuizNext)

        btnNext.setOnClickListener {
            roundIndex++
            if (roundIndex >= roundTypes.size) showResultDialog() else renderRound()
        }
        findViewById<Button>(R.id.btnQuizShowContext).setOnClickListener { showContextDialog() }

        if (!buildSession()) return
        renderRound()
    }

    // ---------- 크기/스타일 헬퍼 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(fillColor: Int, strokeColor: Int, strokeWidthDp: Int = 2, radiusDp: Int = 16): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private val defaultFill by lazy { ContextCompat.getColor(this, R.color.bg_card) }
    private val defaultStroke = Color.parseColor("#3A808080")
    private val defaultText by lazy { ContextCompat.getColor(this, R.color.text_main) }

    /** 선택지 버튼 기본 스타일 (두껍고 둥근 아웃라인 버튼) */
    private fun styleOptionButton(b: Button) {
        b.minHeight = dp(58)
        b.setPadding(dp(20), dp(14), dp(20), dp(14))
        b.textSize = 15.5f
        b.isAllCaps = false
        b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
        b.background = pill(defaultFill, defaultStroke)
        b.setTextColor(defaultText)
        b.elevation = 0f
        b.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6); lp.bottomMargin = dp(6)
        b.layoutParams = lp
    }

    private fun markCorrect(b: Button) {
        b.background = pill(colorCorrect, colorCorrect)
        b.setTextColor(colorWhite)
    }

    private fun markWrong(b: Button) {
        b.background = pill(colorWrong, colorWrong)
        b.setTextColor(colorWhite)
    }

    // ---------- 세션 구성 ----------

    private fun buildSession(): Boolean {
        val allMnemonics = CardStore.getAllMnemonicCards(this)
            .filter { it.subject == subject && it.topicTitle.isNotBlank() && it.mnemonic.isNotBlank() }
            .distinctBy { it.mnemonic }
        basePool = applyQuizFilters(this, allMnemonics, reviewOnly)
        orderablePool = basePool.filter {
            it.mnemonic.contains(".") &&
                it.mnemonic.split(".").map { t -> t.trim() }.filter { t -> t.isNotEmpty() }.size >= 2
        }
        allTokens = orderablePool.flatMap {
            it.mnemonic.split(".").map { t -> t.trim() }.filter { t -> t.isNotEmpty() }
        }.distinct()

        val allConcepts = CardStore.getAllCards(this).filter { it.type == "concept" && it.subject == subject }
        conceptPool = applyQuizFilters(this, allConcepts, reviewOnly)
            .filter { extractKeywordCandidates(it.back).size >= 1 }
        keywordPool = conceptPool.flatMap { extractKeywordCandidates(it.back) }.distinct()

        val outlineSourceConcepts = applyQuizFilters(this, allConcepts, reviewOnly)
        outlineGroups = buildOutlineGroups(outlineSourceConcepts)
        outlineLabelPool = outlineGroups.flatMap { it.siblingLabels }.distinct()
        sentenceItems = buildSentenceQuizItems(outlineSourceConcepts)

        val types = mutableListOf<QType>()
        if (basePool.size >= 4) types.add(QType.CHOICE)
        if (basePool.size >= 2) types.add(QType.OX)
        if (basePool.distinctBy { it.topicTitle }.size >= 4) types.add(QType.REVERSE)
        if (orderablePool.isNotEmpty()) types.add(QType.ORDER)
        if (orderablePool.isNotEmpty() && allTokens.size >= 4) types.add(QType.FILLBLANK)
        if (basePool.size >= 5) types.add(QType.MATCH)
        if (conceptPool.isNotEmpty() && keywordPool.size >= 4) types.add(QType.KEYWORD)
        if (outlineGroups.isNotEmpty()) types.add(QType.OUTLINE)
        if (sentenceItems.isNotEmpty()) types.add(QType.SENTENCE)
        eligibleTypes = types

        if (eligibleTypes.isEmpty()) {
            val msg = if (reviewOnly) "복습할 오답 카드가 부족해요" else "학습 카드가 부족해서 퀴즈를 만들 수 없어요"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            finish()
            return false
        }

        val roundCount = 10
        val seq = mutableListOf<QType>()
        var last: QType? = null
        repeat(roundCount) {
            val candidates = if (eligibleTypes.size > 1) eligibleTypes.filter { it != last } else eligibleTypes
            val picked = candidates.random()
            seq.add(picked)
            last = picked
        }
        roundTypes = seq
        roundIndex = 0
        totalPoints = 0.0
        totalPossible = 0.0
        return true
    }

    /** 본문에서 빈칸 후보로 쓸 만한 핵심 단어를 뽑아낸다 (아주 단순한 규칙 기반) */
    private val keywordStopwords = setOf(
        "은", "는", "이", "가", "을", "를", "의", "에", "에서", "으로", "로", "와", "과", "도", "만",
        "보다", "라고", "하다", "있다", "없다", "것", "등", "및", "그", "이러한", "한다", "때", "경우",
        "위해", "통해", "대한", "대해", "따라", "또는", "그리고", "하며", "하고", "된다", "한다.", "있다."
    )

    private fun extractKeywordCandidates(body: String): List<String> {
        val reflowed = reflowBody(body)
        return reflowed.split(Regex("[\\s]+"))
            .map { it.trim().trim('.', ',', ')', '(', '·', '-', ':', '·') }
            .filter { it.length in 2..8 }
            .filter { !Regex("^[0-9]+$").matches(it) }
            .filter { !Regex("^\\d+[.)]").matches(it) }
            .filter { it !in keywordStopwords }
            .distinct()
    }

    // ---------- 라운드 렌더링 ----------

    private fun renderRound() {
        roundAnswered = false
        container.removeAllViews()
        btnNext.isEnabled = false
        btnNext.text = if (roundIndex == roundTypes.size - 1) "결과 보기" else "다음 문제 ▶"
        tvProgress.text = "${roundIndex + 1} / ${roundTypes.size}"
        tvScore.text = "✅ ${fmtScore(totalPoints)}"
        pbProgress.max = roundTypes.size
        pbProgress.progress = roundIndex

        when (roundTypes[roundIndex]) {
            QType.CHOICE -> renderChoiceRound()
            QType.ORDER -> renderOrderRound()
            QType.OX -> renderOxRound()
            QType.FILLBLANK -> renderFillBlankRound()
            QType.REVERSE -> renderReverseRound()
            QType.MATCH -> renderMatchRound()
            QType.KEYWORD -> renderKeywordRound()
            QType.OUTLINE -> renderOutlineRound()
            QType.SENTENCE -> renderSentenceRound()
        }
    }

    private fun fmtScore(d: Double): String =
        if (d == Math.floor(d)) d.toInt().toString() else String.format("%.1f", d)

    /** 라운드 상단에 붙는 작은 배지 형태의 유형 라벨 */
    private fun sectionLabel(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, R.color.primary))
        tv.textSize = 12.5f
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.background = pill(Color.parseColor("#1F2962FF"), Color.TRANSPARENT, strokeWidthDp = 0, radiusDp = 20)
        tv.setPadding(dp(14), dp(6), dp(14), dp(6))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        tv.layoutParams = lp
        return tv
    }

    private fun questionCard(text: String): CardView {
        val cv = CardView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(22)
        cv.layoutParams = lp
        cv.radius = dp(20).toFloat()
        cv.cardElevation = dp(2).toFloat()
        cv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        tv.textSize = 17f
        tv.gravity = Gravity.CENTER
        tv.setLineSpacing(dp(4).toFloat(), 1f)
        tv.setPadding(dp(24), dp(28), dp(24), dp(28))
        cv.addView(tv)
        return cv
    }

    private fun choiceButton(text: String, onClick: (Button) -> Unit): Button {
        val b = Button(this)
        b.text = text
        styleOptionButton(b)
        b.setOnClickListener { onClick(b) }
        return b
    }

    private fun finishRound(correct: Boolean, cardIds: List<String>) {
        finishRoundPoints(if (correct) 1.0 else 0.0, 1.0, cardIds)
    }

    private fun finishRoundPoints(earned: Double, possible: Double, cardIds: List<String>) {
        roundAnswered = true
        totalPoints += earned
        totalPossible += possible
        val mastered = earned >= possible - 0.0001
        cardIds.forEach { if (mastered) CardStore.removeWrong(this, it) else CardStore.addWrong(this, it) }
        tvScore.text = "✅ ${fmtScore(totalPoints)}"
        pbProgress.progress = roundIndex + 1
        btnNext.isEnabled = true
    }

    // ---------- 객관식 (주제 -> 두문자) ----------
    private fun renderChoiceRound() {
        val target = weightedRandomCard(this, basePool)
        currentRoundCards = listOf(target)
        container.addView(sectionLabel("📝 객관식"))
        container.addView(questionCard("[${target.topicTitle}]\n이 주제의 두문자는?"))

        val wrong = basePool.filter { it.mnemonic != target.mnemonic }.shuffled().take(3)
        val choices = (wrong.map { it.mnemonic } + target.mnemonic).shuffled()
        val buttons = mutableListOf<Button>()
        choices.forEach { choiceText ->
            val b = choiceButton(choiceText) { pressed ->
                if (roundAnswered) return@choiceButton
                val isCorrect = choiceText == target.mnemonic
                buttons.forEach { btn ->
                    when {
                        btn.text == target.mnemonic -> markCorrect(btn)
                        btn === pressed && !isCorrect -> markWrong(btn)
                    }
                    btn.isEnabled = false
                }
                finishRound(isCorrect, listOf(target.id))
            }
            buttons.add(b)
            container.addView(b)
        }
    }

    // ---------- 순서 배열 ----------
    private fun renderOrderRound() {
        val target = weightedRandomCard(this, orderablePool)
        currentRoundCards = listOf(target)
        val tokens = target.mnemonic.split(".").map { it.trim() }.filter { it.isNotEmpty() }
        var shuffled: List<String>
        do { shuffled = tokens.shuffled() } while (tokens.size > 1 && shuffled == tokens)

        container.addView(sectionLabel("🔤 순서 배열"))
        container.addView(questionCard("[${target.topicTitle}]\n두문자를 순서대로 탭하세요"))

        val answerCard = CardView(this)
        val alp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        alp.bottomMargin = dp(10)
        answerCard.layoutParams = alp
        answerCard.radius = dp(18).toFloat()
        answerCard.cardElevation = 0f
        answerCard.setCardBackgroundColor(Color.parseColor("#1F2962FF"))
        val tvAnswer = TextView(this)
        tvAnswer.text = "탭한 글자가 여기 표시돼요"
        tvAnswer.gravity = Gravity.CENTER
        tvAnswer.setPadding(dp(20), dp(22), dp(20), dp(22))
        tvAnswer.setTextColor(ContextCompat.getColor(this, R.color.primary))
        tvAnswer.textSize = 19f
        tvAnswer.setTypeface(tvAnswer.typeface, android.graphics.Typeface.BOLD)
        answerCard.addView(tvAnswer)
        container.addView(answerCard)

        val tvReveal = TextView(this)
        tvReveal.gravity = Gravity.CENTER
        tvReveal.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
        tvReveal.textSize = 12f
        tvReveal.setPadding(0, dp(8), 0, dp(14))
        container.addView(tvReveal)

        val grid = GridLayout(this)
        grid.columnCount = 4
        grid.useDefaultMargins = false
        container.addView(grid)

        val userSeq = mutableListOf<String>()
        val used = mutableSetOf<Int>()
        val tileButtons = mutableListOf<Button>()

        shuffled.forEachIndexed { idx, token ->
            val b = Button(this)
            val glp = GridLayout.LayoutParams()
            glp.width = 0
            glp.height = GridLayout.LayoutParams.WRAP_CONTENT
            glp.columnSpec = GridLayout.spec(idx % 4, 1f)
            glp.setMargins(dp(6), dp(6), dp(6), dp(6))
            b.layoutParams = glp
            b.text = token
            b.textSize = 19f
            b.isAllCaps = false
            b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
            b.background = pill(defaultFill, defaultStroke, radiusDp = 14)
            b.setTextColor(defaultText)
            b.elevation = 0f
            b.setPadding(0, dp(10), 0, dp(10))
            b.minHeight = dp(64)
            b.setOnClickListener {
                if (roundAnswered || idx in used) return@setOnClickListener
                used.add(idx)
                userSeq.add(token)
                b.isEnabled = false
                b.background = pill(Color.parseColor("#33808080"), Color.TRANSPARENT, strokeWidthDp = 0, radiusDp = 14)
                tvAnswer.text = userSeq.joinToString(" . ")
                if (userSeq.size == tokens.size) {
                    val correct = userSeq == tokens
                    if (correct) {
                        answerCard.setCardBackgroundColor(colorCorrect)
                        tvAnswer.setTextColor(colorWhite)
                    } else {
                        answerCard.setCardBackgroundColor(colorWrong)
                        tvAnswer.setTextColor(colorWhite)
                        tvReveal.text = "정답: ${tokens.joinToString(" . ")}"
                    }
                    finishRound(correct, listOf(target.id))
                }
            }
            tileButtons.add(b)
            grid.addView(b)
        }
    }

    // ---------- OX ----------
    private fun renderOxRound() {
        val target = weightedRandomCard(this, basePool)
        currentRoundCards = listOf(target)
        val isTrue = (0..1).random() == 0
        val shown = if (isTrue) target.mnemonic else basePool.filter { it.mnemonic != target.mnemonic }.random().mnemonic

        container.addView(sectionLabel("⭕❌ OX 퀴즈"))
        container.addView(questionCard("[${target.topicTitle}]\n이 주제의 두문자는 '$shown' 이다."))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val rowLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        row.layoutParams = rowLp

        val trueBtn = Button(this)
        trueBtn.text = "⭕ 참"
        val falseBtn = Button(this)
        falseBtn.text = "❌ 거짓"
        for (b in listOf(trueBtn, falseBtn)) {
            b.textSize = 18f
            b.minHeight = dp(76)
            b.setPadding(dp(8), dp(14), dp(8), dp(14))
            b.isAllCaps = false
            b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
            b.background = pill(defaultFill, defaultStroke, radiusDp = 18)
            b.setTextColor(defaultText)
            b.elevation = 0f
        }
        val lpT = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); lpT.marginEnd = dp(6)
        val lpF = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); lpF.marginStart = dp(6)
        trueBtn.layoutParams = lpT
        falseBtn.layoutParams = lpF
        row.addView(trueBtn); row.addView(falseBtn)
        container.addView(row)

        fun answer(chooseTrue: Boolean, pressed: Button) {
            if (roundAnswered) return
            val correct = chooseTrue == isTrue
            trueBtn.isEnabled = false; falseBtn.isEnabled = false
            if (correct) {
                markCorrect(pressed)
            } else {
                markWrong(pressed)
                markCorrect(if (isTrue) trueBtn else falseBtn)
            }
            finishRound(correct, listOf(target.id))
        }
        trueBtn.setOnClickListener { answer(true, trueBtn) }
        falseBtn.setOnClickListener { answer(false, falseBtn) }
    }

    // ---------- 빈칸 채우기 ----------
    private fun renderFillBlankRound() {
        val target = weightedRandomCard(this, orderablePool)
        currentRoundCards = listOf(target)
        val tokens = target.mnemonic.split(".").map { it.trim() }.filter { it.isNotEmpty() }
        val blankIdx = tokens.indices.random()
        val correctToken = tokens[blankIdx]
        val displayed = tokens.mapIndexed { i, t -> if (i == blankIdx) "＿" else t }.joinToString(".")

        container.addView(sectionLabel("✏️ 빈칸 채우기"))
        container.addView(questionCard("[${target.topicTitle}]\n두문자: $displayed\n빈칸에 들어갈 글자는?"))

        val distractors = allTokens.filter { it != correctToken }.shuffled().take(3)
        val choices = (distractors + correctToken).shuffled()
        val buttons = mutableListOf<Button>()
        choices.forEach { choiceText ->
            val b = choiceButton(choiceText) { pressed ->
                if (roundAnswered) return@choiceButton
                val correct = choiceText == correctToken
                buttons.forEach { btn ->
                    when {
                        btn.text == correctToken -> markCorrect(btn)
                        btn === pressed && !correct -> markWrong(btn)
                    }
                    btn.isEnabled = false
                }
                finishRound(correct, listOf(target.id))
            }
            buttons.add(b)
            container.addView(b)
        }
    }

    // ---------- 거꾸로 (두문자 -> 주제) ----------
    private fun renderReverseRound() {
        val topicPool = basePool.distinctBy { it.topicTitle }
        val target = weightedRandomCard(this, topicPool)
        currentRoundCards = listOf(target)
        container.addView(sectionLabel("🔄 두문자로 주제 맞히기"))
        container.addView(questionCard("두문자: ${target.mnemonic}\n이 두문자가 가리키는 주제는?"))

        val wrong = topicPool.filter { it.topicTitle != target.topicTitle }.shuffled().take(3)
        val choices = (wrong.map { it.topicTitle } + target.topicTitle).shuffled()
        val buttons = mutableListOf<Button>()
        choices.forEach { choiceText ->
            val b = choiceButton(choiceText) { pressed ->
                if (roundAnswered) return@choiceButton
                val correct = choiceText == target.topicTitle
                buttons.forEach { btn ->
                    when {
                        btn.text == target.topicTitle -> markCorrect(btn)
                        btn === pressed && !correct -> markWrong(btn)
                    }
                    btn.isEnabled = false
                }
                finishRound(correct, listOf(target.id))
            }
            b.textSize = 14f
            buttons.add(b)
            container.addView(b)
        }
    }

    // ---------- 매칭 ----------
    private fun renderMatchRound() {
        val cards5 = basePool.shuffled().take(5)
        currentRoundCards = cards5
        container.addView(sectionLabel("🧩 매칭 (왼쪽 → 오른쪽 순서로 탭)"))

        val leftOrder = cards5.indices.shuffled()
        val rightOrder = cards5.indices.shuffled()
        val matchedLeft = mutableSetOf<Int>()
        val matchedRight = mutableSetOf<Int>()
        var selectedLeftPos: Int? = null

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val leftCol = LinearLayout(this); leftCol.orientation = LinearLayout.VERTICAL
        val rightCol = LinearLayout(this); rightCol.orientation = LinearLayout.VERTICAL
        val leftLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); leftLp.marginEnd = dp(6)
        val rightLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); rightLp.marginStart = dp(6)
        leftCol.layoutParams = leftLp
        rightCol.layoutParams = rightLp
        row.addView(leftCol); row.addView(rightCol)
        container.addView(row)

        fun tileBg(fill: Int, stroke: Int) = pill(fill, stroke, radiusDp = 16)

        fun makeTile(text: String): CardView {
            val cv = CardView(this)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6); lp.bottomMargin = dp(6)
            cv.layoutParams = lp
            cv.minimumHeight = dp(60)
            cv.radius = dp(16).toFloat()
            cv.cardElevation = 0f
            cv.setCardBackgroundColor(defaultFill)
            val tv = TextView(this)
            tv.text = text
            tv.textSize = 13f
            tv.isAllCaps = false
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setTextColor(defaultText)
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(12), dp(10), dp(12), dp(10))
            cv.addView(tv)
            return cv
        }

        val leftViews = mutableListOf<CardView>()
        val rightViews = mutableListOf<CardView>()

        leftOrder.forEachIndexed { pos, cardIdx ->
            val tile = makeTile(cards5[cardIdx].topicTitle)
            tile.setOnClickListener {
                if (cardIdx in matchedLeft) return@setOnClickListener
                selectedLeftPos = pos
                leftViews.forEachIndexed { i, v -> v.setCardBackgroundColor(if (i == pos) colorSelected else defaultFill) }
            }
            leftCol.addView(tile)
            leftViews.add(tile)
        }
        rightOrder.forEachIndexed { pos, cardIdx ->
            val tile = makeTile(cards5[cardIdx].mnemonic)
            tile.setOnClickListener {
                val rightCardIdx = cardIdx
                if (rightCardIdx in matchedRight) return@setOnClickListener
                val leftPos = selectedLeftPos
                if (leftPos == null) {
                    Toast.makeText(this, "먼저 왼쪽 주제를 골라주세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val leftCardIdx = leftOrder[leftPos]
                if (leftCardIdx == rightCardIdx) {
                    matchedLeft.add(leftCardIdx); matchedRight.add(rightCardIdx)
                    leftViews[leftPos].setCardBackgroundColor(colorCorrect)
                    tile.setCardBackgroundColor(colorCorrect)
                    CardStore.removeWrong(this, cards5[leftCardIdx].id)
                    selectedLeftPos = null
                    if (matchedLeft.size == cards5.size) {
                        finishRound(true, cards5.map { it.id })
                    }
                } else {
                    CardStore.addWrong(this, cards5[leftCardIdx].id)
                    CardStore.addWrong(this, cards5[rightCardIdx].id)
                    tile.setCardBackgroundColor(colorWrong)
                    leftViews[leftPos].setCardBackgroundColor(colorWrong)
                    handler.postDelayed({
                        if (leftCardIdx !in matchedLeft) leftViews[leftPos].setCardBackgroundColor(defaultFill)
                        if (rightCardIdx !in matchedRight) tile.setCardBackgroundColor(defaultFill)
                    }, 400)
                    selectedLeftPos = null
                }
            }
            rightCol.addView(tile)
            rightViews.add(tile)
        }
    }

    // ---------- 키워드 맞추기 ----------
    private fun renderKeywordRound() {
        val target = weightedRandomCard(this, conceptPool)
        currentRoundCards = listOf(target)
        val candidates = extractKeywordCandidates(target.back).shuffled()
        val blankCount = (1..3).random().coerceAtMost(candidates.size)
        val blanks = candidates.take(blankCount)

        var displayBody = reflowBody(target.back)
        blanks.forEachIndexed { i, word ->
            displayBody = displayBody.replaceFirst(word, "【${i + 1}】")
        }

        container.addView(sectionLabel("🔑 키워드 맞추기"))
        container.addView(questionCard("[${target.topicTitle}]"))

        val bodyCard = CardView(this)
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.bottomMargin = dp(18)
        bodyCard.layoutParams = blp
        bodyCard.radius = dp(16).toFloat()
        bodyCard.cardElevation = dp(1).toFloat()
        bodyCard.setCardBackgroundColor(defaultFill)
        val tvBody = TextView(this)
        tvBody.text = displayBody
        tvBody.setTextColor(defaultText)
        tvBody.textSize = 14.5f
        tvBody.setLineSpacing(dp(4).toFloat(), 1f)
        tvBody.setPadding(dp(18), dp(18), dp(18), dp(18))
        bodyCard.addView(tvBody)
        container.addView(bodyCard)

        if (blanks.size == 1) {
            renderKeywordSubjective(target, blanks[0])
        } else {
            renderKeywordMultiChoice(target, blanks)
        }
    }

    private fun renderKeywordMultiChoice(target: Card, blanks: List<String>) {
        var correctSubCount = 0
        val totalBlanks = blanks.size
        val allAnswered = BooleanArray(totalBlanks)

        blanks.forEachIndexed { blankIdx, correctWord ->
            val label = TextView(this)
            label.text = "빈칸 【${blankIdx + 1}】에 들어갈 단어는?"
            label.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
            label.textSize = 12.5f
            label.setPadding(0, dp(4), 0, dp(6))
            container.addView(label)

            val distractors = keywordPool.filter { it != correctWord && it !in blanks }.shuffled().take(3)
            val choices = (distractors + correctWord).shuffled()
            val buttons = mutableListOf<Button>()
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            choices.forEach { choiceText ->
                val b = choiceButton(choiceText) { pressed ->
                    if (roundAnswered || allAnswered[blankIdx]) return@choiceButton
                    allAnswered[blankIdx] = true
                    val isCorrect = choiceText == correctWord
                    if (isCorrect) correctSubCount++
                    buttons.forEach { btn ->
                        when {
                            btn.text == correctWord -> markCorrect(btn)
                            btn === pressed && !isCorrect -> markWrong(btn)
                        }
                        btn.isEnabled = false
                    }
                    if (allAnswered.all { it }) {
                        finishRoundPoints(correctSubCount.toDouble(), totalBlanks.toDouble(), listOf(target.id))
                    }
                }
                buttons.add(b)
                row.addView(b)
            }
            container.addView(row)
        }
    }

    private fun renderKeywordSubjective(target: Card, correctWord: String) {
        val label = TextView(this)
        label.text = "【1】에 들어갈 단어를 입력하세요"
        label.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
        label.textSize = 12.5f
        label.setPadding(0, dp(4), 0, dp(8))
        container.addView(label)

        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        val et = EditText(this)
        et.hint = "정답 입력"
        val etLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        et.layoutParams = etLp
        et.setTextColor(defaultText)

        val btnVoice = Button(this)
        btnVoice.text = "🎤"
        btnVoice.minWidth = dp(56)
        btnVoice.minHeight = dp(56)
        val vlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); vlp.marginStart = dp(8)
        btnVoice.layoutParams = vlp
        btnVoice.background = pill(defaultFill, defaultStroke, radiusDp = 14)
        btnVoice.setOnClickListener { startVoiceInput(et) }

        inputRow.addView(et)
        inputRow.addView(btnVoice)
        container.addView(inputRow)

        val tvFeedback = TextView(this)
        tvFeedback.textSize = 13f
        tvFeedback.setPadding(0, dp(8), 0, dp(8))
        container.addView(tvFeedback)

        val btnSubmit = Button(this)
        btnSubmit.text = "제출하기"
        styleOptionButton(btnSubmit)
        btnSubmit.background = pill(ContextCompat.getColor(this, R.color.primary), ContextCompat.getColor(this, R.color.primary))
        btnSubmit.setTextColor(Color.WHITE)
        btnSubmit.setOnClickListener {
            if (roundAnswered) return@setOnClickListener
            val answer = et.text.toString().trim()
            if (answer.isEmpty()) {
                Toast.makeText(this, "답을 입력하거나 음성으로 말해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isCorrect = answer.replace(" ", "") == correctWord.replace(" ", "")
            if (isCorrect) {
                tvFeedback.text = "✅ 정답이에요!"
                tvFeedback.setTextColor(colorCorrect)
            } else {
                tvFeedback.text = "❌ 정답: $correctWord"
                tvFeedback.setTextColor(colorWrong)
            }
            et.isEnabled = false
            btnVoice.isEnabled = false
            finishRoundPoints(if (isCorrect) 1.0 else 0.0, 1.0, listOf(target.id))
        }
        container.addView(btnSubmit)
    }

    private fun startVoiceInput(target: EditText) {
        voiceTargetEditText = target
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE)
        } catch (e: Exception) {
            Toast.makeText(this, "이 기기에서는 음성 입력을 사용할 수 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = results?.firstOrNull() ?: return
            voiceTargetEditText?.setText(text)
        }
    }

    // ---------- 목차퀴즈 ----------
    private fun renderOutlineRound() {
        val group = outlineGroups.random()
        currentRoundCards = listOfNotNull(
            basePool.firstOrNull { it.topicTitle == group.topicTitle }
                ?: conceptPool.firstOrNull { it.id == group.cardId }
        )

        val useTypeB = (0..1).random() == 0
        val questionText: String
        var correctInOrder: List<String>

        if (useTypeB) {
            correctInOrder = group.siblingLabels
            if (correctInOrder.size > 6) {
                val picked = correctInOrder.indices.shuffled().take(6).sorted()
                correctInOrder = picked.map { correctInOrder[it] }
            }
            questionText = "[${group.parentLabel}]\n이 목차의 하위 항목을 순서대로 고르세요 (${correctInOrder.size}개)"
        } else {
            val target = group.siblingLabels.random()
            var rest = group.siblingLabels.filter { it != target }
            if (rest.size > 6) {
                val picked = rest.indices.shuffled().take(6).sorted()
                rest = picked.map { rest[it] }
            }
            correctInOrder = rest
            questionText = "[${group.parentLabel}]\n'$target' 와(과) 대등한 목차를 순서대로 고르세요 (${correctInOrder.size}개)"
        }

        container.addView(sectionLabel("📚 목차퀴즈"))
        container.addView(questionCard(questionText))

        val neededDistractors = correctInOrder.size.coerceAtLeast(1)
        var distractors = outlineLabelPool.filter { it !in group.siblingLabels }.shuffled().take(neededDistractors)
        if (distractors.size < neededDistractors) {
            val more = outlineLabelPool.filter { it !in correctInOrder && it !in distractors }.shuffled()
            distractors = distractors + more.take(neededDistractors - distractors.size)
        }
        val allChoices = (correctInOrder + distractors).shuffled()

        val tvSelected = TextView(this)
        tvSelected.text = "선택 순서: (없음)"
        tvSelected.textSize = 12.5f
        tvSelected.setTextColor(ContextCompat.getColor(this, R.color.primary))
        tvSelected.setPadding(0, 0, 0, dp(10))
        container.addView(tvSelected)

        val selected = mutableListOf<String>()
        val buttons = mutableListOf<Button>()

        fun updateSelectedText() {
            tvSelected.text = if (selected.isEmpty()) "선택 순서: (없음)"
            else "선택 순서: " + selected.mapIndexed { i, s -> "${i + 1})${s.take(10)}" }.joinToString("  ")
        }

        allChoices.forEach { label ->
            val b = choiceButton(label) { pressed ->
                if (roundAnswered) return@choiceButton
                if (label in selected) {
                    selected.remove(label)
                    pressed.background = pill(defaultFill, defaultStroke)
                } else {
                    selected.add(label)
                    pressed.background = pill(colorSelected, ContextCompat.getColor(this, R.color.primary))
                }
                updateSelectedText()
            }
            buttons.add(b)
            container.addView(b)
        }

        val btnSubmit = Button(this)
        btnSubmit.text = "제출하기"
        styleOptionButton(btnSubmit)
        btnSubmit.background = pill(ContextCompat.getColor(this, R.color.primary), ContextCompat.getColor(this, R.color.primary))
        btnSubmit.setTextColor(Color.WHITE)
        btnSubmit.setOnClickListener {
            if (roundAnswered) return@setOnClickListener
            if (selected.isEmpty()) {
                Toast.makeText(this, "최소 1개 이상 선택해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val correctSet = correctInOrder.toSet()
            val overlap = selected.count { it in correctSet }
            val exactMatch = selected == correctInOrder
            val earned = overlap.toDouble() + (if (exactMatch) 1.0 else 0.0)
            val possible = correctInOrder.size.toDouble() + 1.0

            buttons.forEach { btn ->
                val label = btn.text.toString()
                when {
                    label in correctSet && label in selected -> markCorrect(btn)
                    label in correctSet && label !in selected -> btn.background = pill(Color.parseColor("#553D2F00"), Color.parseColor("#FFC107"))
                    label !in correctSet && label in selected -> markWrong(btn)
                }
                btn.isEnabled = false
            }
            tvSelected.text = if (exactMatch) "🎉 순서까지 완벽! (+${possible.toInt()}점)"
                               else "✅ $overlap/${correctInOrder.size}개 맞음 (순서 보너스는 놓쳤어요)"
            currentRoundCards.forEach {} // no-op, id 재사용을 위해 유지
            val cardId = currentRoundCards.firstOrNull()?.id
            finishRoundPoints(earned, possible, listOfNotNull(cardId))
        }
        container.addView(btnSubmit)
    }

    // ---------- 문장→목차 찾기 ----------
    private fun renderSentenceRound() {
        val item = sentenceItems.random()
        currentRoundCards = listOfNotNull(
            conceptPool.firstOrNull { it.id == item.cardId }
                ?: basePool.firstOrNull { it.topicTitle == item.topicTitle }
        )

        container.addView(sectionLabel("🧭 문장→목차 찾기"))
        container.addView(questionCard("[${item.parentLabel}]\n다음 문장은 어느 목차에 속할까요?\n\n“${item.sentence}”"))

        val choices = item.siblingLabels.shuffled()
        val buttons = mutableListOf<Button>()
        choices.forEach { label ->
            val b = choiceButton(label) { pressed ->
                if (roundAnswered) return@choiceButton
                val correct = label == item.correctLabel
                buttons.forEach { btn ->
                    when {
                        btn.text == item.correctLabel -> markCorrect(btn)
                        btn === pressed && !correct -> markWrong(btn)
                    }
                    btn.isEnabled = false
                }
                val cardId = currentRoundCards.firstOrNull()?.id
                finishRoundPoints(if (correct) 1.0 else 0.0, 1.0, listOfNotNull(cardId))
            }
            b.textSize = 14f
            buttons.add(b)
            container.addView(b)
        }
    }

    // ---------- 본문 보기 / 결과 ----------

    private fun showContextDialog() {
        if (currentRoundCards.isEmpty()) return
        val text = currentRoundCards.joinToString("\n\n━━━━━━━━━━\n\n") { c ->
            val mnemonicLine = if (c.mnemonic.isNotBlank()) "두문자: ${c.mnemonic}\n\n" else ""
            "[${c.topicTitle}]\n$mnemonicLine${c.back.ifBlank { "본문 내용이 없어요" }}"
        }
        val scroll = android.widget.ScrollView(this)
        val tv = TextView(this)
        tv.text = text
        tv.setPadding(dp(20), dp(16), dp(20), dp(16))
        tv.textSize = 14f
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("본문 보기")
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun showResultDialog() {
        AlertDialog.Builder(this)
            .setTitle("퀴즈 결과")
            .setMessage("총 ${fmtScore(totalPoints)} / ${fmtScore(totalPossible)}점을 받았어요!\n(문제 유형에 따라 만점이 달라요 — 목차퀴즈나 키워드 맞추기는 한 문제에 여러 점수가 걸려있어요)")
            .setPositiveButton("다시 풀기") { _, _ -> if (buildSession()) renderRound() }
            .setNegativeButton("닫기") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
