package com.example.adminmemo

import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

class OutlineFadeActivity : BaseActivity() {

    override val showGeminiFab = true

    companion object {
        const val EXTRA_IDS = "extra_fade_ids"
        const val EXTRA_INDEX = "extra_fade_index"
    }

    private var queue: List<Card> = emptyList()
    private var index = 0
    private var roots: List<OutlineNode> = emptyList()
    private var depth = -1
    private var throughLevel = -1
    private var writeMode = false
    private var answerOn = false
    private var portraitTreeOpen = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = loadQueue()
        if (queue.isEmpty()) {
            Toast.makeText(this, "목차로 외울 장이 없어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_outline_fade)
        bindFadePages()
        confirmLeaveOnBack("목차학습을 그만둘까요?") { index < queue.size }
        findViewById<View>(R.id.btnFadeReveal).setOnClickListener { revealNext() }
        findViewById<View>(R.id.btnFadeCheck).setOnClickListener { showAnswer() }
        findViewById<View>(R.id.btnFadeNext).setOnClickListener { askNextCard() }
        findViewById<View>(R.id.tvFadeShowTree).setOnClickListener {
            portraitTreeOpen = true
            bindChrome()
        }
        showCard()
    }

    private fun loadQueue(): List<Card> {
        val ids = intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()
        if (ids.isEmpty()) return RecallStore.fadeQueue(this, Int.MAX_VALUE)
        val byId = CardStore.getAllCards(this).associateBy { it.id }
        val cards = ids.mapNotNull { byId[it] }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (cards.size - 1).coerceAtLeast(0))
        return cards
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindFadePages()
        bindCoach(R.id.tvFadeCoach, "fade", CoachHints.FADE)
        bindTree()
        bindStep()
        bindChrome()
        findViewById<View>(R.id.scrollFadeTree).post {
            bindTree()
            bindStep()
            bindChrome()
        }
    }

    private fun showCard() {
        val card = queue.getOrNull(index) ?: run {
            Toast.makeText(this, "목차학습 ${queue.size}장 끝", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        roots = parseOutline(card.back)
        depth = outlineDepth(roots)
        throughLevel = -1
        writeMode = false
        answerOn = false
        portraitTreeOpen = true
        StudyProgressStore.markTopic(this, card.subject, card.topicTitle)
        findViewById<TextView>(R.id.tvFadeProgress).text = "${index + 1} / ${queue.size}"
        findViewById<TextView>(R.id.tvFadeSubject).text = canonicalizeSubject(card.subject)
        findViewById<TextView>(R.id.tvFadeTitle).text = card.topicTitle.ifBlank { card.title }
        bindCoach(R.id.tvFadeCoach, "fade", CoachHints.FADE)
        findViewById<TextView>(R.id.tvFadeAnswer).text = ""
        bindTree()
        bindStep()
        bindChrome()
    }

    private fun bindFadePages() {
        val land = isLandscape()
        val split = findViewById<LinearLayout>(R.id.layoutFadeSplit)
        val tree = findViewById<View>(R.id.scrollFadeTree)
        val right = findViewById<View>(R.id.layoutFadeRightPage)
        split.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val gutter = if (land) (40 * resources.displayMetrics.density).toInt() else 0
        fun pane(v: View, first: Boolean) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.width = if (land) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = if (land) ViewGroup.LayoutParams.MATCH_PARENT
            else if (first) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            lp.weight = if (land || first) 1f else 0f
            lp.marginStart = if (land && !first) gutter else 0
            v.layoutParams = lp
        }
        pane(tree, true)
        pane(right, false)
    }

    private fun bindChrome() {
        val land = isLandscape()
        val tree = findViewById<View>(R.id.scrollFadeTree)
        val right = findViewById<View>(R.id.layoutFadeRightPage)
        val answerScroll = findViewById<ScrollView>(R.id.scrollFadeAnswer)
        val showTree = findViewById<View>(R.id.tvFadeShowTree)
        val coach = findViewById<View>(R.id.tvFadeCoach)
        answerScroll.visibility = if (answerOn) View.VISIBLE else View.GONE
        val spacer = findViewById<View>(R.id.fadeRightSpacer)
        val spacerLp = spacer.layoutParams as LinearLayout.LayoutParams
        if (land && !answerOn) {
            spacer.visibility = View.VISIBLE
            spacerLp.height = 0
            spacerLp.weight = 1f
        } else {
            spacer.visibility = View.GONE
            spacerLp.height = 0
            spacerLp.weight = 0f
        }
        spacer.layoutParams = spacerLp
        if (!land) coach.visibility = View.GONE
        val hideTree = answerOn && !land && !portraitTreeOpen
        tree.visibility = if (hideTree) View.GONE else View.VISIBLE
        showTree.visibility = if (hideTree) View.VISIBLE else View.GONE
        val rightLp = right.layoutParams as LinearLayout.LayoutParams
        if (land) {
            rightLp.width = 0
            rightLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            rightLp.weight = 1f
        } else if (answerOn) {
            rightLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            rightLp.height = 0
            rightLp.weight = 1f
        } else {
            rightLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            rightLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            rightLp.weight = 0f
        }
        right.layoutParams = rightLp
        if (!land) {
            val treeLp = tree.layoutParams as LinearLayout.LayoutParams
            if (hideTree) {
                treeLp.height = 0
                treeLp.weight = 0f
            } else {
                treeLp.height = 0
                treeLp.weight = 1f
            }
            tree.layoutParams = treeLp
        }
        bindFadeButtons()
    }

    private fun bindFadeButtons() {
        val land = isLandscape()
        val floor = resources.getDimensionPixelSize(R.dimen.btn_min_height)
        val h = if (land) floor * 2 else floor
        val d = resources.displayMetrics.density
        val padV = (8 * d).toInt()
        listOf(R.id.btnFadeReveal, R.id.btnFadeCheck, R.id.btnFadeNext).forEach { id ->
            val btn = findViewById<MaterialButton>(id)
            btn.insetTop = 0
            btn.insetBottom = 0
            btn.includeFontPadding = false
            btn.gravity = Gravity.CENTER
            btn.minHeight = h
            btn.minimumHeight = h
            btn.isSingleLine = false
            btn.maxLines = 2
            btn.textSize = if (land) 15f else 14f
            btn.setPaddingRelative(btn.paddingStart, padV, btn.paddingEnd, padV)
            val lp = btn.layoutParams as LinearLayout.LayoutParams
            lp.height = h
            btn.layoutParams = lp
        }
    }

    private fun askNextCard() {
        val last = index >= queue.size - 1
        confirmAdvance(
            if (last) "목차학습을 끝낼까요?" else "다음 장으로 넘어갈까요??",
            if (last) "끝내기" else "넘어가기"
        ) { nextCard() }
    }

    private fun bindTree() {
        val card = queue.getOrNull(index) ?: return
        val tree = findViewById<OutlineTreeView>(R.id.viewFadeTree)
        tree.setOutline(
            card.topicTitle.ifBlank { card.title },
            maskOutline(roots, throughLevel),
            true
        )
        tree.post {
            tree.requestLayout()
            (tree.parent as? View)?.requestLayout()
        }
    }

    private fun bindStep() {
        val step = findViewById<TextView>(R.id.tvFadeStep)
        val reveal = findViewById<MaterialButton>(R.id.btnFadeReveal)
        val check = findViewById<View>(R.id.btnFadeCheck)
        if (writeMode) {
            step.visibility = View.GONE
            reveal.visibility = View.GONE
            check.visibility = View.VISIBLE
        } else {
            step.visibility = View.VISIBLE
            step.text = if (throughLevel < 0) {
                "제목만 보고 목차를 말한 뒤 한 단계 여세요."
            } else {
                "열린 가지의 아래를 말한 뒤 한 단계 여세요."
            }
            reveal.visibility = View.VISIBLE
            reveal.text = "한 단계 열기"
            check.visibility = View.GONE
        }
    }

    private fun revealNext() {
        if (throughLevel >= depth) {
            writeMode = true
            bindStep()
            bindChrome()
            return
        }
        throughLevel++
        bindTree()
        if (throughLevel >= depth) {
            writeMode = true
        }
        bindStep()
        bindChrome()
    }

    private fun showAnswer() {
        val card = queue.getOrNull(index)
        val raw = card?.back.orEmpty().ifBlank { "본문 내용이 없어요" }
        findViewById<TextView>(R.id.tvFadeAnswer).text = styledStudyAnswer(
            this,
            raw,
            keepTypedBreaks = card != null && CardStore.isLocallyEdited(this, card.id)
        )
        answerOn = true
        portraitTreeOpen = true
        bindStep()
        bindChrome()
    }

    private fun nextCard() {
        index++
        showCard()
    }
}
