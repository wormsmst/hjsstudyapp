package com.example.adminmemo

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

class ContentDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_IDS = "extra_ids"
        const val EXTRA_INDEX = "extra_index"
    }

    private lateinit var ids: List<String>
    private var index = 0

    private lateinit var tvProgress: TextView
    private lateinit var tvGrade: TextView
    private lateinit var tvTitle: TextView
    private lateinit var cardMnemonicBox: CardView
    private lateinit var tvMnemonic: TextView
    private lateinit var tvBody: TextView
    private lateinit var etMemo: EditText
    private lateinit var memoryStars: List<TextView>

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isSpeaking = false
    private lateinit var btnSpeak: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_detail)

        ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: arrayListOf()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, ids.size - 1))

        tvProgress = findViewById(R.id.tvDetailProgress)
        tvGrade = findViewById(R.id.tvDetailGrade)
        tvTitle = findViewById(R.id.tvDetailTitle)
        cardMnemonicBox = findViewById(R.id.cardMnemonicBox)
        tvMnemonic = findViewById(R.id.tvDetailMnemonic)
        tvBody = findViewById(R.id.tvDetailBody)
        etMemo = findViewById(R.id.etMemo)
        btnSpeak = findViewById(R.id.btnSpeak)

        memoryStars = listOf(
            findViewById(R.id.star1), findViewById(R.id.star2), findViewById(R.id.star3),
            findViewById(R.id.star4), findViewById(R.id.star5)
        )
        memoryStars.forEachIndexed { i, star ->
            star.setOnClickListener {
                val card = currentCard() ?: return@setOnClickListener
                CardStore.setMemoryLevel(this, card.subject, card.topicTitle, i + 1)
                renderMemoryStars(i + 1)
            }
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { setSpeakingUi(false) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { setSpeakingUi(false) }
            }
        })

        btnSpeak.setOnClickListener {
            if (isSpeaking) {
                stopSpeaking()
            } else {
                startSpeaking()
            }
        }

        findViewById<ImageButton>(R.id.btnEditDetail).setOnClickListener {
            stopSpeaking()
            showEditDialog()
        }

        findViewById<Button>(R.id.btnPrevDetail).setOnClickListener {
            saveCurrentMemoSilently()
            stopSpeaking()
            if (index > 0) { index--; render() }
        }
        findViewById<Button>(R.id.btnNextDetail).setOnClickListener {
            saveCurrentMemoSilently()
            stopSpeaking()
            if (index < ids.size - 1) { index++; render() }
        }
        findViewById<Button>(R.id.btnSaveMemo).setOnClickListener {
            saveCurrentMemoSilently()
            Toast.makeText(this, "메모를 저장했어요", Toast.LENGTH_SHORT).show()
        }

        render()
    }

    private fun startSpeaking() {
        val card = currentCard() ?: return
        if (!ttsReady) {
            Toast.makeText(this, "음성 엔진을 준비 중이에요. 잠시 후 다시 눌러주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val parts = mutableListOf(card.topicTitle)
        if (card.mnemonic.isNotBlank()) parts.add("두문자, ${card.mnemonic.replace(".", ", ")}")
        parts.add(card.back.ifBlank { "본문 내용이 없어요" })
        val fullText = parts.joinToString(". ")

        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        setSpeakingUi(true)
    }

    private fun stopSpeaking() {
        tts?.stop()
        setSpeakingUi(false)
    }

    private fun setSpeakingUi(speaking: Boolean) {
        isSpeaking = speaking
        btnSpeak.setImageResource(
            if (speaking) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_lock_silent_mode_off
        )
    }

    private fun currentCard(): Card? {
        if (ids.isEmpty()) return null
        val id = ids[index]
        return CardStore.getAllCards(this).firstOrNull { it.id == id }
    }

    private fun render() {
        findViewById<ScrollView>(R.id.detailScrollView)?.scrollTo(0, 0)
        val card = currentCard() ?: run {
            tvTitle.text = "카드를 찾을 수 없어요"
            tvBody.text = ""
            return
        }
        tvProgress.text = "${index + 1} / ${ids.size}"
        tvGrade.text = card.grade
        tvTitle.text = card.topicTitle

        if (card.mnemonic.isNotBlank()) {
            cardMnemonicBox.visibility = View.VISIBLE
            tvMnemonic.text = "🔑 두문자: ${card.mnemonic}"
        } else {
            cardMnemonicBox.visibility = View.GONE
        }

        tvBody.text = buildStyledBody(formatBodyText(reflowBody(card.back.ifBlank { "본문 내용이 없어요" })))
        etMemo.setText(CardStore.getMemo(this, card.id))
        renderMemoryStars(CardStore.getMemoryLevel(this, card.subject, card.topicTitle))

        findViewById<Button>(R.id.btnPrevDetail).isEnabled = index > 0
        findViewById<Button>(R.id.btnNextDetail).isEnabled = index < ids.size - 1
    }

    /**
     * 본문을 목차 레벨에 맞게 꾸민다.
     * "1. " (대목차) → 굵게 + 큰 글씨 + 들여쓰기 없음
     * "1) " (중목차) → 굵게 + 들여쓰기 1단계
     * "(1) " (소목차) → 들여쓰기 2단계
     * "①", "-", "·" 등 → 들여쓰기 2단계
     * 그 외 줄바꿈된 설명 문장은 바로 위 목차와 같은 들여쓰기를 따라간다.
     */
    private fun buildStyledBody(text: String): SpannableStringBuilder {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val majorRe = Regex("^\\d+\\.\\s")
        val sub1Re = Regex("^\\d+\\)\\s")
        val sub2Re = Regex("^\\(\\d+\\)\\s")
        val sub3Re = Regex("^([①②③④⑤⑥⑦⑧⑨⑩]|[-·])\\s?")

        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val lines = text.split("\n")
        val sb = SpannableStringBuilder()
        var currentIndent = 0

        for (line in lines) {
            val trimmed = line.trimStart()
            val start = sb.length
            var indent = currentIndent
            var sizeRel = 1.0f
            var bold = false
            var color: Int? = null

            when {
                trimmed.isBlank() -> {
                    indent = 0
                }
                majorRe.containsMatchIn(trimmed) -> {
                    indent = 0; sizeRel = 1.12f; bold = true; color = primaryColor
                    currentIndent = 0
                }
                sub1Re.containsMatchIn(trimmed) -> {
                    indent = dp(18); bold = true
                    currentIndent = dp(18)
                }
                sub2Re.containsMatchIn(trimmed) -> {
                    indent = dp(36); sizeRel = 0.97f
                    currentIndent = dp(36)
                }
                sub3Re.containsMatchIn(trimmed) -> {
                    indent = dp(36); sizeRel = 0.97f
                    currentIndent = dp(36)
                }
                else -> {
                    indent = currentIndent
                }
            }

            sb.append(line)
            val end = sb.length
            sb.append("\n")

            if (end > start) {
                sb.setSpan(LeadingMarginSpan.Standard(indent, indent), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                if (sizeRel != 1.0f) sb.setSpan(RelativeSizeSpan(sizeRel), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                if (color != null) sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** "1. ", "2. " 같은 최상위 번호 목차 앞에 빈 줄을 넣어 읽기 편하게 만든다. */
    private fun formatBodyText(text: String): String {
        val headingRegex = Regex("^\\d+\\.\\s")
        val lines = text.split("\n")
        val out = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val isHeading = headingRegex.containsMatchIn(line.trimStart())
            if (i > 0 && isHeading) {
                val prevBlank = out.isNotEmpty() && out.last().isBlank()
                if (!prevBlank) out.add("")
            }
            out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun renderMemoryStars(level: Int) {
        memoryStars.forEachIndexed { i, star ->
            star.text = if (i < level) "★" else "☆"
            star.setTextColor(
                if (i < level) ContextCompat.getColor(this, R.color.accent)
                else ContextCompat.getColor(this, R.color.text_sub)
            )
        }
    }

    private fun showEditDialog() {
        val card = currentCard() ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_concept, null)
        val etTitle = view.findViewById<EditText>(R.id.etConceptTitle)
        val etBody = view.findViewById<EditText>(R.id.etConceptBody)
        etTitle.setText(card.topicTitle)
        etBody.setText(card.back)

        AlertDialog.Builder(this)
            .setTitle("내용 수정")
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                val newTitle = etTitle.text.toString().trim().ifEmpty { "(제목없음)" }
                val newBody = etBody.text.toString().trim()
                val updated = card.copy(
                    topicTitle = newTitle,
                    title = "${card.num} $newTitle".trim(),
                    front = if (card.grade.isNotBlank()) "$newTitle\n(${card.grade})" else newTitle,
                    back = newBody
                )
                CardStore.updateCard(this, updated)
                render()
                Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveCurrentMemoSilently() {
        val card = currentCard() ?: return
        CardStore.setMemo(this, card.id, etMemo.text.toString())
    }

    override fun onPause() {
        super.onPause()
        saveCurrentMemoSilently()
        stopSpeaking()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
