package com.example.adminmemo

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 실전 모의고사: 논점 여러 개를 무작위로 뽑아 타이머와 함께 보여주는 하드코어 연습 모드. */
class ExamSessionActivity : BaseActivity() {

    companion object {
        const val EXTRA_COUNT = "extra_exam_count"
        const val EXTRA_MINUTES = "extra_exam_minutes"
    }

    private lateinit var subject: String
    private var minutes = 50
    private lateinit var topics: List<Card>
    private var timer: CountDownTimer? = null
    private var submitted = false
    private var remainingMillisAtPause = 0L

    private lateinit var tvTimer: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var container: LinearLayout
    private lateinit var btnSubmit: Button

    private val answerViews = mutableListOf<Pair<Card, EditText>>()
    private val revealButtons = mutableListOf<Pair<Card, TextView>>() // 정답 표시용 (제출 후)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam_session)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ALL_SUBJECTS_KEY
        val count = intent.getIntExtra(EXTRA_COUNT, 4)
        minutes = intent.getIntExtra(EXTRA_MINUTES, 50)

        tvTimer = findViewById(R.id.tvExamTimer)
        tvSubtitle = findViewById(R.id.tvExamSubtitle)
        container = findViewById(R.id.examContainer)
        btnSubmit = findViewById(R.id.btnExamSubmit)

        topics = try {
            pickExamTopics(this, subject, count)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
        if (topics.isEmpty()) {
            val all = CardStore.getAllCards(this)
            val scoped = if (subject == ALL_SUBJECTS_KEY) all else all.filter { it.subject == subject }
            val pool = scoped.ifEmpty { all }
            topics = if (pool.isNotEmpty()) pool.shuffled().take(count) else listOf(
                Card(id = "dummy", type = "concept", subject = subject, title = "기본 주제", topicTitle = "기본 주제", front = "내용", back = "내용", grade = "A+", mnemonics = emptyList())
            )
        }

        buildQuestions()
        startTimer(minutes * 60_000L)

        btnSubmit.setOnClickListener {
            if (!submitted) submitExam() else finish()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(fillColor: Int, strokeColor: Int, strokeWidthDp: Int = 2, radiusDp: Int = 16): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun buildQuestions() {
        container.removeAllViews()
        answerViews.clear()
        revealButtons.clear()

        topics.forEachIndexed { i, card ->
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
            tvNum.text = "문제 ${i + 1}" + if (subject == ALL_SUBJECTS_KEY) "  ·  ${card.subject}" else ""
            tvNum.setTextColor(ContextCompat.getColor(this, R.color.primary))
            tvNum.textSize = 12f
            tvNum.setTypeface(tvNum.typeface, Typeface.BOLD)

            val tvTitle = TextView(this)
            val questionText = if (card.type == "case") {
                card.front
            } else if (card.front.isNotBlank()) {
                val header = if (card.title.isNotBlank() && card.title != card.front && !card.front.contains(card.title)) "【${card.title}】\n\n" else ""
                header + card.front
            } else {
                "「${card.topicTitle.ifBlank { card.title }}」에 대하여 서술하시오."
            }
            tvTitle.text = questionText
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            tvTitle.textSize = 17f
            tvTitle.setTypeface(tvTitle.typeface, Typeface.BOLD)
            val ttlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            ttlp.topMargin = dp(4); ttlp.bottomMargin = dp(12)
            tvTitle.layoutParams = ttlp

            val et = EditText(this)
            et.hint = "(선택) 여기에 목차나 키워드를 간단히 적어도 되고, 종이에 손으로 써도 돼요"
            et.gravity = Gravity.TOP
            et.minLines = 3
            et.setTextColor(ContextCompat.getColor(this, R.color.text_main))

            val tvAnswer = TextView(this)
            tvAnswer.visibility = android.view.View.GONE
            tvAnswer.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            tvAnswer.textSize = 13.5f
            tvAnswer.setLineSpacing(dp(3).toFloat(), 1f)
            val alp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            alp.topMargin = dp(12)
            tvAnswer.layoutParams = alp

            col.addView(tvNum)
            col.addView(tvTitle)
            col.addView(et)
            col.addView(tvAnswer)
            cv.addView(col)
            container.addView(cv)

            answerViews.add(card to et)
            revealButtons.add(card to tvAnswer)
        }
    }

    private fun startTimer(totalMillis: Long) {
        timer?.cancel()
        timer = object : CountDownTimer(totalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillisAtPause = millisUntilFinished
                val totalSec = millisUntilFinished / 1000
                val m = totalSec / 60
                val s = totalSec % 60
                tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d", m, s)
                if (totalSec <= 300) {
                    tvTimer.setTextColor(Color.parseColor("#FF4B4B"))
                }
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
        tvSubtitle.text = "제출 완료 — 각 문제의 '모범답안 보기'로 확인해보세요"

        val dateLabel = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())

        answerViews.forEachIndexed { i, (card, et) ->
            et.isEnabled = false
            val typed = et.text.toString().trim()
            if (typed.isNotEmpty()) {
                val existing = CardStore.getMemo(this, card.id)
                val addition = "[실전연습 $dateLabel]\n$typed"
                val merged = if (existing.isBlank()) addition else "$existing\n\n$addition"
                CardStore.setMemo(this, card.id, merged)
            }

            val (_, tvAnswer) = revealButtons[i]
            val btnReveal = Button(this)
            btnReveal.text = "📖 모범답안 및 해설 보기"
            btnReveal.minHeight = dp(50)
            btnReveal.setPadding(dp(20), dp(12), dp(20), dp(12))
            btnReveal.textSize = 15f
            btnReveal.isAllCaps = false
            btnReveal.setTypeface(btnReveal.typeface, Typeface.BOLD)
            btnReveal.background = pill(Color.parseColor("#1F2962FF"), ContextCompat.getColor(this, R.color.primary))
            btnReveal.backgroundTintList = null
            btnReveal.setTextColor(ContextCompat.getColor(this, R.color.primary))
            btnReveal.elevation = 0f
            btnReveal.gravity = Gravity.CENTER
            val brlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            brlp.topMargin = dp(12)
            btnReveal.layoutParams = brlp
            btnReveal.setOnClickListener {
                val answerText = reflowBody(card.back.ifBlank { "모범답안 내용이 없어요" })
                val scroll = ScrollView(this)
                val tv = TextView(this)
                tv.text = answerText
                tv.setPadding(dp(22), dp(20), dp(22), dp(20))
                tv.textSize = 14.5f
                tv.setLineSpacing(dp(5).toFloat(), 1.15f)
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
                scroll.addView(tv)

                AlertDialog.Builder(this)
                    .setTitle("📖 [${card.title.ifBlank { card.topicTitle }}] 모범답안")
                    .setView(scroll)
                    .setPositiveButton("닫기", null)
                    .show()
            }
            val parentLayout = tvAnswer.parent as? LinearLayout
            parentLayout?.let { parent ->
                val idx = parent.indexOfChild(tvAnswer)
                parent.removeView(tvAnswer)
                if (idx >= 0) parent.addView(btnReveal, idx) else parent.addView(btnReveal)
            } ?: run {
                (tvAnswer.parent as? ViewGroup)?.addView(btnReveal)
            }
        }

        btnSubmit.text = "🏁 마치기"
        if (answerViews.any { it.second.text.toString().isNotBlank() }) {
            Toast.makeText(this, "작성한 메모는 각 주제의 메모로 저장됐어요", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
