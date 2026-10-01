package com.example.adminmemo

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

class HonestyQuizActivity : BaseActivity() {

    companion object {
        const val EXTRA_SUBJECT = "extra_subject"
    }

    private lateinit var subject: String
    private lateinit var conceptCards: MutableList<Card>
    private var currentCard: Card? = null
    private var keywords: List<String> = emptyList()
    private var checkedState: BooleanArray = booleanArrayOf()

    private lateinit var tvTopic: TextView
    private lateinit var layoutRubric: LinearLayout
    private lateinit var containerRubric: LinearLayout
    private lateinit var tvResult: TextView
    private lateinit var btnAction: Button

    private var isRevealed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_honesty_quiz)

            subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ALL_SUBJECTS_KEY

            tvTopic = findViewById(R.id.tvHonestyTopic)
            layoutRubric = findViewById(R.id.layoutRubric)
            containerRubric = findViewById(R.id.containerRubricCheckboxes)
            tvResult = findViewById(R.id.tvHonestyResult)
            btnAction = findViewById(R.id.btnHonestyAction)

            findViewById<ImageButton>(R.id.btnHonestyBack).setOnClickListener {
                confirmChoice("퀴즈를 나갈까요?", "나가기") { finish() }
            }
            confirmLeaveOnBack("퀴즈를 나갈까요?")
            findViewById<Button>(R.id.btnHonestyViewBody).setOnClickListener {
                currentCard?.let { card ->
                    val fullText = reflowBody(card.back.ifBlank { card.front.ifBlank { "본문 내용이 없어요" } })
                    AlertDialog.Builder(this)
                        .setTitle("📖 [${card.topicTitle}] 모범답안 본문")
                        .setMessage(fullText)
                        .setPositiveButton("닫기", null)
                        .show()
                }
            }

            val all = CardStore.getAllCards(this)
            val scoped = if (subject == ALL_SUBJECTS_KEY) all else all.filter { it.subject == subject }
            conceptCards = scoped.filter { it.type == "concept" && it.topicTitle.isNotBlank() }.toMutableList()
            if (conceptCards.isEmpty()) {
                conceptCards = all.filter { it.type == "concept" && it.topicTitle.isNotBlank() }.toMutableList()
            }
            if (conceptCards.isEmpty()) {
                conceptCards = scoped.filter { it.topicTitle.isNotBlank() }.toMutableList()
            }

            if (conceptCards.isEmpty()) {
                Toast.makeText(this, "학습할 카드가 부족해요", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            StudyProgressStore.markActivity(this, subject, StudyProgressStore.KIND_QUIZ)

            loadNextQuestion()

            btnAction.setOnClickListener {
                if (!isRevealed) {
                    isRevealed = true
                    layoutRubric.visibility = View.VISIBLE
                    btnAction.text = "채점 완료 및 다음 문제"
                } else {
                    val checkedCount = checkedState.count { it }
                    val totalCount = keywords.size
                    val ratio = if (totalCount > 0) checkedCount.toDouble() / totalCount.toDouble() else 0.0
                    val correct = ratio >= 0.6

                    tvResult.visibility = View.VISIBLE
                    tvResult.text = "🎯 키워드 적중률: ${(ratio * 100).toInt()}% ($checkedCount / $totalCount 개 체크)"
                    tvResult.setTextColor(ContextCompat.getColor(this, if (correct) R.color.primary else R.color.text_sub))

                    currentCard?.let {
                        if (correct) CardStore.removeWrong(this, it.id) else CardStore.addWrong(this, it.id)
                    }

                    btnAction.isEnabled = false
                    btnAction.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            btnAction.isEnabled = true
                            tvResult.visibility = View.GONE
                            layoutRubric.visibility = View.GONE
                            isRevealed = false
                            btnAction.text = "정답 및 채점표 확인"
                            loadNextQuestion()
                        }
                    }, 1500)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "핵심키워드 연습 오류: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadNextQuestion() {
        if (conceptCards.isEmpty()) {
            Toast.makeText(this, "모든 문제를 풀었어요! 🎉", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentCard = conceptCards.random()
        conceptCards.remove(currentCard)
        tvTopic.text = "[${currentCard?.topicTitle}]"

        keywords = extractCardRubricKeywords(currentCard!!)
        checkedState = BooleanArray(keywords.size) { false }

        containerRubric.removeAllViews()
        keywords.forEachIndexed { index, keyword ->
            val cb = CheckBox(this).apply {
                text = keyword
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnCheckedChangeListener { _, isChecked ->
                    if (index in checkedState.indices) {
                        checkedState[index] = isChecked
                    }
                }
            }
            val cv = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) }
                radius = dp(12).toFloat()
                cardElevation = 1f * resources.displayMetrics.density
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.bg_card))
                addView(cb)
            }
            containerRubric.addView(cv)
        }
    }

    private fun extractCardRubricKeywords(card: Card): List<String> {
        val keywords = mutableListOf<String>()
        if (card.topicTitle.isNotBlank()) {
            toChiefQuizKeyword(card.topicTitle)?.let { keywords.add(it) }
        }

        val roots = parseOutline(card.back).filter { it.level >= 0 }
        roots.forEach { root ->
            toChiefQuizKeyword(root.label)?.let { keywords.add(it) }
            root.children.forEach { child ->
                toChiefQuizKeyword(child.label)?.let { keywords.add(it) }
            }
        }

        if (card.mnemonic.isNotBlank()) {
            keywords.add("두문자: ${card.mnemonic.trim()}")
        }

        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()

        for (kw in keywords) {
            val normalized = kw.lowercase().replace(Regex("[^가-힣a-zA-Z0-9]"), "")
            if (normalized.length >= 2 && seen.add(normalized)) {
                result.add(kw)
                if (result.size >= 6) break
            }
        }

        return result
    }

    private fun toChiefQuizKeyword(raw: String): String? {
        var cleaned = cleanOutlineLabel(raw)
        cleaned = cleaned.replace(Regex("(에 관하여|에 대하여|의 경우|를 할 수 있다|할 수 있다|할 수 있음|하는 경우|하는 때|인 경우|인 때|의 방법|을 말한다|이다|한다|된다|있다|없다|등)$"), "").trim()
        
        if (cleaned.contains(".") || cleaned.contains("다.")) return null
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return null

        val finalStr = words.take(4).joinToString(" ")
        if (finalStr.length < 2 || isGenericOrWeakWord(finalStr)) return null
        return finalStr
    }

    private fun isGenericOrWeakWord(word: String): Boolean {
        val generic = setOf(
            "사람", "경우", "방법", "내용", "규정", "사항", "기타", "사실", "과정", "절차",
            "점", "때문", "중", "등", "관련", "관하여", "대하여", "통하여", "의하여",
            "원칙", "기준", "성격", "측면", "요소", "형태", "종류", "특징", "의미",
            "의의", "요건", "절차", "효과", "판례", "태도", "방법", "취지", "성질",
            "범위", "효력", "종류", "특징", "원칙", "기준", "문제점", "검토", "결론",
            "사유", "시기", "한계", "의미", "대상", "주체", "객체"
        )
        return word.lowercase() in generic || word.length < 2 || Regex("^[0-9]+$").matches(word)
    }

    private fun cleanOutlineLabel(label: String): String {
        return label.replace(Regex("^(?:[IVXLCDMivxlcdm]+\\.|\\d+[.)]\\s*|\\(\\d+\\)\\s*|[가-힣][.)]\\s*|[①②③④⑤⑥⑦⑧⑨⑩\\-·•]\\s*)"), "").trim()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
