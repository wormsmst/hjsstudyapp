package com.example.adminmemo

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.core.content.ContextCompat

private val MAJOR_RE = Regex("^\\d+\\.\\s")
private val SUB1_RE = Regex("^\\d+\\)\\s")
private val SUB2_RE = Regex("^\\(\\d+\\)\\s")
private val CIRCLE_RE = Regex("^[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮]\\s?")
private val DASH_RE = Regex("^[-·]\\s")
private val ROMAN_RE = Regex("^[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[\\.．]\\s*")

/** 본문 글자는 그대로 두고, 화면에서 목차 양식만 입힌다. */
fun formatStudyOutlineText(text: String): String = normalizeNewlines(text)

/** 가로 펼침에서 페이지를 자를 수 있는 줄: 1. / Ⅰ. / 1) / (1). 목차와 그 아래 설명은 붙인다. */
private fun isSpreadBreakHeading(line: String): Boolean {
    val t = line.trimStart()
    return MAJOR_RE.containsMatchIn(t) ||
        ROMAN_RE.containsMatchIn(t) ||
        SUB1_RE.containsMatchIn(t) ||
        SUB2_RE.containsMatchIn(t)
}

/**
 * 가로 책 펼침: 왼쪽 본문이 화면 높이의 85%를 넘기 직전까지만 담고,
 * 넘치면 다음 1. · 1) · (1)부터 오른쪽으로 보낸다.
 * 그 목차 줄과 바로 아래 설명은 한 덩어리로 두고, ① · - 이하는 그 안에 붙인다.
 */
fun splitStudySpread(
    context: Context,
    text: String,
    sample: TextView,
    widthPx: Int,
    maxHeightPx: Int,
): Pair<String, String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || maxHeightPx <= 0) return trimmed to ""

    val lines = trimmed.split('\n')
    val blockStarts = mutableListOf(0)
    for (i in 1 until lines.size) {
        if (isSpreadBreakHeading(lines[i])) blockStarts.add(i)
    }
    val blocks = blockStarts.indices.map { bi ->
        val from = blockStarts[bi]
        val to = blockStarts.getOrElse(bi + 1) { lines.size }
        lines.subList(from, to).joinToString("\n")
    }
    if (blocks.size <= 1) return trimmed to ""

    fun heightOf(body: String): Int = measureStudyBodyHeight(context, body, sample, widthPx)

    var cutAt = blocks.size
    val leftParts = mutableListOf<String>()
    for (i in blocks.indices) {
        val candidate = (leftParts + blocks[i]).joinToString("\n")
        val h = heightOf(candidate)
        if (i > 0 && h > maxHeightPx) {
            cutAt = i
            break
        }
        leftParts += blocks[i]
    }
    if (cutAt >= blocks.size) return trimmed to ""
    val left = blocks.subList(0, cutAt).joinToString("\n").trim()
    val right = blocks.subList(cutAt, blocks.size).joinToString("\n").trim()
    if (left.isBlank() || right.isBlank()) return trimmed to ""
    return left to right
}

private fun measureStudyBodyHeight(
    context: Context,
    text: String,
    sample: TextView,
    widthPx: Int,
): Int {
    val spanned = buildStyledStudyBody(context, text)
    val layout = StaticLayout.Builder
        .obtain(spanned, 0, spanned.length, sample.paint, widthPx.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(sample.lineSpacingExtra, sample.lineSpacingMultiplier)
        .setIncludePad(sample.includeFontPadding)
        .build()
    return layout.height
}

/**
 * 본문학습과 같은 목차 꾸미기.
 * 1. 대목차 → 굵게
 * 1) 중목차 → 한 단
 * (1) 소목차 → 두 단
 * ① 세부 → 세 단
 * - 가장 안쪽 → 네 단
 */
fun buildStyledStudyBody(context: Context, text: String): SpannableStringBuilder {
    val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val primaryColor = ContextCompat.getColor(context, R.color.primary)
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
            trimmed.isBlank() -> indent = 0
            ROMAN_RE.containsMatchIn(trimmed) || MAJOR_RE.containsMatchIn(trimmed) -> {
                indent = 0; sizeRel = 1.12f; bold = true; color = primaryColor
                currentIndent = 0
            }
            SUB1_RE.containsMatchIn(trimmed) -> {
                indent = dp(18); bold = true
                currentIndent = dp(18)
            }
            SUB2_RE.containsMatchIn(trimmed) -> {
                indent = dp(36); sizeRel = 0.97f
                currentIndent = dp(36)
            }
            CIRCLE_RE.containsMatchIn(trimmed) -> {
                indent = dp(48); sizeRel = 0.97f
                currentIndent = dp(48)
            }
            DASH_RE.containsMatchIn(trimmed) -> {
                indent = dp(60); sizeRel = 0.95f
                currentIndent = dp(60)
            }
            else -> indent = currentIndent
        }

        sb.append(if (trimmed.isBlank()) "" else trimmed)
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

fun styledStudyAnswer(context: Context, raw: String, keepTypedBreaks: Boolean = false): SpannableStringBuilder {
    return buildStyledStudyBody(
        context,
        formatStudyOutlineText(displayStudyBody(raw, keepTypedBreaks))
    )
}
