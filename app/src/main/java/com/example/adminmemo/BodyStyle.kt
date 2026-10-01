package com.example.adminmemo

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat

private val MAJOR_RE = Regex("^\\d+\\.\\s")
private val SUB1_RE = Regex("^\\d+\\)\\s")
private val SUB2_RE = Regex("^\\(\\d+\\)\\s")
private val CIRCLE_RE = Regex("^[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮]\\s?")
private val DASH_RE = Regex("^[-·]\\s")
private val ROMAN_RE = Regex("^[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[\\.．]\\s*")
private val PAGE_MARK_RE = Regex("^-\\s*\\d+\\s*-$")

/** 대목차(1. / Ⅰ.) 앞에 빈 줄을 넣어 읽기 편하게 만든다. */
fun formatStudyOutlineText(text: String): String {
    val lines = normalizeNewlines(text).split("\n")
    val out = mutableListOf<String>()
    for ((i, line) in lines.withIndex()) {
        val trimmed = line.trim()
        if (PAGE_MARK_RE.matches(trimmed)) continue
        val isMajor = MAJOR_RE.containsMatchIn(line.trimStart()) || ROMAN_RE.containsMatchIn(line.trimStart())
        if (i > 0 && isMajor) {
            val prevBlank = out.isNotEmpty() && out.last().isBlank()
            if (!prevBlank) out.add("")
        }
        out.add(line)
    }
    return out.joinToString("\n")
}

/** 가로 책 펼침: 목차 줄 경계에서 절반으로 나눈다. 짧으면 오른쪽은 비운다. */
fun splitStudySpread(text: String): Pair<String, String> {
    val trimmed = text.trim()
    val lines = trimmed.count { it == '\n' }
    if (trimmed.length < 240 || lines < 3) return trimmed to ""
    val mid = trimmed.length / 2
    val minCut = (trimmed.length / 4).coerceAtLeast(1)
    val maxCut = (mid + trimmed.length / 4).coerceAtMost(trimmed.lastIndex)
    fun cutAt(token: String): Int {
        val i = trimmed.lastIndexOf(token, maxCut)
        return if (i >= minCut) i + token.length else -1
    }
    val cut = sequenceOf("\n\n", "\n").map { cutAt(it) }.firstOrNull { it > 0 } ?: mid
    val left = trimmed.substring(0, cut).trim()
    val right = trimmed.substring(cut).trim()
    if (right.isBlank()) return trimmed to ""
    return left to right
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

fun styledStudyAnswer(context: Context, raw: String, keepTypedBreaks: Boolean = false): SpannableStringBuilder {
    return buildStyledStudyBody(
        context,
        formatStudyOutlineText(displayStudyBody(raw, keepTypedBreaks))
    )
}
