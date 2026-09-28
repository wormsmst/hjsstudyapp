package com.example.adminmemo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.widget.RemoteViews

class CardWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_FLIP = "com.example.adminmemo.ACTION_FLIP"
        const val ACTION_NEXT = "com.example.adminmemo.ACTION_NEXT"
        const val ACTION_CYCLE_MEMORY = "com.example.adminmemo.ACTION_CYCLE_MEMORY"
        private const val PREFS = "widget_state"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun cardIndexKey(id: Int) = "card_index_$id"
        private fun flippedKey(id: Int) = "flipped_$id"

        private fun makeRoundedBitmap(color: Int, radiusPx: Float): Bitmap {
            val w = 400; val h = 300
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = color
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radiusPx, radiusPx, paint)
            return bmp
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val cards = CardStore.getAllCards(context)
            if (cards.isEmpty()) return

            val p = prefs(context)
            var cardIndex = p.getInt(cardIndexKey(widgetId), -1)
            if (cardIndex < 0 || cardIndex >= cards.size) {
                cardIndex = weightedRandomIndex(context, cards)
                p.edit().putInt(cardIndexKey(widgetId), cardIndex).apply()
            }
            val flipped = p.getBoolean(flippedKey(widgetId), false)
            val card = cards[cardIndex]

            val views = RemoteViews(context.packageName, R.layout.widget_card)

            // ---- 배경(색상/투명도) ----
            val rgb = AppPrefs.getWidgetColor(context)
            val opacity = AppPrefs.getWidgetOpacity(context)
            val alpha = (opacity * 255 / 100).coerceIn(0, 255)
            val bgColor = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            views.setImageViewBitmap(R.id.widgetBgImage, makeRoundedBitmap(bgColor, 48f))

            // ---- 글자 크기 ----
            val fontScale = AppPrefs.getWidgetFontScale(context)
            views.setTextViewTextSize(R.id.widgetGrade, TypedValue.COMPLEX_UNIT_SP, 11f * fontScale)
            views.setTextViewTextSize(R.id.widgetMemory, TypedValue.COMPLEX_UNIT_SP, 11f * fontScale)
            views.setTextViewTextSize(R.id.widgetText, TypedValue.COMPLEX_UNIT_SP, 13f * fontScale)
            views.setTextViewTextSize(R.id.widgetHint, TypedValue.COMPLEX_UNIT_SP, 10f * fontScale)
            views.setTextViewTextSize(R.id.widgetNext, TypedValue.COMPLEX_UNIT_SP, 12f * fontScale)

            // ---- 텍스트 내용 ----
            views.setTextViewText(R.id.widgetGrade, "${if (card.type == "mnemonic") "두문자" else "개념"} · ${card.grade}")
            views.setTextViewText(R.id.widgetText, if (flipped) card.back else card.front)
            views.setTextViewText(R.id.widgetHint, if (flipped) "👆 탭: 문제 보기" else "👆 탭: 정답 보기")

            val level = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
            views.setTextViewText(R.id.widgetMemory, "★".repeat(level) + "☆".repeat(5 - level) + "  (탭해서 변경)")

            // ---- 클릭 동작 ----
            val flipIntent = Intent(context, CardWidgetProvider::class.java).apply {
                action = ACTION_FLIP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            views.setOnClickPendingIntent(
                R.id.widgetHeaderRow,
                PendingIntent.getBroadcast(context, widgetId, flipIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            val nextIntent = Intent(context, CardWidgetProvider::class.java).apply {
                action = ACTION_NEXT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            views.setOnClickPendingIntent(
                R.id.widgetNext,
                PendingIntent.getBroadcast(context, widgetId + 100000, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            val cycleIntent = Intent(context, CardWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_MEMORY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            views.setOnClickPendingIntent(
                R.id.widgetMemory,
                PendingIntent.getBroadcast(context, widgetId + 200000, cycleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) {
            updateWidget(context, manager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val manager = AppWidgetManager.getInstance(context)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (widgetId == -1) return

        when (intent.action) {
            ACTION_FLIP -> {
                val p = prefs(context)
                val flipped = p.getBoolean(flippedKey(widgetId), false)
                p.edit().putBoolean(flippedKey(widgetId), !flipped).apply()
                updateWidget(context, manager, widgetId)
            }
            ACTION_NEXT -> {
                val cards = CardStore.getAllCards(context)
                if (cards.isEmpty()) return
                val p = prefs(context)
                val newIndex = weightedRandomIndex(context, cards)
                p.edit()
                    .putInt(cardIndexKey(widgetId), newIndex)
                    .putBoolean(flippedKey(widgetId), false)
                    .apply()
                updateWidget(context, manager, widgetId)
            }
            ACTION_CYCLE_MEMORY -> {
                val cards = CardStore.getAllCards(context)
                val p = prefs(context)
                val idx = p.getInt(cardIndexKey(widgetId), -1)
                if (idx in cards.indices) {
                    val card = cards[idx]
                    CardStore.cycleMemoryLevel(context, card.subject, card.topicTitle)
                    updateWidget(context, manager, widgetId)
                }
            }
        }
    }
}
