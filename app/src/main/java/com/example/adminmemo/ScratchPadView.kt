package com.example.adminmemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

class ScratchPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokes = mutableListOf<Path>()
    private var current: Path? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.text_main)
        strokeWidth = 3.4f * resources.displayMetrics.density
    }
    private val paper = ContextCompat.getColor(context, R.color.bg_card)

    fun clearPad() {
        strokes.clear()
        current = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(paper)
        strokes.forEach { canvas.drawPath(it, paint) }
        current?.let { canvas.drawPath(it, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Path().also { it.moveTo(x, y) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                current?.lineTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { strokes.add(it) }
                current = null
                invalidate()
            }
        }
        return true
    }
}
