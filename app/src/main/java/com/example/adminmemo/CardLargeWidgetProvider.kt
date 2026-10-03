package com.example.adminmemo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.TypedValue
import android.widget.RemoteViews

class CardLargeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_NEXT = "com.example.adminmemo.ACTION_NEXT_LARGE"
        const val ACTION_CYCLE_MEMORY = "com.example.adminmemo.ACTION_CYCLE_MEMORY_LARGE"
        private const val PREFS = "widget_large_state"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun cardIndexKey(id: Int) = "card_index_$id"

        private fun makeRoundedBitmap(color: Int, radiusPx: Float): Bitmap {
            val w = 600; val h = 600
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
            val card = cards[cardIndex]

            val views = RemoteViews(context.packageName, R.layout.widget_card_scroll)

            val rgb = AppPrefs.getWidgetColor(context)
            val opacity = AppPrefs.getWidgetOpacity(context)
            val alpha = (opacity * 255 / 100).coerceIn(0, 255)
            val bgColor = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            views.setImageViewBitmap(R.id.widgetBgImage, makeRoundedBitmap(bgColor, 16f))

            val fontScale = AppPrefs.getWidgetFontScale(context)
            views.setTextViewTextSize(R.id.widgetGrade, TypedValue.COMPLEX_UNIT_SP, 12f * fontScale)
            views.setTextViewTextSize(R.id.widgetMemory, TypedValue.COMPLEX_UNIT_SP, 12f * fontScale)
            views.setTextViewTextSize(R.id.widgetNext, TypedValue.COMPLEX_UNIT_SP, 13f * fontScale)

            views.setTextViewText(R.id.widgetGrade, "[${card.topicTitle}] · ${card.grade.ifBlank { "기본" }}")

            val level = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
            views.setTextViewText(R.id.widgetMemory, "★".repeat(level) + "☆".repeat(5 - level))

            val adapterIntent = Intent(context, StudyWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME) + "#$widgetId")
            }
            views.setRemoteAdapter(R.id.widgetListView, adapterIntent)

            val nextIntent = Intent(context, CardLargeWidgetProvider::class.java).apply {
                action = ACTION_NEXT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            views.setOnClickPendingIntent(
                R.id.widgetNext,
                PendingIntent.getBroadcast(context, widgetId + 100000, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            val cycleIntent = Intent(context, CardLargeWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_MEMORY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            views.setOnClickPendingIntent(
                R.id.widgetMemory,
                PendingIntent.getBroadcast(context, widgetId + 200000, cycleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widgetListView)
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
        val widgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            ?: run {
                val singleId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (singleId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    intArrayOf(singleId)
                } else {
                    manager.getAppWidgetIds(ComponentName(context, CardLargeWidgetProvider::class.java))
                }
            }

        if (widgetIds.isEmpty()) return

        val p = prefs(context)
        when (intent.action) {
            ACTION_NEXT -> {
                val cards = CardStore.getAllCards(context)
                if (cards.isEmpty()) return
                for (widgetId in widgetIds) {
                    val newIndex = weightedRandomIndex(context, cards)
                    p.edit().putInt(cardIndexKey(widgetId), newIndex).apply()
                    updateWidget(context, manager, widgetId)
                }
            }
            ACTION_CYCLE_MEMORY -> {
                for (widgetId in widgetIds) updateWidget(context, manager, widgetId)
            }
        }
    }
}
