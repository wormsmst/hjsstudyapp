package com.example.adminmemo

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

/**
 * 목차학습: 대목차 -> 중목차 -> 소목차 -> 본문 순으로 탭할 때마다 한 단계씩
 * 내려가며 보여주는 화면. 더 내려갈 목차가 없으면 그 항목의 실제 내용을 보여준다.
 */
class OutlineDrillActivity : BaseActivity() {

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"
    }

    private lateinit var card: Card
    private lateinit var roots: List<OutlineNode>
    private val stack = mutableListOf<OutlineNode>()

    private lateinit var tvBreadcrumb: TextView
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_outline_drill)

        tvBreadcrumb = findViewById(R.id.tvOutlineBreadcrumb)
        container = findViewById(R.id.outlineContainer)

        val cardId = intent.getStringExtra(EXTRA_CARD_ID)
        val found = CardStore.getAllCards(this).firstOrNull { it.id == cardId }
        if (found == null) {
            Toast.makeText(this, "카드를 찾을 수 없어요", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        card = found
        roots = parseOutline(card.back).filter { it.level >= 0 }

        findViewById<ImageButton>(R.id.btnOutlineBack).setOnClickListener { goBack() }

        render()
    }

    override fun onBackPressed() {
        if (stack.isNotEmpty()) {
            goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun goBack() {
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.size - 1)
            render()
        } else {
            finish()
        }
    }

    private fun currentChildren(): List<OutlineNode> = if (stack.isEmpty()) roots else stack.last().children

    private fun shortLabel(label: String): String {
        return if (label.length > 14) label.take(14) + "…" else label
    }

    private fun render() {
        val crumbs = mutableListOf(card.topicTitle)
        crumbs.addAll(stack.map { shortLabel(it.label) })
        tvBreadcrumb.text = crumbs.joinToString(" › ")

        container.removeAllViews()

        val node = stack.lastOrNull()
        val children = currentChildren()

        if (node != null && node.bodyText.isNotBlank()) {
            container.addView(makeIntroText(node.bodyText))
        }

        if (children.isEmpty()) {
            if (node != null) {
                container.addView(makeLeafCard(node))
            } else {
                val tv = TextView(this)
                tv.text = "이 주제에는 인식된 목차 구조가 없어요. 본문학습에서 전체 내용을 확인해주세요."
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
                tv.textSize = 14f
                container.addView(tv)
            }
        } else {
            children.forEach { child ->
                container.addView(makeRow(child))
            }
        }
    }

    private fun makeIntroText(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        tv.textSize = 15f
        tv.setLineSpacing(6f, 1f)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(16)
        tv.layoutParams = lp
        return tv
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun makeRow(node: OutlineNode): CardView {
        val cv = CardView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        cv.layoutParams = lp
        cv.radius = dp(16).toFloat()
        cv.cardElevation = dp(1).toFloat()
        cv.foreground = ContextCompat.getDrawable(this, android.R.drawable.list_selector_background)
        cv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(18), dp(16), dp(18), dp(16))

        val tv = TextView(this)
        tv.text = node.label
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        tv.textSize = 15f
        val hasChildren = node.children.isNotEmpty()
        if (hasChildren) tv.setTypeface(tv.typeface, Typeface.BOLD)
        val tvLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tv.layoutParams = tvLp

        val arrow = TextView(this)
        arrow.text = if (hasChildren) "›" else "📄"
        arrow.setTextColor(ContextCompat.getColor(this, R.color.primary))
        arrow.textSize = 18f

        row.addView(tv)
        row.addView(arrow)
        cv.addView(row)

        cv.setOnClickListener {
            stack.add(node)
            render()
        }
        return cv
    }

    private fun makeLeafCard(node: OutlineNode): CardView {
        val cv = CardView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        cv.layoutParams = lp
        cv.radius = dp(18).toFloat()
        cv.cardElevation = dp(1).toFloat()
        cv.setCardBackgroundColor(Color.parseColor("#1F2962FF"))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(20))

        val tvTitle = TextView(this)
        tvTitle.text = node.label
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.primary))
        tvTitle.setTypeface(tvTitle.typeface, Typeface.BOLD)
        tvTitle.textSize = 16f
        val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tlp.bottomMargin = dp(10)
        tvTitle.layoutParams = tlp

        val tvBody = TextView(this)
        tvBody.text = node.bodyText.ifBlank { "(내용 없음)" }
        tvBody.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        tvBody.textSize = 15f
        tvBody.setLineSpacing(dp(4).toFloat(), 1f)
        enableGeminiSelection(this, tvBody) { node.bodyText }

        col.addView(tvTitle)
        col.addView(tvBody)
        cv.addView(col)
        return cv
    }
}
