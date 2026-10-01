package com.example.adminmemo

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class StudyWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return StudyViewsFactory(applicationContext, intent)
    }
}

class StudyViewsFactory(private val context: Context, intent: Intent) : RemoteViewsService.RemoteViewsFactory {
    private val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    private var lines: List<String> = emptyList()
    private var textColor = Color.parseColor("#1A1C1E")
    private var fontScale = 1f

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        fontScale = AppPrefs.getWidgetFontScale(context)
        textColor = if (WidgetLooks.isDarkRgb(AppPrefs.getWidgetColor(context))) {
            Color.WHITE
        } else {
            Color.parseColor("#1A1C1E")
        }
        val cards = CardStore.getAllCards(context)
        if (cards.isEmpty()) {
            lines = listOf("등록된 카드가 없어요")
            return
        }
        val p = context.getSharedPreferences("widget_state", Context.MODE_PRIVATE)
        var cardIndex = p.getInt("card_index_$widgetId", -1)
        if (cardIndex !in cards.indices) {
            cardIndex = context.getSharedPreferences("widget_large_state", Context.MODE_PRIVATE)
                .getInt("card_index_$widgetId", -1)
        }
        if (cardIndex !in cards.indices) cardIndex = 0
        val body = getWidgetBodyText(cards[cardIndex])
        lines = body.split("\n").ifEmpty { listOf("본문 내용이 없어요") }
    }

    override fun onDestroy() {}

    override fun getCount(): Int = lines.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list_row)
        val line = lines.getOrElse(position) { "" }
        views.setTextViewText(R.id.tvRowItem, if (line.isBlank()) " " else line)
        views.setTextColor(R.id.tvRowItem, textColor)
        views.setTextViewTextSize(R.id.tvRowItem, TypedValue.COMPLEX_UNIT_SP, 13f * fontScale)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
