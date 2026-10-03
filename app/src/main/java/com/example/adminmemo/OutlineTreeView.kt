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
    private val padH = 10 * d
    private val padV = 8 * d
    private val maxBox = 160 * d
    private val minBox = 72 * d
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
        var ty = node.y + padV - paint.ascent()
        node.lines.forEach { line ->
            val tw = paint.measureText(line)
            canvas.drawText(line, node.x + (node.boxW - tw) / 2f, ty, paint)
            ty += paint.textSize * 1.25f
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
        val lines = wrap(outlineHeadingText(label).ifBlank { label }, paint)
        val textW = lines.maxOf { paint.measureText(it) }
        val boxW = (textW + padH * 2).coerceIn(minBox, maxBox)
        val boxH = padV * 2 + lines.size * paint.textSize * 1.25f
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
            node.y = top + (node.subH - node.boxH) / 2f
            if (node.kids.isEmpty()) return
            val kidsH = node.kids.sumOf { it.subH.toDouble() }.toFloat() + gap * (node.kids.size - 1)
            var y = top + (node.subH - kidsH) / 2f
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

    private fun wrap(text: String, paint: TextPaint): List<String> {
        val inner = maxBox - padH * 2
        if (paint.measureText(text) <= inner) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty() && out.size < 3) {
            var cut = rest.length
            while (cut > 1 && paint.measureText(rest.take(cut)) > inner) cut--
            if (out.size == 2 && rest.length > cut) {
                out.add(rest.take((cut - 1).coerceAtLeast(1)) + "…")
                break
            }
            out.add(rest.take(cut))
            rest = rest.drop(cut).trimStart()
        }
        return out.ifEmpty { listOf(text) }
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
