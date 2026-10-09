package com.example.adminmemo

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ContentManageActivity : BaseActivity() {

    private enum class Tab { CONCEPT, MNEMONIC, UNREGISTERED }

    private lateinit var subject: String
    private lateinit var adapter: ContentAdapter
    private var currentTab = Tab.CONCEPT
    private var query = ""

    private var conceptCards: List<Card> = emptyList()
    private var mnemonicCards: List<Card> = emptyList()
    private var unregisteredCards: List<Card> = emptyList()

    private lateinit var fab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_manage)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""

        val rv = findViewById<RecyclerView>(R.id.rvManageList)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ContentAdapter(
            titleFn = { it.topicTitle },
            subtitleFn = { card ->
                when (currentTab) {
                    Tab.MNEMONIC -> "두문자: ${card.mnemonic}"
                    else -> card.back.take(40).replace("\n", " ")
                }
            },
            onClick = { card -> onRowClick(card) }
        )
        rv.adapter = adapter

        val touchCallback = object : ItemTouchHelper.SimpleCallback(0, 0) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (currentTab == Tab.CONCEPT && query.isBlank()) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    0
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (currentTab == Tab.CONCEPT) {
                    val newOrder = adapter.currentList().map { it.id }
                    CardStore.setCustomOrder(this@ContentManageActivity, subject, newOrder)
                    conceptCards = sortConceptCards(this@ContentManageActivity, conceptCards, subject)
                }
            }
        }
        ItemTouchHelper(touchCallback).attachToRecyclerView(rv)

        fab = findViewById(R.id.fabManageAdd)
        fab.setOnClickListener {
            when (currentTab) {
                Tab.CONCEPT -> showAddConceptDialog()
                Tab.MNEMONIC -> showAddMnemonicDialog(prefillTopic = "")
                Tab.UNREGISTERED -> {}
            }
        }

        findViewById<Button>(R.id.btnTabConcept).setOnClickListener { switchTab(Tab.CONCEPT) }
        findViewById<Button>(R.id.btnTabMnemonic).setOnClickListener { switchTab(Tab.MNEMONIC) }
        findViewById<Button>(R.id.btnTabUnregistered).setOnClickListener { switchTab(Tab.UNREGISTERED) }

        findViewById<EditText>(R.id.etManageSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString() ?: ""
                findViewById<TextView>(R.id.tvReorderHint).visibility =
                    if (currentTab == Tab.CONCEPT && query.isBlank()) View.VISIBLE else View.GONE
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
        switchTab(Tab.CONCEPT)
    }

    override fun onResume() {
        super.onResume()
        loadData()
        refreshList()
    }

    private fun loadData() {
        val all = CardStore.getAllCards(this).filter { it.subject == subject }
        val rawConcepts = all.filter { it.type == "concept" }
        conceptCards = sortConceptCards(this, rawConcepts, subject)
        mnemonicCards = all.filter { it.type == "mnemonic" }
            .sortedWith(
                compareBy<Card>(
                    { numSortKey(it.num).first },
                    { numSortKey(it.num).second }
                ).thenComparator { a, b -> compareNatural(a.topicTitle, b.topicTitle) }
            )
        val mnemonicTopics = mnemonicCards.map { it.topicTitle }.toSet()
        unregisteredCards = conceptCards.filter { it.topicTitle !in mnemonicTopics }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        fab.visibility = if (tab == Tab.UNREGISTERED) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvReorderHint).visibility =
            if (tab == Tab.CONCEPT && query.isBlank()) View.VISIBLE else View.GONE
        refreshList()
    }

    private fun refreshList() {
        val base = when (currentTab) {
            Tab.CONCEPT -> conceptCards
            Tab.MNEMONIC -> mnemonicCards
            Tab.UNREGISTERED -> unregisteredCards
        }
        val q = query.trim()
        val filtered = if (q.isEmpty()) base else base.filter {
            it.topicTitle.contains(q, true) || it.back.contains(q, true) || it.mnemonic.contains(q, true)
        }
        adapter.submitList(filtered)
    }

    private fun onRowClick(card: Card) {
        when (currentTab) {
            Tab.CONCEPT -> showEditConceptDialog(card)
            Tab.MNEMONIC -> showEditMnemonicDialog(card)
            Tab.UNREGISTERED -> showAddMnemonicDialog(prefillTopic = card.topicTitle, sourceCard = card)
        }
    }

    // ---- 새 주제(개념카드) 추가 ----
    private fun showAddConceptDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_concept, null)
        val etTitle = view.findViewById<EditText>(R.id.etConceptTitle)
        val etBody = view.findViewById<EditText>(R.id.etConceptBody)
        view.findViewById<TextView>(R.id.tvExamChance).visibility = View.GONE

        AlertDialog.Builder(this)
            .setTitle("새 주제 추가")
            .setView(view)
            .setPositiveButton("추가") { _, _ ->
                val title = etTitle.text.toString().trim()
                val body = etBody.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(this, "제목을 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CardStore.addConceptCard(this, subject, title, body)
                loadData(); refreshList()
                Toast.makeText(this, "새 주제를 추가했어요. 맨 아래에 있어요 — 길게 눌러서 순서를 옮길 수 있어요", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---- 개념카드 편집 ----
    private fun showEditConceptDialog(card: Card) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_concept, null)
        val etTitle = view.findViewById<EditText>(R.id.etConceptTitle)
        val etBody = view.findViewById<EditText>(R.id.etConceptBody)
        etTitle.setText(card.topicTitle)
        etBody.setText(card.back)
        ExamChanceStore.attachPicker(view.findViewById(R.id.tvExamChance), card.id)

        AlertDialog.Builder(this)
            .setTitle("개념카드 수정")
            .setView(view)
            .showWithEditConfirms(
                this,
                onSave = {
                    val newTitle = etTitle.text.toString().trim().ifEmpty { "(제목없음)" }
                    val newBody = etBody.text.toString().trim()
                    val updated = card.copy(
                        topicTitle = newTitle,
                        title = "${card.num} $newTitle".trim(),
                        front = if (card.grade.isNotBlank()) "$newTitle\n(${card.grade})" else newTitle,
                        back = newBody
                    )
                    CardStore.updateCard(this, updated)
                    loadData(); refreshList()
                    Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
                    true
                },
                onDelete = {
                    CardStore.deleteCard(this, card.id)
                    loadData(); refreshList()
                }
            )
    }

    // ---- 두문자 편집 ----
    private fun showEditMnemonicDialog(card: Card) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_mnemonic, null)
        val etTopic = view.findViewById<EditText>(R.id.etTopicTitle)
        val etMnemonic = view.findViewById<EditText>(R.id.etMnemonic)
        val etContext = view.findViewById<EditText>(R.id.etContext)
        etTopic.setText(card.topicTitle)
        etMnemonic.setText(card.mnemonic)
        etContext.setText(card.back)

        AlertDialog.Builder(this)
            .setTitle("두문자 수정")
            .setView(view)
            .showWithEditConfirms(
                this,
                onSave = {
                    val topic = etTopic.text.toString().trim().ifEmpty { "(제목없음)" }
                    val mnemonic = etMnemonic.text.toString().trim()
                    val ctx = etContext.text.toString().trim()
                    if (mnemonic.isEmpty()) {
                        Toast.makeText(this, "두문자를 입력해주세요", Toast.LENGTH_SHORT).show()
                        false
                    } else {
                        val updated = card.copy(
                            topicTitle = topic,
                            mnemonic = mnemonic,
                            title = "두문자: $mnemonic",
                            front = "[$topic]\n두문자 '$mnemonic' 은(는) 무엇의 앞글자일까?",
                            back = ctx,
                            mnemonics = listOf(mnemonic)
                        )
                        CardStore.updateCard(this, updated)
                        loadData(); refreshList()
                        true
                    }
                },
                onDelete = {
                    CardStore.deleteCard(this, card.id)
                    loadData(); refreshList()
                }
            )
    }

    // ---- 새 두문자 추가 (두문자미등록 탭 또는 FAB) ----
    private fun showAddMnemonicDialog(prefillTopic: String, sourceCard: Card? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_mnemonic, null)
        val etTopic = view.findViewById<EditText>(R.id.etTopicTitle)
        val etMnemonic = view.findViewById<EditText>(R.id.etMnemonic)
        val etContext = view.findViewById<EditText>(R.id.etContext)
        etTopic.setText(prefillTopic)
        if (sourceCard != null && etContext.text.isBlank()) {
            // 본문 앞부분을 힌트로 살짝 채워준다
        }

        AlertDialog.Builder(this)
            .setTitle(if (prefillTopic.isNotEmpty()) "'$prefillTopic'에 두문자 추가" else "두문자 추가")
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                val topic = etTopic.text.toString().trim().ifEmpty { "(제목없음)" }
                val mnemonic = etMnemonic.text.toString().trim()
                val ctx = etContext.text.toString().trim()
                if (mnemonic.isEmpty()) {
                    Toast.makeText(this, "두문자를 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CardStore.addMnemonicCard(
                    this, topicTitle = topic, mnemonic = mnemonic, contextText = ctx,
                    subject = sourceCard?.subject ?: subject,
                    num = sourceCard?.num ?: ""
                )
                loadData(); refreshList()
                Toast.makeText(this, "두문자를 추가했어요", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
