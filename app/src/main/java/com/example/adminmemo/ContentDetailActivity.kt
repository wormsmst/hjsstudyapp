package com.example.adminmemo

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

class ContentDetailActivity : BaseActivity() {

    override val showScratchPad = true
    override val scratchPadInToolbar = true
    override val showGeminiFab = true

    override fun geminiStudyContext(): String {
        val card = if (::ids.isInitialized) currentCard() else null
        return card?.let { "${it.topicTitle}\n${it.back}" }.orEmpty()
    }

    companion object {
        const val EXTRA_IDS = "extra_ids"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_QUEST_ID = "extra_quest_id"
        private const val REQ_NOTIF = 4302
    }

    private lateinit var ids: List<String>
    private var index = 0

    private lateinit var tvProgress: TextView
    private lateinit var tvGrade: TextView
    private lateinit var tvTitle: TextView
    private lateinit var cardMnemonicBox: CardView
    private lateinit var tvMnemonic: TextView
    private lateinit var tvBody: TextView
    private lateinit var tvBodyRight: TextView
    private lateinit var etMemo: EditText
    private lateinit var memoryStars: List<TextView>
    private lateinit var btnSpeak: ImageButton
    private var showOutlineTree = false
    private var visitCardId = ""
    private var bodyAccumMs = 0L
    private var bodyTick = 0L
    private var bodyWasShown = false
    private var maxLeftScroll = 0f
    private var seenRightPage = false
    private var scrollWired = false

    private val ttsListener: (StudyTtsState) -> Unit = { st ->
        if (st.ids.isNotEmpty() && st.ids == ids && st.index in ids.indices && st.index != index) {
            saveCurrentMemoSilently()
            index = st.index
            render()
        } else {
            refreshSpeakButton()
            updateProgressLabel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_detail)
        bindBookLayout()
        applyIntent(intent)

        tvProgress = findViewById(R.id.tvDetailProgress)
        tvProgress.setOnLongClickListener {
            undoTodayReadPass()
            true
        }
        tvGrade = findViewById(R.id.tvDetailGrade)
        tvGrade.setOnClickListener {
            val id = currentCard()?.id ?: return@setOnClickListener
            ExamChanceStore.pick(this, id) { render() }
        }
        tvTitle = findViewById(R.id.tvDetailTitle)
        cardMnemonicBox = findViewById(R.id.cardMnemonicBox)
        tvMnemonic = findViewById(R.id.tvDetailMnemonic)
        tvBody = findViewById(R.id.tvDetailBody)
        tvBodyRight = findViewById(R.id.tvDetailBodyRight)
        enableGeminiSelection(this, tvBody) { currentCard()?.back ?: "" }
        enableGeminiSelection(this, tvBodyRight) { currentCard()?.back ?: "" }
        enableGeminiSelection(this, tvMnemonic) { currentCard()?.let { "${it.topicTitle}\n${it.mnemonic}\n${it.back}" } ?: "" }
        etMemo = findViewById(R.id.etMemo)
        btnSpeak = findViewById(R.id.btnSpeak)

        memoryStars = listOf(
            findViewById(R.id.star1), findViewById(R.id.star2), findViewById(R.id.star3),
            findViewById(R.id.star4), findViewById(R.id.star5)
        )
        memoryStars.forEach { star ->
            star.isClickable = false
            star.isFocusable = false
        }

        btnSpeak.setOnClickListener { onSpeakClicked() }
        findViewById<ImageButton>(R.id.btnScratchPad).setOnClickListener { toggleScratchPad() }

        findViewById<ImageButton>(R.id.btnEditDetail).setOnClickListener {
            StudyTtsService.stop(this)
            showEditDialog()
        }
        findViewById<ImageButton>(R.id.btnOutlineTree).setOnClickListener {
            showOutlineTree = !showOutlineTree
            render()
        }

        findViewById<Button>(R.id.btnPrevDetail).setOnClickListener {
            saveCurrentMemoSilently()
            moveCard(-1)
        }
        findViewById<Button>(R.id.btnNextDetail).setOnClickListener {
            saveCurrentMemoSilently()
            moveCard(1)
        }
        findViewById<Button>(R.id.btnSaveMemo).setOnClickListener {
            saveCurrentMemoSilently()
            Toast.makeText(this, "메모를 저장했어요", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnQuestWrite).setOnClickListener {
            val card = currentCard() ?: return@setOnClickListener
            startActivity(
                Intent(this, RecallActivity::class.java)
                    .putStringArrayListExtra(EXTRA_RECALL_CARD_IDS, arrayListOf(card.id))
                    .putExtra(
                        EXTRA_RECALL_BODY,
                        DailyQuestStore.openQuestForCard(this, card.id)?.type == DailyQuestStore.TYPE_BODY
                    )
            )
        }

        StudyTtsHub.addListener(ttsListener)
        wireReadPassScroll()
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::etMemo.isInitialized) saveCurrentMemoSilently()
        bindBookLayout()
        if (::tvBody.isInitialized) render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
        render()
        refreshSpeakButton()
    }

    private fun applyIntent(intent: Intent) {
        ids = intent.getStringArrayListExtra(EXTRA_IDS) ?: arrayListOf()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, ids.size - 1))
        val st = StudyTtsHub.state
        if (st.ids == ids && st.index in ids.indices) {
            index = st.index
        }
    }

    private fun onSpeakClicked() {
        val st = StudyTtsHub.state
        if (st.ids == ids && st.index == index && (st.playing || st.paused)) {
            StudyTtsService.toggle(this)
        } else {
            startPlaylist()
        }
    }

    private fun startPlaylist() {
        if (ids.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        StudyTtsService.play(this, ids, index)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) StudyTtsService.play(this, ids, index)
    }

    private fun moveCard(delta: Int) {
        val st = StudyTtsHub.state
        val listening = st.ids == ids && (st.playing || st.paused)
        if (listening) {
            StudyTtsService.skip(this, delta)
            return
        }
        val next = index + delta
        if (next in ids.indices) {
            index = next
            render()
        }
    }

    private fun refreshSpeakButton() {
        val st = StudyTtsHub.state
        val onThis = st.ids == ids && st.index == index && st.playing
        btnSpeak.setImageResource(
            if (onThis) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_lock_silent_mode_off
        )
    }

    private fun updateProgressLabel() {
        if (!::tvProgress.isInitialized) return
        tvProgress.text = progressLabel()
    }

    private fun progressLabel(): String {
        val base = "${index + 1} / ${ids.size}"
        val st = StudyTtsHub.state
        val onThis = st.ids == ids && st.index == index && (st.playing || st.paused)
        val tts = if (onThis && st.repeat > 1) "  ·  ${st.pass}/${st.repeat}회" else ""
        val id = ids.getOrNull(index).orEmpty()
        val n = if (id.isBlank()) 0 else ReadPassStore.count(this, id)
        val today = if (id.isNotBlank() && ReadPassStore.countedToday(this, id)) " · 오늘" else ""
        return "$base$tts  ·  읽음 ${n}회$today"
    }

    private fun bindBookLayout() {
        val split = findViewById<LinearLayout>(R.id.layoutDetailSplit) ?: return
        val leftInner = findViewById<LinearLayout>(R.id.layoutDetailLeftInner) ?: return
        val rightInner = findViewById<LinearLayout>(R.id.layoutDetailRightInner) ?: return
        val tools = findViewById<LinearLayout>(R.id.layoutDetailTools) ?: return
        val rightScroll = findViewById<View>(R.id.scrollDetailRight) ?: return
        val gutter = findViewById<View>(R.id.viewDetailGutter)
        val land = isLandscape() && !showOutlineTree
        split.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            (tools.parent as? ViewGroup)?.removeView(tools)
            tools.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            val leftScroll = split.getChildAt(0)
        if (land) {
            rightInner.addView(tools)
            rightScroll.visibility = View.VISIBLE
            gutter?.visibility = View.VISIBLE
            leftScroll.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            rightScroll.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gutter?.layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.density).toInt().coerceAtLeast(1),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        } else {
            leftInner.addView(tools)
            rightScroll.visibility = View.GONE
            gutter?.visibility = View.GONE
            leftScroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
    }

    private fun currentCard(): Card? {
        if (ids.isEmpty()) return null
        val id = ids[index]
        return CardStore.getAllCards(this).firstOrNull { it.id == id }
    }

    private fun render() {
        val card = currentCard() ?: run {
            tvTitle.text = "카드를 찾을 수 없어요"
            tvBody.text = ""
            return
        }
        beginReadVisit(card.id)
        tvProgress.text = progressLabel()
        tvGrade.text = ExamChanceStore.label(card.grade)
        tvTitle.text = card.topicTitle

        if (card.mnemonic.isNotBlank()) {
            cardMnemonicBox.visibility = View.VISIBLE
            tvMnemonic.text = "🔑 두문자: ${card.mnemonic}"
        } else {
            cardMnemonicBox.visibility = View.GONE
        }

        val treeBtn = findViewById<ImageButton>(R.id.btnOutlineTree)
        val treeScroll = findViewById<View>(R.id.scrollOutlineTree)
        val treeView = findViewById<OutlineTreeView>(R.id.viewOutlineTree)
        if (showOutlineTree) {
            val roots = try {
                parseOutline(card.back).filter { it.level >= 0 }
            } catch (_: Exception) {
                emptyList()
            }
            treeView.setOutline(card.topicTitle, roots, isLandscape())
            treeScroll.visibility = View.VISIBLE
            tvBody.visibility = View.GONE
            tvBodyRight.visibility = View.GONE
            treeBtn.setImageResource(R.drawable.ic_outline_body)
            treeBtn.contentDescription = "본문 보기"
            pauseBodyClock()
            bindBookLayout()
        } else {
            treeScroll.visibility = View.GONE
            tvBody.visibility = View.VISIBLE
            resumeBodyClock()
            treeBtn.setImageResource(R.drawable.ic_outline_tree)
            treeBtn.contentDescription = "목차 트리"
            bindBookLayout()
            val formatted = formatStudyOutlineText(
                displayStudyBody(
                    card.back.ifBlank { "본문 내용이 없어요" },
                    keepTypedBreaks = CardStore.isLocallyEdited(this, card.id)
                )
            )
            if (isLandscape()) {
                applyLandscapeSpread(formatted)
            } else {
                tvBody.text = buildStyledStudyBody(this, formatted)
                tvBodyRight.visibility = View.GONE
                tvBodyRight.text = ""
            }
        }
        etMemo.setText(CardStore.getMemo(this, card.id))
        renderMemoryStars(CardStore.getMemoryLevel(this, card.subject, card.topicTitle))
        StudyProgressStore.markTopic(this, card.subject, card.topicTitle)

        findViewById<Button>(R.id.btnPrevDetail).isEnabled = index > 0
        findViewById<Button>(R.id.btnNextDetail).isEnabled = index < ids.size - 1
        refreshSpeakButton()
        bindQuestWrite()
    }

    private fun applyLandscapeSpread(formatted: String) {
        val leftInner = findViewById<LinearLayout>(R.id.layoutDetailLeftInner) ?: return
        tvBody.post {
            val d = resources.displayMetrics
            val width = tvBody.width.takeIf { it > 0 }
                ?: ((d.widthPixels / 2) - (40 * d.density).toInt()).coerceAtLeast(1)
            var chrome = leftInner.paddingTop + leftInner.paddingBottom
            val bodyIndex = leftInner.indexOfChild(tvBody)
            for (i in 0 until bodyIndex) {
                val child = leftInner.getChildAt(i)
                if (child.visibility == View.GONE) continue
                val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
                val h = when {
                    child.height > 0 -> child.height
                    child.measuredHeight > 0 -> child.measuredHeight
                    else -> {
                        child.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        child.measuredHeight
                    }
                }
                chrome += h + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            }
            val bodyLp = tvBody.layoutParams as? ViewGroup.MarginLayoutParams
            chrome += bodyLp?.topMargin ?: 0
            val cap = (d.heightPixels * 0.85f).toInt()
            val maxBody = (cap - chrome).coerceAtLeast((120 * d.density).toInt())
            val pages = splitStudySpread(this, formatted, tvBody, width, maxBody)
            tvBody.text = buildStyledStudyBody(this, pages.first)
            if (pages.second.isBlank()) {
                tvBodyRight.visibility = View.GONE
                tvBodyRight.text = ""
            } else {
                tvBodyRight.visibility = View.VISIBLE
                tvBodyRight.text = buildStyledStudyBody(this, pages.second)
            }
        }
    }

    private fun bindQuestWrite() {
        val btn = findViewById<Button>(R.id.btnQuestWrite)
        val card = currentCard()
        val quest = card?.let { DailyQuestStore.openQuestForCard(this, it.id) }
        if (quest == null) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        btn.text = "${DailyQuestStore.kindLabel(quest)}  ·  ${quest.progress}/${quest.target}"
    }

    private fun renderMemoryStars(level: Int) {
        memoryStars.forEachIndexed { i, star ->
            star.text = if (i < level) "●" else "○"
            star.setTextColor(
                if (i < level) ContextCompat.getColor(this, R.color.mem_master)
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
        ExamChanceStore.attachPicker(view.findViewById(R.id.tvExamChance), card.id) { render() }

        AlertDialog.Builder(this)
            .setTitle("내용 수정")
            .setView(view)
            .showWithEditConfirms(this, onSave = {
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
                true
            })
    }

    private fun saveCurrentMemoSilently() {
        val card = currentCard() ?: return
        CardStore.setMemo(this, card.id, etMemo.text.toString())
    }

    override fun onPause() {
        pauseBodyClock()
        maybeCommitReadPass()
        saveCurrentMemoSilently()
        super.onPause()
    }

    override fun onStop() {
        if (isFinishing) maybeCommitReadPass()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumeBodyClock()
    }

    override fun onDestroy() {
        StudyTtsHub.removeListener(ttsListener)
        super.onDestroy()
    }

    private fun wireReadPassScroll() {
        if (scrollWired) return
        scrollWired = true
        val left = findViewById<ScrollView>(R.id.scrollDetailLeft)
        left.viewTreeObserver.addOnScrollChangedListener {
            val child = left.getChildAt(0) ?: return@addOnScrollChangedListener
            val range = (child.height - left.height).coerceAtLeast(1)
            maxLeftScroll = maxOf(maxLeftScroll, left.scrollY.toFloat() / range)
        }
        val right = findViewById<ScrollView>(R.id.scrollDetailRight)
        right.viewTreeObserver.addOnScrollChangedListener {
            if (right.scrollY > 24) seenRightPage = true
            val child = right.getChildAt(0) ?: return@addOnScrollChangedListener
            if (child.height <= right.height + 24) seenRightPage = true
        }
    }

    private fun pauseBodyClock() {
        if (bodyTick > 0L) {
            bodyAccumMs += SystemClock.elapsedRealtime() - bodyTick
            bodyTick = 0L
        }
    }

    private fun resumeBodyClock() {
        if (showOutlineTree) return
        bodyWasShown = true
        if (bodyTick == 0L) bodyTick = SystemClock.elapsedRealtime()
    }

    private fun bodyMs(): Long {
        val extra = if (bodyTick > 0L) SystemClock.elapsedRealtime() - bodyTick else 0L
        return bodyAccumMs + extra
    }

    private fun dwellNeeded(card: Card): Long {
        val letters = card.back.count { !it.isWhitespace() }
        return (8_000L + (letters / 90) * 1_000L).coerceIn(8_000L, 36_000L)
    }

    private fun landscapeEnough(card: Card): Boolean {
        if (!isLandscape() || showOutlineTree) return true
        if (tvBodyRight.visibility != View.VISIBLE || tvBodyRight.text.isBlank()) return true
        val right = findViewById<ScrollView>(R.id.scrollDetailRight)
        val child = right.getChildAt(0)
        if (child != null && child.height <= right.height + 24) return true
        return seenRightPage || bodyMs() >= dwellNeeded(card) + 6_000L
    }

    private fun readPassQualifies(card: Card): Boolean {
        if (!bodyWasShown) return false
        if (!landscapeEnough(card)) return false
        val left = findViewById<ScrollView>(R.id.scrollDetailLeft)
        val child = left?.getChildAt(0)
        val range = if (child == null) 0 else child.height - left.height
        val shortBody = range <= (48 * resources.displayMetrics.density).toInt()
        val timeOk = bodyMs() >= dwellNeeded(card)
        return if (shortBody) timeOk else (maxLeftScroll >= 0.7f || timeOk)
    }

    private fun maybeCommitReadPass(cardId: String = visitCardId) {
        if (cardId.isBlank()) return
        val card = CardStore.getAllCards(this).firstOrNull { it.id == cardId } ?: return
        if (!readPassQualifies(card)) return
        if (ReadPassStore.tryMark(this, cardId)) updateProgressLabel()
    }

    private fun beginReadVisit(cardId: String) {
        if (visitCardId == cardId) {
            if (!showOutlineTree) resumeBodyClock()
            return
        }
        maybeCommitReadPass(visitCardId)
        visitCardId = cardId
        bodyAccumMs = 0L
        bodyTick = 0L
        bodyWasShown = false
        maxLeftScroll = 0f
        seenRightPage = false
        findViewById<ScrollView>(R.id.scrollDetailLeft)?.scrollTo(0, 0)
        if (!showOutlineTree) resumeBodyClock()
    }

    private fun undoTodayReadPass() {
        val card = currentCard() ?: return
        if (!ReadPassStore.countedToday(this, card.id)) {
            Toast.makeText(this, "오늘 읽음으로 센 기록이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("오늘 이 장 회독을 취소할까요?")
            .setPositiveButton("취소하기") { _, _ ->
                ReadPassStore.undoToday(this, card.id)
                updateProgressLabel()
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
