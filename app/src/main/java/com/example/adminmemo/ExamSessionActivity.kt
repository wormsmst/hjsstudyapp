package com.example.adminmemo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 실전 모의고사 (4문제 / 50분 고정)
 *  - 민법 · 행정절차론 · 행정사실무법: 1번 사례문제(mock_exams.json) + 2~4번 약술(cards.json)
 *  - 사무관리론: 1~4번 모두 약술(cards.json)
 * 사례문제는 question을 문제로, explanation을 해답으로 보여주고,
 * 약술은 "「제목」에 대하여 설명하시오."로 출제하고 본문(back)을 해답으로 보여준다.
 */
class ExamSessionActivity : BaseActivity() {

    override val showScratchPad = true

    companion object {
        const val QUESTION_COUNT = 4
        const val TOTAL_MINUTES = 50
        const val EXTRA_COUNT = "extra_exam_count"
        const val EXTRA_MINUTES = "extra_exam_minutes"
        const val EXTRA_PERIOD = "extra_exam_period"
    }

    private enum class Kind { CASE, SHORT }

    private data class ExamItem(
        val kind: Kind,
        val prompt: String,
        val answer: String,
        val card: Card? = null,
        val caseItem: MockExamItem? = null,
        val subjectLabel: String = ""
    )

    private lateinit var subject: String
    private lateinit var items: List<ExamItem>
    private var questionCount = QUESTION_COUNT
    private var totalMinutes = TOTAL_MINUTES
    private var examPeriod = 0
    private var timer: CountDownTimer? = null
    private var submitted = false
    private var totalExamMillis = 0L
    private val weakBoxes = mutableListOf<android.widget.CheckBox>()

    private lateinit var tvTimer: TextView
    private lateinit var tvPace: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var container: LinearLayout
    private lateinit var btnSubmit: Button

    private val inputs = mutableListOf<EditText>()
    private val answerViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam_session)
        bindLandscapeSplit(R.id.layoutExamSplit)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""
        examPeriod = intent.getIntExtra(EXTRA_PERIOD, 0)
        questionCount = intent.getIntExtra(EXTRA_COUNT, QUESTION_COUNT).coerceIn(1, 20)
        totalMinutes = intent.getIntExtra(EXTRA_MINUTES, TOTAL_MINUTES).coerceIn(5, 180)
        if (examPeriod == 1 || examPeriod == 2) {
            questionCount = 8
            totalMinutes = 100
        }

        tvTimer = findViewById(R.id.tvExamTimer)
        tvPace = findViewById(R.id.tvExamPace)
        tvSubtitle = findViewById(R.id.tvExamSubtitle)
        findViewById<TextView>(R.id.tvExamCoach).text = CoachHints.EXAM
        container = findViewById(R.id.examContainer)
        btnSubmit = findViewById(R.id.btnExamSubmit)

        val built = buildItems()
        items = built.first
        val notice = built.second

        if (items.isEmpty()) {
            Toast.makeText(this, "출제할 문제가 없어요. 설정에서 본문·사례를 추가해주세요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        StudyProgressStore.markActivity(this, subject, StudyProgressStore.KIND_EXAM)
        if (examPeriod == 1) {
            StudyProgressStore.EXAM1_SUBJECTS.forEach {
                StudyProgressStore.markActivity(this, it, StudyProgressStore.KIND_EXAM)
            }
        } else if (examPeriod == 2) {
            StudyProgressStore.EXAM2_SUBJECTS.forEach {
                StudyProgressStore.markActivity(this, it, StudyProgressStore.KIND_EXAM)
            }
        }

        tvSubtitle.text = when {
            notice.isNotEmpty() -> notice
            examPeriod == 1 -> "1교시 · 민법+행정절차론 · 종이에 쓰고, 앱은 타이머와 해답만 쓰세요"
            examPeriod == 2 -> "2교시 · 사무관리론+행정사실무법 · 종이에 쓰고, 앱은 타이머와 해답만 쓰세요"
            else -> "📚 $subject · 종이에 답안을 쓰고, 앱은 타이머와 해답만 쓰세요"
        }

        buildQuestions()
        startTimer(totalMinutes * 60_000L)
        tvPace.text = paceLine(0)

        btnSubmit.setOnClickListener {
            if (!submitted) submitExam() else finish()
        }
        confirmLeaveOnBack("모의고사를 나갈까요? 제출하지 않으면 답안이 저장되지 않아요.") { !submitted }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** "05 부작위ㅇ" 같은 제목 뒤의 표식(ㅇ, 괄호 속 등급/기출 표기)을 걷어낸 시험지용 제목 */
    private fun cleanTitle(t: String): String {
        var s = t.trim()
        s = s.replace(Regex("[ㅇ]+$"), "").trim()
        s = s.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        s = s.replace(Regex("[ㅇ]+$"), "").trim()
        return s.ifBlank { t.trim() }
    }

    /** 과목별 출제 규칙에 따라 문제를 구성한다. 반환: (문제 목록, 안내 문구) */
    private fun buildItems(): Pair<List<ExamItem>, String> {
        if (examPeriod == 1 || examPeriod == 2) {
            val subs = if (examPeriod == 1) StudyProgressStore.EXAM1_SUBJECTS else StudyProgressStore.EXAM2_SUBJECTS
            val all = mutableListOf<ExamItem>()
            val notes = mutableListOf<String>()
            for (sub in subs) {
                val part = buildItemsFor(sub, 4)
                all.addAll(part.first)
                if (part.second.isNotBlank()) notes.add(part.second)
            }
            return all to notes.joinToString("\n")
        }
        return buildItemsFor(subject, questionCount)
    }

    private fun buildItemsFor(rawSubject: String, count: Int): Pair<List<ExamItem>, String> {
        val list = mutableListOf<ExamItem>()
        var notice = ""
        val want = canonicalizeSubject(rawSubject)

        val wantCase = !want.contains("사무관리")
        if (wantCase) {
            val case = MockExamRepository.pickCase(this, rawSubject)
            if (case != null) {
                list.add(
                    ExamItem(
                        Kind.CASE,
                        case.question.trim(),
                        case.explanation.trim().ifBlank { "(이 문제는 해답 데이터가 없어요)" },
                        null,
                        case,
                        want
                    )
                )
            } else {
                notice = "⚠️ $want 사례문제를 찾지 못해 1번도 약술로 출제했어요"
            }
        }

        val pool = CardStore.getAllCards(this)
            .filter {
                it.type == "concept" &&
                    canonicalizeSubject(it.subject) == want &&
                    it.back.isNotBlank()
            }
            .toMutableList()

        while (list.size < count && pool.isNotEmpty()) {
            val idx = weightedRandomIndex(this, pool)
            val card = pool.removeAt(idx)
            list.add(
                ExamItem(
                    Kind.SHORT,
                    "「${cleanTitle(card.topicTitle)}」에 대하여 설명하시오.",
                    card.back,
                    card,
                    null,
                    want
                )
            )
        }
        return list to notice
    }

    private fun buildQuestions() {
        container.removeAllViews()
        inputs.clear()
        answerViews.clear()

        items.forEachIndexed { i, item ->
            val cv = CardView(this)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(16)
            cv.layoutParams = lp
            cv.radius = dp(18).toFloat()
            cv.cardElevation = dp(1).toFloat()
            cv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(18), dp(18), dp(18), dp(18))

            val tvNum = TextView(this)
            tvNum.text = buildString {
                if (item.subjectLabel.isNotBlank()) append(item.subjectLabel).append("  ·  ")
                append("문제 ${i + 1}  ·  ")
                append(if (item.kind == Kind.CASE) "사례문제" else "약술")
                append("  ·  목표 ${itemBudgetMin(i)}분")
            }
            tvNum.setTextColor(ContextCompat.getColor(this, R.color.primary))
            tvNum.textSize = 12f
            tvNum.setTypeface(tvNum.typeface, Typeface.BOLD)

            val tvPrompt = TextView(this)
            tvPrompt.text = item.prompt
            tvPrompt.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            if (item.kind == Kind.CASE) {
                tvPrompt.textSize = 15f
                tvPrompt.setLineSpacing(dp(4).toFloat(), 1f)
            } else {
                tvPrompt.textSize = 17f
                tvPrompt.setTypeface(tvPrompt.typeface, Typeface.BOLD)
            }
            val plp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            plp.topMargin = dp(6); plp.bottomMargin = dp(12)
            tvPrompt.layoutParams = plp

            val et = EditText(this)
            et.hint = "가능하면 종이에 쓰세요. 여기엔 목차만 적어도 됩니다."
            et.gravity = Gravity.TOP
            et.minLines = if (item.kind == Kind.CASE) 5 else 3
            et.setTextColor(ContextCompat.getColor(this, R.color.text_main))

            val tvAnswer = TextView(this)
            tvAnswer.visibility = View.GONE
            tvAnswer.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            tvAnswer.textSize = 15f
            tvAnswer.setLineSpacing(dp(6).toFloat(), 1f)
            val alp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            alp.topMargin = dp(12)
            tvAnswer.layoutParams = alp

            col.addView(tvNum)
            col.addView(tvPrompt)
            col.addView(et)
            col.addView(tvAnswer)
            cv.addView(col)
            container.addView(cv)

            inputs.add(et)
            answerViews.add(tvAnswer)
        }
    }

    private fun startTimer(totalMillis: Long) {
        totalExamMillis = totalMillis
        timer?.cancel()
        timer = object : CountDownTimer(totalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSec = millisUntilFinished / 1000
                tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
                if (totalSec <= 300) tvTimer.setTextColor(Color.parseColor("#FF4B4B"))
                if (!submitted) tvPace.text = paceLine(totalMillis - millisUntilFinished)
            }

            override fun onFinish() {
                tvTimer.text = "00:00"
                Toast.makeText(this@ExamSessionActivity, "⏰ 시간 종료! 제출할게요", Toast.LENGTH_LONG).show()
                submitExam()
            }
        }.start()
    }

    private fun submitExam() {
        if (submitted) return
        submitted = true
        timer?.cancel()
        tvSubtitle.text = "제출 완료 — 각 문제의 '해답 보기'로 확인해보세요"

        val dateLabel = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
        var savedAny = false
        weakBoxes.clear()

        items.forEachIndexed { i, item ->
            val et = inputs[i]
            et.isEnabled = false

            // 약술로 적은 내용은 그 주제의 메모에 시각과 함께 보관
            val typed = et.text.toString().trim()
            val card = item.card
            if (typed.isNotEmpty() && card != null) {
                val existing = CardStore.getMemo(this, card.id)
                val addition = "[실전연습 $dateLabel]\n$typed"
                CardStore.setMemo(this, card.id, if (existing.isBlank()) addition else "$existing\n\n$addition")
                savedAny = true
            }

            val tvAnswer = answerViews[i]
            val btnReveal = Button(this)
            btnReveal.text = "📖 해답 보기"
            val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            blp.topMargin = dp(10)
            btnReveal.layoutParams = blp
            btnReveal.setOnClickListener {
                if (tvAnswer.visibility == View.VISIBLE) {
                    tvAnswer.visibility = View.GONE
                    btnReveal.text = "📖 해답 보기"
                } else {
                    val keep = item.card != null && CardStore.isLocallyEdited(this, item.card.id)
                    tvAnswer.text = styledStudyAnswer(this, item.answer, keep)
                    tvAnswer.visibility = View.VISIBLE
                    btnReveal.text = "📕 해답 닫기"
                }
            }
            val parent = tvAnswer.parent as LinearLayout
            parent.addView(btnReveal, parent.indexOfChild(tvAnswer))

            val btnWrong = Button(this)
            btnWrong.text = "오답노트에 넣기"
            val wlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            wlp.topMargin = dp(6)
            btnWrong.layoutParams = wlp
            btnWrong.setOnClickListener {
                val caseItem = item.caseItem
                val card = item.card
                when {
                    caseItem != null -> WrongNoteStore.recordWrong(
                        this, "case", caseItem.id, caseItem.subject,
                        caseItem.title.ifBlank { "사례문제" }, "사례"
                    )
                    card != null -> CardStore.addWrong(this, card.id)
                    else -> WrongNoteStore.recordWrong(
                        this, "exam", "exam_${subject}_$i", subject,
                        "모의고사 ${i + 1}번", "모의고사"
                    )
                }
                Toast.makeText(this, "오답노트에 넣었어요", Toast.LENGTH_SHORT).show()
            }
            parent.addView(btnWrong)

            val tvScore = TextView(this)
            tvScore.text = "자기채점 — 해당하면 체크. 분량이 아니라 목차와 문장입니다."
            tvScore.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
            tvScore.textSize = 12f
            val slp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            slp.topMargin = dp(10)
            tvScore.layoutParams = slp
            parent.addView(tvScore)
            val missOutline = examCheck(parent, "목차가 빠졌다")
            val keywordOnly = examCheck(parent, "키워드만 쓰고 문장을 못 썼다")
            val noSentence = examCheck(parent, "항목을 문장으로 잇지 못했다")
            weakBoxes.add(missOutline)
            weakBoxes.add(keywordOnly)
            weakBoxes.add(noSentence)
            missOutline.tag = i
            keywordOnly.tag = i
            noSentence.tag = i
        }

        StudyProgressStore.recordExam(this, subject)

        val btnQuest = Button(this)
        btnQuest.text = "약했던 문항을 인출 퀘스트로"
        val qlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        qlp.topMargin = dp(8)
        qlp.bottomMargin = dp(20)
        btnQuest.layoutParams = qlp
        btnQuest.setOnClickListener { sendWeakToQuests() }
        container.addView(btnQuest)

        btnSubmit.text = "🏁 마치기"
        if (savedAny) Toast.makeText(this, "약술 메모는 각 주제의 메모로 저장됐어요", Toast.LENGTH_SHORT).show()
    }

    private fun examCheck(parent: LinearLayout, label: String): CheckBox {
        val cb = CheckBox(this)
        cb.text = label
        cb.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        parent.addView(cb)
        return cb
    }

    private fun sendWeakToQuests() {
        val weakIdx = weakBoxes
            .filter { it.isChecked }
            .mapNotNull { (it.tag as? Int) }
            .toSet()
        val cards = weakIdx.flatMap { i -> relatedCards(items.getOrNull(i) ?: return@flatMap emptyList()) }
            .distinctBy { it.id }
        if (cards.isEmpty()) {
            Toast.makeText(this, "체크한 문항이 없거나, 연결할 주제를 찾지 못했어요", Toast.LENGTH_SHORT).show()
            return
        }
        DailyQuestStore.addExamWeak(this, cards)
        Toast.makeText(this, "퀘스트에 ${cards.size}개를 넣었어요. 인출 기한도 오늘로 당겼어요.", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, QuestActivity::class.java))
    }

    private fun relatedCards(item: ExamItem): List<Card> {
        item.card?.let { return listOf(it) }
        val case = item.caseItem ?: return emptyList()
        val want = canonicalizeSubject(case.subject)
        val keys = (listOf(case.topic, case.title) + case.keywordList())
            .map { it.trim() }
            .filter { it.length >= 2 }
        val pool = CardStore.getAllCards(this).filter {
            it.type == "concept" && canonicalizeSubject(it.subject) == want && it.back.isNotBlank()
        }
        val hit = pool.filter { c ->
            keys.any { k -> c.topicTitle.contains(k) || c.title.contains(k) }
        }
        return hit.take(2)
    }

    private fun itemBudgetMin(i: Int): Int {
        val n = items.size.coerceAtLeast(1)
        if (n == 8 && totalMinutes >= 90) {
            return if (i % 4 == 0 && items.getOrNull(i)?.kind == Kind.CASE) 20 else 10
        }
        if (n == 4) {
            val caseFirst = items.firstOrNull()?.kind == Kind.CASE
            return when {
                totalMinutes >= 90 && caseFirst && i == 0 -> 40
                totalMinutes >= 90 && caseFirst -> 20
                caseFirst && i == 0 -> 20
                caseFirst -> 10
                else -> (totalMinutes / n).coerceAtLeast(8)
            }
        }
        return (totalMinutes / n).coerceAtLeast(5)
    }

    private fun paceLine(elapsedMs: Long): String {
        if (items.isEmpty()) return ""
        val elapsedMin = elapsedMs / 60_000.0
        var acc = 0
        var rec = 0
        for (i in items.indices) {
            acc += itemBudgetMin(i)
            rec = i
            if (elapsedMin < acc) break
        }
        val item = items[rec]
        val kind = if (item.kind == Kind.CASE) "사례" else "약술"
        val budget = itemBudgetMin(rec)
        val es = elapsedMs / 1000
        return "권장 ${rec + 1}번 $kind · 이 문제 약 ${budget}분  ·  경과 ${es / 60}:${String.format(Locale.getDefault(), "%02d", es % 60)}"
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
