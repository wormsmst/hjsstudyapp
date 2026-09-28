package com.example.adminmemo

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.widget.Button
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

    companion object {
        const val QUESTION_COUNT = 4
        const val TOTAL_MINUTES = 50
    }

    private enum class Kind { CASE, SHORT }

    private data class ExamItem(
        val kind: Kind,
        val prompt: String,
        val answer: String,
        val card: Card?          // 약술이면 그 주제 카드 (메모 저장용)
    )

    private lateinit var subject: String
    private lateinit var items: List<ExamItem>
    private var timer: CountDownTimer? = null
    private var submitted = false

    private lateinit var tvTimer: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var container: LinearLayout
    private lateinit var btnSubmit: Button

    private val inputs = mutableListOf<EditText>()
    private val answerViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam_session)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""

        tvTimer = findViewById(R.id.tvExamTimer)
        tvSubtitle = findViewById(R.id.tvExamSubtitle)
        container = findViewById(R.id.examContainer)
        btnSubmit = findViewById(R.id.btnExamSubmit)

        val built = buildItems()
        items = built.first
        val notice = built.second

        if (items.isEmpty()) {
            Toast.makeText(this, "출제할 문제가 없어요. 먼저 학습내용관리에서 주제를 추가해주세요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        tvSubtitle.text = if (notice.isNotEmpty()) notice else "📚 $subject · 문제를 확인하고 백지에 답안을 작성해보세요"

        buildQuestions()
        startTimer(TOTAL_MINUTES * 60_000L)

        btnSubmit.setOnClickListener {
            if (!submitted) submitExam() else finish()
        }
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

    /** 과목별 출제 규칙에 따라 4문제를 구성한다. 반환: (문제 목록, 안내 문구) */
    private fun buildItems(): Pair<List<ExamItem>, String> {
        val list = mutableListOf<ExamItem>()
        var notice = ""

        val wantCase = !subject.contains("사무관리")
        if (wantCase) {
            val case = MockExamRepository.pickCase(this, subject)
            if (case != null) {
                list.add(
                    ExamItem(
                        Kind.CASE,
                        case.question.trim(),
                        case.explanation.trim().ifBlank { "(이 문제는 해답 데이터가 없어요)" },
                        null
                    )
                )
            } else {
                notice = "⚠️ 이 과목의 사례문제(mock_exams.json)를 찾지 못해 1번도 약술로 출제했어요"
            }
        }

        val pool = CardStore.getAllCards(this)
            .filter { it.type == "concept" && it.subject == subject && it.back.isNotBlank() }
            .toMutableList()

        while (list.size < QUESTION_COUNT && pool.isNotEmpty()) {
            val idx = weightedRandomIndex(this, pool)
            val card = pool.removeAt(idx)
            list.add(
                ExamItem(
                    Kind.SHORT,
                    "「${cleanTitle(card.topicTitle)}」에 대하여 설명하시오.",
                    reflowBody(card.back),
                    card
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
            tvNum.text = "문제 ${i + 1}  ·  " + if (item.kind == Kind.CASE) "사례문제" else "약술"
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
            et.hint = "(선택) 목차나 키워드를 간단히 적어도 되고, 종이에 손으로 써도 돼요"
            et.gravity = Gravity.TOP
            et.minLines = if (item.kind == Kind.CASE) 5 else 3
            et.setTextColor(ContextCompat.getColor(this, R.color.text_main))

            val tvAnswer = TextView(this)
            tvAnswer.visibility = View.GONE
            tvAnswer.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            tvAnswer.textSize = 13.5f
            tvAnswer.setLineSpacing(dp(3).toFloat(), 1f)
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
        timer?.cancel()
        timer = object : CountDownTimer(totalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSec = millisUntilFinished / 1000
                tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
                if (totalSec <= 300) tvTimer.setTextColor(Color.parseColor("#FF4B4B"))
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
                    tvAnswer.text = item.answer
                    tvAnswer.visibility = View.VISIBLE
                    btnReveal.text = "📕 해답 닫기"
                }
            }
            val parent = tvAnswer.parent as LinearLayout
            parent.addView(btnReveal, parent.indexOfChild(tvAnswer))
        }

        btnSubmit.text = "🏁 마치기"
        if (savedAny) Toast.makeText(this, "약술 메모는 각 주제의 메모로 저장됐어요", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
