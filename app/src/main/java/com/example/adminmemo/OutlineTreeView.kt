package com.example.adminmemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** 세로: 위→아래, 가로: 왼→오른쪽 목차 트리. */
class OutlineTreeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var root: Laid? = null
    private var sideways = false
    private val d = resources.displayMetrics.density
    private val gap = 10 * d
    private val stem = 28 * d
    private val padH = 12 * d
    private val padV = 10 * d
    private val maxBox = 260 * d
    private val minBox = 96 * d
    private val radius = 12 * d

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.tile_outline)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = ContextCompat.getColor(context, R.color.primary)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = ContextCompat.getColor(context, R.color.primary)
        alpha = 140
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_main)
        textSize = 12.5f * resources.displayMetrics.scaledDensity
    }
    private val titlePaint = TextPaint(textPaint).apply {
        isFakeBoldText = true
        textSize = 13.5f * resources.displayMetrics.scaledDensity
    }

    fun setOutline(title: String, roots: List<OutlineNode>, sideways: Boolean) {
        this.sideways = sideways
        val laid = layoutNode(title.ifBlank { "주제" }, roots, isRoot = true)
        place(laid, padH, padV)
        root = laid
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val r = root
        if (r == null) {
            setMeasuredDimension(0, 0)
            return
        }
        val w = (r.subW + padH * 2).toInt().coerceAtLeast(suggestedMinimumWidth)
        val h = (r.subH + padV * 2).toInt().coerceAtLeast(suggestedMinimumHeight)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val r = root ?: return
        drawNode(canvas, r)
    }

    private fun drawNode(canvas: Canvas, node: Laid) {
        val rect = RectF(node.x, node.y, node.x + node.boxW, node.y + node.boxH)
        canvas.drawRoundRect(rect, radius, radius, boxPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        val paint = if (node.isRoot) titlePaint else textPaint
        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val contentH = node.lines.size * lineH * 1.12f
        var ty = node.y + (node.boxH - contentH) / 2f - fm.ascent
        val multi = node.lines.size > 1
        node.lines.forEach { line ->
            val tw = paint.measureText(line)
            val tx = if (multi) node.x + padH else node.x + (node.boxW - tw) / 2f
            canvas.drawText(line, tx, ty, paint)
            ty += lineH * 1.12f
        }
        if (sideways) {
            val px = node.x + node.boxW
            val py = node.y + node.boxH / 2f
            node.kids.forEach { kid ->
                val cx = kid.x
                val cy = kid.y + kid.boxH / 2f
                canvas.drawLine(px, py, px + stem * 0.4f, py, linePaint)
                canvas.drawLine(px + stem * 0.4f, py, px + stem * 0.4f, cy, linePaint)
                canvas.drawLine(px + stem * 0.4f, cy, cx, cy, linePaint)
                drawNode(canvas, kid)
            }
        } else {
            val px = node.x + node.boxW / 2f
            val py = node.y + node.boxH
            node.kids.forEach { kid ->
                val cx = kid.x + kid.boxW / 2f
                val cy = kid.y
                canvas.drawLine(px, py, px, py + stem * 0.35f, linePaint)
                canvas.drawLine(px, py + stem * 0.35f, cx, cy - 2, linePaint)
                canvas.drawLine(cx, cy - 2, cx, cy, linePaint)
                drawNode(canvas, kid)
            }
        }
    }

    private fun layoutNode(label: String, children: List<OutlineNode>, isRoot: Boolean): Laid {
        val paint = if (isRoot) titlePaint else textPaint
        val maxW = (if (sideways) maxBox * 1.5f else maxBox) * 1.2f
        val minW = (if (sideways) minBox * 1.5f else minBox) * 1.2f
        val lines = wrap(outlineHeadingText(label).ifBlank { label }, paint, maxW - padH * 2)
        val textW = lines.maxOf { paint.measureText(it) }
        val boxW = (textW + padH * 2).coerceIn(minW, maxW)
        val baseH = padV * 2 + lines.size * (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 1.12f
        val boxH = (if (sideways) baseH else baseH * 1.5f) * 1.2f
        val kids = children.map { layoutNode(it.label, it.children, false) }
        if (sideways) {
            val kidsH = if (kids.isEmpty()) 0f else kids.sumOf { it.subH.toDouble() }.toFloat() + gap * (kids.size - 1)
            val kidsW = if (kids.isEmpty()) 0f else kids.maxOf { it.subW }
            val subW = boxW + if (kids.isEmpty()) 0f else stem + kidsW
            val subH = maxOf(boxH, kidsH)
            return Laid(lines, boxW, boxH, subW, subH, isRoot, kids)
        }
        val kidsW = if (kids.isEmpty()) 0f else kids.sumOf { it.subW.toDouble() }.toFloat() + gap * (kids.size - 1)
        val subW = maxOf(boxW, kidsW)
        val subH = boxH + if (kids.isEmpty()) 0f else stem + kids.maxOf { it.subH }
        return Laid(lines, boxW, boxH, subW, subH, isRoot, kids)
    }

    private fun place(node: Laid, left: Float, top: Float) {
        if (sideways) {
            node.x = left
            node.y = top
            if (node.kids.isEmpty()) return
            var y = top
            val x = left + node.boxW + stem
            node.kids.forEach { kid ->
                place(kid, x, y)
                y += kid.subH + gap
            }
            return
        }
        node.x = left + (node.subW - node.boxW) / 2f
        node.y = top
        if (node.kids.isEmpty()) return
        val kidsW = node.kids.sumOf { it.subW.toDouble() }.toFloat() + gap * (node.kids.size - 1)
        var x = left + (node.subW - kidsW) / 2f
        val y = top + node.boxH + stem
        node.kids.forEach { kid ->
            place(kid, x, y)
            x += kid.subW + gap
        }
    }

    private fun wrap(text: String, paint: TextPaint, inner: Float): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return listOf(text)
        if (paint.measureText(t) <= inner) return listOf(t)
        val first = cutLine(t, inner, paint)
        val rest = t.drop(first).trimStart()
        if (rest.isEmpty()) return listOf(t.take(first).trimEnd())
        if (paint.measureText(rest) <= inner) {
            return listOf(t.take(first).trimEnd(), rest)
        }
        val second = cutLine(rest, inner, paint)
        val tail = rest.drop(second).trimStart()
        val a = t.take(first).trimEnd()
        val b = rest.take(second).trimEnd()
        if (tail.isEmpty()) return listOf(a, b)
        return listOf(a, b, if (paint.measureText(tail) <= inner) tail else ellipsize(tail, inner, paint))
    }

    private fun cutLine(s: String, inner: Float, paint: TextPaint): Int {
        if (paint.measureText(s) <= inner) return s.length
        var fit = s.length
        while (fit > 1 && paint.measureText(s.take(fit)) > inner) fit--
        val good = (fit downTo (fit * 0.4f).toInt().coerceAtLeast(1)).firstOrNull { i ->
            canBreak(s, i) && paint.measureText(s.take(i).trimEnd()) in 1f..inner
        }
        return good ?: fit.coerceAtLeast(1)
    }

    private fun canBreak(s: String, i: Int): Boolean {
        if (i <= 0 || i >= s.length) return false
        val prev = s[i - 1]
        val next = s[i]
        if (prev.isWhitespace() || prev == '·' || prev == '・' || prev == '/' || prev == ',' || prev == '.') return true
        if (next == '(' || next == '[') return true
        if (prev == '의' && next == '의') return false
        if (prev in "의과와및을를은는에로") return next in '가'..'힣' || next.isWhitespace()
        return false
    }

    private fun ellipsize(s: String, inner: Float, paint: TextPaint): String {
        if (paint.measureText(s) <= inner) return s
        var cut = s.length
        while (cut > 1 && paint.measureText(s.take(cut) + "…") > inner) cut--
        return s.take(cut.coerceAtLeast(1)) + "…"
    }

    private class Laid(
        val lines: List<String>,
        val boxW: Float,
        val boxH: Float,
        val subW: Float,
        val subH: Float,
        val isRoot: Boolean,
        val kids: List<Laid>
    ) {
        var x = 0f
        var y = 0f
    }
}
