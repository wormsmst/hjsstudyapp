package com.example.adminmemo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews

class CardWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_NEXT = "com.example.adminmemo.ACTION_NEXT"
        const val ACTION_CYCLE_MEMORY = "com.example.adminmemo.ACTION_CYCLE_MEMORY"
        private const val PREFS = "widget_state"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun cardIndexKey(id: Int) = "card_index_$id"

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
            val views = RemoteViews(context.packageName, R.layout.widget_card)

            val rgb = AppPrefs.getWidgetColor(context)
            val opacity = AppPrefs.getWidgetOpacity(context)
            val alpha = (opacity * 255 / 100).coerceIn(0, 255)
            val bgColor = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            views.setImageViewBitmap(
                R.id.widgetBgImage,
                WidgetLooks.roundedBackground(context, manager, widgetId, bgColor)
            )

            val dark = WidgetLooks.isDarkRgb(rgb)
            val main = if (dark) Color.WHITE else Color.parseColor("#1A1C1E")
            val accent = if (dark) Color.parseColor("#8AB4F8") else Color.parseColor("#2F6FED")
            views.setTextColor(R.id.widgetTitle, main)
            views.setTextColor(R.id.widgetGrade, if (dark) Color.parseColor("#FFB74D") else Color.parseColor("#C45A00"))
            views.setTextColor(R.id.widgetNext, accent)
            views.setTextColor(R.id.widgetMemory, if (dark) Color.parseColor("#FFD54F") else Color.parseColor("#D4A017"))

            val fontScale = AppPrefs.getWidgetFontScale(context)
            views.setTextViewTextSize(R.id.widgetGrade, TypedValue.COMPLEX_UNIT_SP, 11f * fontScale)
            views.setTextViewTextSize(R.id.widgetMemory, TypedValue.COMPLEX_UNIT_SP, 12f * fontScale)
            views.setTextViewTextSize(R.id.widgetTitle, TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
            views.setTextViewTextSize(R.id.widgetNext, TypedValue.COMPLEX_UNIT_SP, 12f * fontScale)

            val kind = if (card.type == "mnemonic") "두문자" else "개념"
            views.setTextViewText(R.id.widgetGrade, "$kind  ·  ${card.grade.ifBlank { "기본" }}")
            views.setTextViewText(R.id.widgetTitle, card.topicTitle.ifBlank { card.title })
            val level = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
            views.setTextViewText(R.id.widgetMemory, "★".repeat(level) + "☆".repeat(5 - level))

            val adapterIntent = Intent(context, StudyWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse("adminmemo://widget/$widgetId")
            }
            views.setRemoteAdapter(R.id.widgetListView, adapterIntent)

            val nextIntent = Intent(context, CardWidgetProvider::class.java).apply {
                action = ACTION_NEXT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            views.setOnClickPendingIntent(
                R.id.widgetNext,
                PendingIntent.getBroadcast(
                    context,
                    widgetId + 100000,
                    nextIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
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

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle
    ) {
        updateWidget(context, manager, widgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val manager = AppWidgetManager.getInstance(context)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (widgetId == -1) return

        when (intent.action) {
            ACTION_NEXT -> {
                val cards = CardStore.getAllCards(context)
                if (cards.isEmpty()) return
                val p = prefs(context)
                p.edit().putInt(cardIndexKey(widgetId), weightedRandomIndex(context, cards)).apply()
                updateWidget(context, manager, widgetId)
            }
            ACTION_CYCLE_MEMORY -> {
                updateWidget(context, manager, widgetId)
            }
        }
    }
}
