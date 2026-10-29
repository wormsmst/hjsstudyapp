package com.example.adminmemo

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ContentStudyListActivity : BaseActivity() {

    companion object {
        const val EXTRA_OUTLINE_MODE = "extra_outline_mode"
        const val EXTRA_WEAK_STUDY = "extra_weak_study"
        private const val SUBJECT_ALL = ""
    }

    private lateinit var adapter: ContentAdapter
    private var allConcepts: List<Card> = emptyList()
    private lateinit var subject: String
    private var outlineMode = false
    private var weakStudy = false
    private var currentQuery: String = ""
    private var weakSubject: String = SUBJECT_ALL
    private var weakNewestFirst = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_study_list)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""
        outlineMode = intent.getBooleanExtra(EXTRA_OUTLINE_MODE, false)
        weakStudy = intent.getBooleanExtra(EXTRA_WEAK_STUDY, false)
        val headerIcon = findViewById<android.widget.ImageView>(R.id.ivStudyListIcon)
        findViewById<android.widget.TextView>(R.id.tvStudyListHeader)?.text = when {
            outlineMode && weakStudy -> {
                headerIcon?.visibility = android.view.View.GONE
                "약점학습"
            }
            outlineMode -> {
                headerIcon?.visibility = android.view.View.GONE
                "목차학습"
            }
            weakStudy -> {
                headerIcon?.visibility = android.view.View.GONE
                "약점학습"
            }
            else -> {
                headerIcon?.visibility = android.view.View.VISIBLE
                "본문학습"
            }
        }

        val rv = findViewById<RecyclerView>(R.id.rvContentList)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ContentAdapter(
            titleFn = { it.topicTitle },
            subtitleFn = {
                when {
                    weakStudy -> {
                        val ago = RecallStore.weakAgoLabel(this, it.id)
                        val sub = canonicalizeSubject(it.subject)
                        if (ago.isBlank()) sub else "$sub  ·  $ago"
                    }
                    outlineMode -> ""
                    else -> {
                        if (it.mnemonic.isNotBlank()) "두문자: ${it.mnemonic}" else ""
                    }
                }
            },
            memoryFn = if (outlineMode) null else { card ->
                CardStore.memoryStarsLabel(CardStore.getMemoryLevel(this, card.subject, card.topicTitle))
            },
            readsFn = if (outlineMode || weakStudy) null else { card ->
                val pass = ReadPassStore.count(this, card.id)
                if (pass > 0) "${pass}회" else ""
            },
            onClick = { card -> openCard(card.id) }
        )
        rv.adapter = adapter

        loadConcepts()
        bindWeakFilters()
        applyFilter(currentQuery)
        if (outlineMode && allConcepts.isEmpty()) {
            Toast.makeText(this, "목차로 외울 장이 없어요", Toast.LENGTH_SHORT).show()
        }

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        loadConcepts()
        bindWeakFilters()
        applyFilter(currentQuery)
    }

    private fun loadConcepts() {
        allConcepts = if (weakStudy) {
            RecallStore.weakStudyCards(this, limit = 200, mixSubjects = false)
        } else {
            sortConceptCards(
                this,
                CardStore.getAllCards(this).filter { it.type == "concept" && it.subject == subject },
                subject
            )
        }
        if (outlineMode) {
            allConcepts = allConcepts.filter { hasOutlineTree(it.back) }
        }
        if (weakStudy && weakSubject != SUBJECT_ALL) {
            val still = allConcepts.any { canonicalizeSubject(it.subject) == weakSubject }
            if (!still) weakSubject = SUBJECT_ALL
        }
    }

    private fun bindWeakFilters() {
        val bar = findViewById<View>(R.id.layoutWeakFilters)
        if (!weakStudy) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        bindSubjectChips()
        bindSortChips()
    }

    private fun bindSubjectChips() {
        val box = findViewById<LinearLayout>(R.id.layoutWeakSubjects)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val subjects = orderedSubjects(allConcepts.map { it.subject })
        val labels = listOf("전체" to SUBJECT_ALL) + subjects.map { it to it }
        labels.forEach { (label, key) ->
            val n = if (key == SUBJECT_ALL) {
                allConcepts.size
            } else {
                allConcepts.count { canonicalizeSubject(it.subject) == key }
            }
            box.addView(
                pill(
                    "$label $n",
                    key == weakSubject,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (8 * d).toInt() }
                ) {
                    weakSubject = key
                    bindWeakFilters()
                    applyFilter(currentQuery)
                }
            )
        }
    }

    private fun bindSortChips() {
        val box = findViewById<LinearLayout>(R.id.layoutWeakSort)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val gap = (8 * d).toInt()
        fun add(label: String, newest: Boolean) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = gap }
            box.addView(
                pill(label, newest == weakNewestFirst, lp) {
                    weakNewestFirst = newest
                    bindSortChips()
                    applyFilter(currentQuery)
                }
            )
        }
        add("최신순", true)
        add("오래된순", false)
    }

    private fun pill(
        label: String,
        on: Boolean,
        lp: LinearLayout.LayoutParams,
        click: () -> Unit
    ): TextView {
        val d = resources.displayMetrics.density
        val tv = TextView(this)
        tv.layoutParams = lp
        tv.gravity = Gravity.CENTER
        tv.text = label
        tv.textSize = 13f
        tv.setTypeface(tv.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        tv.setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        tv.background = ContextCompat.getDrawable(
            this,
            if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off
        )
        tv.setTextColor(
            ContextCompat.getColor(this, if (on) android.R.color.white else R.color.text_main)
        )
        tv.setOnClickListener { click() }
        return tv
    }

    private fun applyFilter(query: String) {
        currentQuery = query
        val q = query.trim()
        var filtered = allConcepts
        if (weakStudy && weakSubject != SUBJECT_ALL) {
            filtered = filtered.filter { canonicalizeSubject(it.subject) == weakSubject }
        }
        if (q.isNotEmpty()) {
            filtered = filtered.filter {
                it.topicTitle.contains(q, ignoreCase = true) || it.back.contains(q, ignoreCase = true)
            }
        }
        if (weakStudy) {
            filtered = filtered.sortedWith(weakDateOrder())
        }
        adapter.submitList(filtered)
    }

    private fun weakDateOrder(): Comparator<Card> {
        val byDate = compareBy<Card> { at ->
            val t = RecallStore.lastWeakAt(this, at.id)
            if (t <= 0L) Long.MAX_VALUE else t
        }.thenBy { it.topicTitle }
        return if (weakNewestFirst) {
            compareByDescending<Card> { RecallStore.lastWeakAt(this, it.id) }
                .thenBy { it.topicTitle }
        } else {
            byDate
        }
    }

    private fun openCard(cardId: String) {
        if (outlineMode) {
            val ids = adapter.currentList().map { it.id }
            val idx = ids.indexOf(cardId).coerceAtLeast(0)
            startActivity(
                Intent(this, OutlineFadeActivity::class.java)
                    .putStringArrayListExtra(OutlineFadeActivity.EXTRA_IDS, ArrayList(ids))
                    .putExtra(OutlineFadeActivity.EXTRA_INDEX, idx)
            )
            return
        }
        val ids = adapter.currentList().map { it.id }
        val idx = ids.indexOf(cardId)
        val intent = Intent(this, ContentDetailActivity::class.java)
        intent.putExtra(ContentDetailActivity.EXTRA_IDS, ArrayList(ids))
        intent.putExtra(ContentDetailActivity.EXTRA_INDEX, idx)
        startActivity(intent)
    }
}
