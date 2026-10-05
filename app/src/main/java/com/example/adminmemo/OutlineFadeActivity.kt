package com.example.adminmemo

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

class OutlineFadeActivity : BaseActivity() {

    override val showScratchPad = true

    private var queue: List<Card> = emptyList()
    private var index = 0
    private var roots: List<OutlineNode> = emptyList()
    private var depth = -1
    private var throughLevel = -1
    private var writeMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = RecallStore.fadeQueue(this)
        if (queue.isEmpty()) {
            Toast.makeText(this, "목차가 있는 약한 장이 없어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_outline_fade)
        bindLandscapeSplit(R.id.layoutFadeSplit)
        confirmLeaveOnBack("목차암기를 그만둘까요?") { index < queue.size }
        findViewById<View>(R.id.btnFadeReveal).setOnClickListener { revealNext() }
        findViewById<View>(R.id.btnFadeCheck).setOnClickListener { showAnswer() }
        findViewById<View>(R.id.btnFadeNext).setOnClickListener { nextCard() }
        showCard()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindLandscapeSplit(R.id.layoutFadeSplit)
        bindTree()
    }

    private fun showCard() {
        val card = queue.getOrNull(index) ?: run {
            Toast.makeText(this, "목차암기 ${queue.size}장 끝", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        roots = parseOutline(card.back)
        depth = outlineDepth(roots)
        throughLevel = -1
        writeMode = false
        StudyProgressStore.markTopic(this, card.subject, card.topicTitle)
        findViewById<TextView>(R.id.tvFadeProgress).text = "${index + 1} / ${queue.size}"
        findViewById<TextView>(R.id.tvFadeSubject).text = canonicalizeSubject(card.subject)
        findViewById<TextView>(R.id.tvFadeTitle).text = card.topicTitle.ifBlank { card.title }
        findViewById<TextView>(R.id.tvFadeCoach).text = CoachHints.FADE
        findViewById<EditText>(R.id.etFadeSentence).setText("")
        findViewById<TextView>(R.id.tvFadeAnswer).visibility = View.GONE
        bindTree()
        bindStep()
    }

    private fun bindTree() {
        val card = queue.getOrNull(index) ?: return
        findViewById<OutlineTreeView>(R.id.viewFadeTree).setOutline(
            card.topicTitle.ifBlank { card.title },
            maskOutline(roots, throughLevel),
            isLandscape()
        )
    }

    private fun bindStep() {
        val step = findViewById<TextView>(R.id.tvFadeStep)
        val reveal = findViewById<MaterialButton>(R.id.btnFadeReveal)
        val check = findViewById<View>(R.id.btnFadeCheck)
        val write = findViewById<EditText>(R.id.etFadeSentence)
        if (writeMode) {
            step.text = "제목만 보고 목차 아래 문장까지 쓴 뒤 대조하세요."
            reveal.visibility = View.GONE
            check.visibility = View.VISIBLE
            write.visibility = View.VISIBLE
        } else {
            step.text = if (throughLevel < 0) "제목만 보고 대목차를 말해 보세요."
            else "열린 가지의 아래를 말한 뒤 한 단계 여세요."
            reveal.visibility = View.VISIBLE
            reveal.text = "한 단계 열기"
            check.visibility = View.GONE
            write.visibility = View.GONE
        }
    }

    private fun revealNext() {
        if (throughLevel >= depth) {
            writeMode = true
            bindStep()
            return
        }
        throughLevel++
        bindTree()
        if (throughLevel >= depth) {
            writeMode = true
        }
        bindStep()
    }

    private fun showAnswer() {
        val tv = findViewById<TextView>(R.id.tvFadeAnswer)
        tv.text = fadePromptBody(roots)
        tv.visibility = View.VISIBLE
    }

    private fun nextCard() {
        index++
        showCard()
    }
}
