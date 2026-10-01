package com.example.adminmemo

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

object WidgetLooks {
    /** 홈 위젯 모서리. 너무 둥글면 타원처럼 늘어 보여서 20dp로 둔다. */
    private const val CORNER_DP = 20f

    fun roundedBackground(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        color: Int
    ): Bitmap {
        val dens = context.resources.displayMetrics.density
        val opts = manager.getAppWidgetOptions(widgetId)
        val w = px(opts, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250, dens)
        val h = px(opts, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250, dens)
        val radius = CORNER_DP * dens
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        val inset = dens * 0.4f
        canvas.drawRoundRect(
            RectF(inset, inset, w - inset, h - inset),
            radius,
            radius,
            paint
        )
        return bmp
    }

    fun isDarkRgb(rgb: Int): Boolean {
        val r = Color.red(rgb)
        val g = Color.green(rgb)
        val b = Color.blue(rgb)
        return (r + g + b) / 3 < 128
    }

    private fun px(
        opts: android.os.Bundle,
        maxKey: String,
        minKey: String,
        fallbackDp: Int,
        dens: Float
    ): Int {
        val max = opts.getInt(maxKey, 0)
        val min = opts.getInt(minKey, 0)
        val dp = when {
            max > 0 -> max
            min > 0 -> min
            else -> fallbackDp
        }
        return (dp * dens).toInt().coerceIn(240, 1600)
    }
}
