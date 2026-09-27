package com.example.adminmemo

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        val cards = CardStore.getAllCards(context)
        if (cards.isEmpty()) {
            lines = listOf("등록된 카드가 없어요")
            return
        }
        val p1 = context.getSharedPreferences("widget_scroll_state", Context.MODE_PRIVATE)
        val p2 = context.getSharedPreferences("widget_large_state", Context.MODE_PRIVATE)

        var cardIndex = p1.getInt("card_index_$widgetId", -1)
        if (cardIndex !in cards.indices) {
            cardIndex = p2.getInt("card_index_$widgetId", -1)
        }
        if (cardIndex !in cards.indices) {
            cardIndex = 0
        }
        val card = cards[cardIndex]
        val fullText = getFormattedCardText(card)
        lines = fullText.split("\n")
    }

    override fun onDestroy() {}

    override fun getCount(): Int = lines.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list_row)
        views.setTextViewText(R.id.tvRowItem, lines.getOrElse(position) { "" })
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
