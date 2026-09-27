package com.example.adminmemo

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 학습퀴즈 통합 화면.
 * 매 라운드(문제)마다 비두문자 유형(키워드/목차퀴즈/문장목차찾기/사례논점/목차퍼즐) 중
 * 하나를 무작위로 고르되, 바로 이전 라운드와 같은 유형은 피한다.
 * 듀오링고 스타일: 두꺼운 알약형 버튼, 상단 진행바, 하단 고정 확인 버튼.
 */
class QuizSessionActivity : BaseActivity() {

    private enum class QType { KEYWORD, OUTLINE, SENTENCE, CASE_ISSUE, OUTLINE_PUZZLE }

    private lateinit var subject: String
    private var reviewOnly = false

    private var basePool: List<Card> = emptyList()
    private var conceptPool: List<Card> = emptyList()
    private var keywordPool: List<String> = emptyList()
    private var outlineGroups: List<OutlineGroup> = emptyList()
    private var outlineLabelPool: List<String> = emptyList()
    private var sentenceItems: List<SentenceQuizItem> = emptyList()
    private var eligibleTypes: List<QType> = emptyList()

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
        try {
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
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "퀴즈 로딩 오류: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("퀴즈 나가기")
            .setMessage("퀴즈를 그만두고 나가시겠어요? 진행 중인 점수는 저장되지 않아요.")
            .setPositiveButton("나가기") { _, _ -> super.onBackPressed() }
            .setNegativeButton("계속 풀기", null)
            .show()
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
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.background = pill(defaultFill, defaultStroke)
        b.backgroundTintList = null
        b.setTextColor(defaultText)
        b.elevation = 0f
        b.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6); lp.bottomMargin = dp(6)
        b.layoutParams = lp
    }

    private fun markCorrect(b: Button) {
        b.background = pill(colorCorrect, colorCorrect)
        b.backgroundTintList = null
        b.setTextColor(colorWhite)
    }

    private fun markWrong(b: Button) {
        b.background = pill(colorWrong, colorWrong)
        b.backgroundTintList = null
        b.setTextColor(colorWhite)
    }

    // ---------- 세션 구성 ----------

    private fun buildSession(): Boolean {
        try {
            val allCards = CardStore.getAllCards(this) ?: emptyList()
            basePool = if (allCards.isNotEmpty()) allCards else listOf(Card(id = "dummy", type = "concept", subject = "기본", title = "기본", topicTitle = "기본 주제", grade = "A+", front = "내용", back = "내용", mnemonics = emptyList()))
            conceptPool = basePool.filter { it.type == "concept" }.ifEmpty { basePool }
            outlineGroups = buildOutlineGroups(conceptPool)
            outlineLabelPool = outlineGroups.flatMap { it.siblingLabels }
            keywordPool = conceptPool.flatMap { extractKeywordCandidates(it.back) }.distinct()
            sentenceItems = buildSentenceQuizItems(conceptPool)

            val types = mutableListOf<QType>()
            types.add(QType.KEYWORD)
            types.add(QType.OUTLINE)
            types.add(QType.SENTENCE)
            types.add(QType.CASE_ISSUE)
            types.add(QType.OUTLINE_PUZZLE)
            eligibleTypes = types

            val roundCount = 10
            val seq = mutableListOf<QType>()
            var last: QType? = null
            repeat(roundCount) {
                val candidates = if (eligibleTypes.size > 1) eligibleTypes.filter { it != last } else eligibleTypes
                val picked = if (candidates.isNotEmpty()) candidates.random() else QType.KEYWORD
                seq.add(picked)
                last = picked
            }
            roundTypes = seq
            roundIndex = 0
            totalPoints = 0.0
            totalPossible = 0.0
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            roundTypes = List(10) { QType.KEYWORD }
            roundIndex = 0
            totalPoints = 0.0
            totalPossible = 0.0
            return true
        }
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
            QType.KEYWORD -> renderKeywordRound()
            QType.OUTLINE -> renderOutlineRound()
            QType.SENTENCE -> renderSentenceRound()
            QType.CASE_ISSUE -> renderCaseIssueRound()
            QType.OUTLINE_PUZZLE -> renderOutlinePuzzleRound()
        }
    }

    private fun renderOutlinePuzzleRound() {
        val groups = buildOutlineGroups(conceptPool).filter { it.siblingLabels.size >= 3 }
        val group = if (groups.isNotEmpty()) groups.randomOrNull() else buildOutlineGroups(conceptPool).randomOrNull()
        if (group == null || group.siblingLabels.size < 2) {
            renderKeywordRound()
            return
        }

        val targetCard = basePool.firstOrNull { it.id == group.cardId } ?: conceptPool.randomOrNull()
        if (targetCard == null) {
            renderKeywordRound()
            return
        }
        currentRoundCards = listOf(targetCard)

        val correctOrder = group.siblingLabels.map { cleanOutlineLabel(it) }.filter { it.isNotBlank() }.take(6)
        if (correctOrder.size < 2) {
            renderKeywordRound()
            return
        }

        var shuffledOrder: List<String>
        do {
            shuffledOrder = correctOrder.shuffled()
        } while (correctOrder.size > 1 && shuffledOrder == correctOrder)

        container.addView(sectionLabel("🧩 목차 퍼즐 (동일 수준 뼈대 정렬)"))
        container.addView(questionCard("[${group.parentLabel}]\n\n동일한 수준의 아래 목차 블록들을 드래그하여 올바른 순서대로 정렬하세요"))

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@QuizSessionActivity)
            isNestedScrollingEnabled = false
        }
        val puzzleAdapter = OutlinePuzzleAdapter(shuffledOrder.toMutableList())
        rv.adapter = puzzleAdapter

        val touchCallback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                puzzleAdapter.moveItem(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        ItemTouchHelper(touchCallback).attachToRecyclerView(rv)

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        rv.layoutParams = lp
        container.addView(rv)

        val btnSubmit = Button(this)
        btnSubmit.text = "제출하기"
        styleOptionButton(btnSubmit)
        btnSubmit.background = pill(ContextCompat.getColor(this, R.color.primary), ContextCompat.getColor(this, R.color.primary))
        btnSubmit.setTextColor(Color.WHITE)
        btnSubmit.setOnClickListener {
            if (roundAnswered) return@setOnClickListener
            val userOrder = puzzleAdapter.currentList()
            val correct = userOrder == correctOrder

            val feedbackTv = TextView(this).apply {
                textSize = 14f
                setPadding(0, dp(8), 0, dp(8))
                gravity = Gravity.CENTER
                text = if (correct) "🎉 완벽한 답안 설계도 완성!" else "❌ 정답 순서:\n" + correctOrder.joinToString(" ➔ ") { "• $it" }
                setTextColor(if (correct) colorCorrect else colorWrong)
            }
            container.addView(feedbackTv)
            btnSubmit.isEnabled = false
            finishRound(correct, listOf(targetCard.id))
        }
        container.addView(btnSubmit)
    }

    private class OutlinePuzzleAdapter(
        private val items: MutableList<String>
    ) : RecyclerView.Adapter<OutlinePuzzleAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvText: TextView = v.findViewById(R.id.tvRowTitle)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_content_row, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tvText.text = "☰  ${items[position]}"
            holder.itemView.findViewById<View>(R.id.tvRowSubtitle)?.visibility = View.GONE
        }
        override fun getItemCount() = items.size
        fun moveItem(from: Int, to: Int) {
            if (from == to || from !in items.indices || to !in items.indices) return
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
        }
        fun currentList(): List<String> = items
    }

    private fun renderCaseIssueRound() {
        val allMock = MockExamRepository.loadMockExams(this)
        val matchMock = allMock.filter { subject == ALL_SUBJECTS_KEY || it.subject == subject || subject.contains(it.subject) || it.subject.contains(subject) }
        val casePool = if (matchMock.any { it.issues.isNotEmpty() }) matchMock.filter { it.issues.isNotEmpty() } else allMock.filter { it.issues.isNotEmpty() }

        if (casePool.isEmpty()) {
            renderOutlineRound()
            return
        }
        val exam = casePool.randomOrNull()
        if (exam == null || exam.issues.isEmpty()) {
            renderOutlineRound()
            return
        }
        val correctIssue = exam.issues.random()

        val allOtherIssues = allMock.flatMap { it.issues }.filter { it != correctIssue }.distinct()
        val distractors = allOtherIssues.shuffled().take(3)
        val choices = (distractors + correctIssue).shuffled()

        container.addView(sectionLabel("⚖️ 사례 논점 맞히기"))
        val snippet = if (exam.question.length > 120) exam.question.take(120) + "..." else exam.question
        container.addView(questionCard("[${exam.title}]\n\n$snippet\n\nQ. 다음 중 이 사례의 주요 논점으로 옳은 것은?"))

        val buttons = mutableListOf<View>()
        choices.forEach { choiceText ->
            val b = choiceButton(choiceText, 13.5f) { pressed ->
                if (!roundAnswered) {
                    val correct = choiceText == correctIssue
                    buttons.forEach { btn ->
                        when {
                            getChoiceText(btn) == correctIssue -> markCorrect(btn)
                            btn === pressed && !correct -> markWrong(btn)
                        }
                        btn.isEnabled = false
                    }
                    finishRound(correct, listOf(exam.id))
                }
            }
            buttons.add(b)
            container.addView(b)
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
        tv.setTypeface(tv.typeface, Typeface.BOLD)
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

    private fun getChoiceText(view: View): String {
        return (view.tag as? String) ?: ((view as? ViewGroup)?.getChildAt(0) as? TextView)?.text?.toString() ?: (view as? Button)?.text?.toString() ?: ""
    }

    private fun choiceButton(text: String, textSize: Float = 15.5f, onClick: (View) -> Unit): View {
        val cv = CardView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        cv.layoutParams = lp
        cv.radius = dp(16).toFloat()
        cv.cardElevation = 0f
        cv.setCardBackgroundColor(defaultFill)
        cv.tag = text
        cv.isClickable = true
        cv.isFocusable = true
        cv.foreground = ContextCompat.getDrawable(this, android.R.drawable.list_selector_background)

        val tv = TextView(this)
        tv.text = text
        tv.textSize = textSize
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setTextColor(ContextCompat.getColor(this, R.color.primary)) // Blue for choices!
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(20), dp(16), dp(20), dp(16))
        cv.addView(tv)

        cv.setOnClickListener { onClick(cv) }
        return cv
    }

    private fun markCorrect(view: View) {
        if (view is CardView) {
            view.setCardBackgroundColor(colorCorrect)
            (view.getChildAt(0) as? TextView)?.setTextColor(Color.WHITE)
        } else if (view is Button) {
            view.background = pill(colorCorrect, colorCorrect)
            view.backgroundTintList = null
            view.setTextColor(Color.WHITE)
        }
    }

    private fun markWrong(view: View) {
        if (view is CardView) {
            view.setCardBackgroundColor(colorWrong)
            (view.getChildAt(0) as? TextView)?.setTextColor(Color.WHITE)
        } else if (view is Button) {
            view.background = pill(colorWrong, colorWrong)
            view.backgroundTintList = null
            view.setTextColor(Color.WHITE)
        }
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

    // ---------- 키워드 맞추기 ----------
    private fun renderKeywordRound() {
        val target = weightedRandomCard(this, conceptPool)
        currentRoundCards = listOf(target)
        val candidates = extractKeywordCandidates(target.back).filter { it.isNotBlank() }.shuffled()
        if (candidates.isEmpty()) {
            renderOutlineRound()
            return
        }
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
            val buttons = mutableListOf<View>()
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            choices.forEach { choiceText ->
                val b = choiceButton(choiceText) { pressed ->
                    if (!roundAnswered && !allAnswered[blankIdx]) {
                        allAnswered[blankIdx] = true
                        val isCorrect = choiceText == correctWord
                        if (isCorrect) correctSubCount++
                        buttons.forEach { btn ->
                            when {
                                getChoiceText(btn) == correctWord -> markCorrect(btn)
                                btn === pressed && !isCorrect -> markWrong(btn)
                            }
                            btn.isEnabled = false
                        }
                        if (allAnswered.all { it }) {
                            finishRoundPoints(correctSubCount.toDouble(), totalBlanks.toDouble(), listOf(target.id))
                        }
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
        val vlp = LinearLayout.LayoutParams(dp(56), dp(56)); vlp.marginStart = dp(8)
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
    private fun cleanOutlineLabel(label: String): String {
        return label.replace(Regex("^(\\d+[\\.\\)]\\s*|\\(\\d+\\)\\s*|[①②③④⑤⑥⑦⑧⑨⑩\\-·]\\s*)"), "").trim()
    }

    private fun renderOutlineRound() {
        if (outlineGroups.isEmpty()) {
            renderOutlinePuzzleRound()
            return
        }
        val group = outlineGroups.random()
        currentRoundCards = listOfNotNull(
            basePool.firstOrNull { it.topicTitle == group.topicTitle }
                ?: conceptPool.firstOrNull { it.id == group.cardId }
        )

        val cleanedSiblings = group.siblingLabels.map { cleanOutlineLabel(it) }.filter { it.isNotBlank() }
        if (cleanedSiblings.size < 2) {
            renderOutlinePuzzleRound()
            return
        }

        val useTypeB = (0..1).random() == 0
        val questionText: String
        var correctInOrder: List<String>

        if (useTypeB) {
            correctInOrder = cleanedSiblings
            if (correctInOrder.size > 6) {
                val picked = correctInOrder.indices.shuffled().take(6).sorted()
                correctInOrder = picked.map { correctInOrder[it] }
            }
            questionText = "[${group.parentLabel}]\n이 목차의 하위 항목을 순서대로 고르세요 (${correctInOrder.size}개)"
        } else {
            val target = cleanedSiblings.randomOrNull() ?: cleanedSiblings.firstOrNull() ?: "기본"
            var rest = cleanedSiblings.filter { it != target }
            if (rest.size > 6) {
                val picked = rest.indices.shuffled().take(6).sorted()
                rest = picked.map { rest[it] }
            }
            correctInOrder = rest
            questionText = "[${group.parentLabel}]\n'$target' 와(과) 대등한 목차를 순서대로 고르세요 (${correctInOrder.size}개)"
        }

        container.addView(sectionLabel("📚 목차퀴즈"))
        container.addView(questionCard(questionText))

        val cleanedPool = outlineLabelPool.map { cleanOutlineLabel(it) }.filter { it.isNotBlank() }
        val neededDistractors = correctInOrder.size.coerceAtLeast(1)
        var distractors = cleanedPool.filter { it !in cleanedSiblings }.shuffled().take(neededDistractors)
        if (distractors.size < neededDistractors) {
            val more = cleanedPool.filter { it !in correctInOrder && it !in distractors }.shuffled()
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
        val buttons = mutableListOf<View>()

        fun updateSelectedText() {
            tvSelected.text = if (selected.isEmpty()) "선택 순서: (없음)"
            else "선택 순서: " + selected.mapIndexed { i, s -> "${i + 1})${s.take(10)}" }.joinToString("  ")
        }

        allChoices.forEach { label ->
            val b = choiceButton(label) { pressed ->
                if (!roundAnswered) {
                    if (label in selected) {
                        selected.remove(label)
                        (pressed as? CardView)?.setCardBackgroundColor(defaultFill)
                        (pressed as? CardView)?.let { (it.getChildAt(0) as? TextView)?.setTextColor(ContextCompat.getColor(this, R.color.primary)) }
                    } else {
                        selected.add(label)
                        (pressed as? CardView)?.setCardBackgroundColor(colorSelected)
                        (pressed as? CardView)?.let { (it.getChildAt(0) as? TextView)?.setTextColor(colorWhite) }
                    }
                    updateSelectedText()
                }
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
                val label = getChoiceText(btn)
                when {
                    label in correctSet && label in selected -> markCorrect(btn)
                    label in correctSet && label !in selected -> (btn as? CardView)?.setCardBackgroundColor(Color.parseColor("#553D2F00"))
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
        if (sentenceItems.isEmpty()) {
            renderCaseIssueRound()
            return
        }
        val item = sentenceItems.random()
        currentRoundCards = listOfNotNull(
            conceptPool.firstOrNull { it.id == item.cardId }
                ?: basePool.firstOrNull { it.topicTitle == item.topicTitle }
        )

        container.addView(sectionLabel("🧭 문장→목차 찾기"))
        container.addView(questionCard("[${item.parentLabel}]\n다음 문장은 어느 목차에 속할까요?\n\n“${item.sentence}”"))

        val choices = item.siblingLabels.shuffled()
        val buttons = mutableListOf<View>()
        choices.forEach { label ->
            val b = choiceButton(label, 14f) { pressed ->
                if (!roundAnswered) {
                    val correct = label == item.correctLabel
                    buttons.forEach { btn ->
                        when {
                            getChoiceText(btn) == item.correctLabel -> markCorrect(btn)
                            btn === pressed && !correct -> markWrong(btn)
                        }
                        btn.isEnabled = false
                    }
                    val cardId = currentRoundCards.firstOrNull()?.id
                    finishRoundPoints(if (correct) 1.0 else 0.0, 1.0, listOfNotNull(cardId))
                }
            }
            buttons.add(b)
            container.addView(b)
        }
    }

    // ---------- 본문 보기 / 결과 ----------

    private fun showContextDialog() {
        if (currentRoundCards.isEmpty()) return
        val allLatest = CardStore.getAllCards(this)
        val text = currentRoundCards.joinToString("\n\n━━━━━━━━━━\n\n") { c ->
            val latest = allLatest.firstOrNull { it.id == c.id } ?: c
            val mnemonicLine = if (latest.mnemonic.isNotBlank()) "두문자: ${latest.mnemonic}\n\n" else ""
            "[${latest.topicTitle}]\n$mnemonicLine${latest.back.ifBlank { "본문 내용이 없어요" }}"
        }
        val scroll = ScrollView(this)
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
