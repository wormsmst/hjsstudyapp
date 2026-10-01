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
import android.widget.RemoteViews

class QuizWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_CHOICE_0 = "com.example.adminmemo.ACTION_QUIZ_CHOICE_0"
        const val ACTION_CHOICE_1 = "com.example.adminmemo.ACTION_QUIZ_CHOICE_1"
        const val ACTION_CHOICE_2 = "com.example.adminmemo.ACTION_QUIZ_CHOICE_2"
        const val ACTION_NEXT = "com.example.adminmemo.ACTION_QUIZ_NEXT"

        private const val PREFS = "widget_quiz_state"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun targetIdKey(id: Int) = "target_id_$id"
        private fun choice0Key(id: Int) = "choice_0_$id"
        private fun choice1Key(id: Int) = "choice_1_$id"
        private fun choice2Key(id: Int) = "choice_2_$id"
        private fun correctIdxKey(id: Int) = "correct_idx_$id"
        private fun answeredKey(id: Int) = "answered_$id"
        private fun selectedIdxKey(id: Int) = "selected_idx_$id"

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
            val pool = conceptMnemonicQuizPool(
                context,
                CardStore.getAllCards(context).filter { it.type == "concept" && it.topicTitle.isNotBlank() }
            )
            if (pool.isEmpty()) return

            val p = prefs(context)
            val targetId = p.getString(targetIdKey(widgetId), null)
            var target = pool.firstOrNull { it.id == targetId }

            var choice0 = p.getString(choice0Key(widgetId), "") ?: ""
            var choice1 = p.getString(choice1Key(widgetId), "") ?: ""
            var choice2 = p.getString(choice2Key(widgetId), "") ?: ""
            var correctIdx = p.getInt(correctIdxKey(widgetId), 0)
            val answered = p.getBoolean(answeredKey(widgetId), false)
            val selectedIdx = p.getInt(selectedIdxKey(widgetId), -1)

            if (target == null || choice0.isEmpty() || choice1.isEmpty() || choice2.isEmpty()) {
                if (pool.size < 3) return
                target = pool.random()
                val wrong = pool.filter { it.mnemonic != target.mnemonic }.distinctBy { it.mnemonic }.shuffled().take(2)
                if (wrong.size < 2) return
                val choices = (wrong.map { it.mnemonic } + target.mnemonic).shuffled()
                if (choices.size < 3) return
                choice0 = choices[0]
                choice1 = choices[1]
                choice2 = choices[2]
                correctIdx = choices.indexOf(target.mnemonic)

                p.edit()
                    .putString(targetIdKey(widgetId), target.id)
                    .putString(choice0Key(widgetId), choice0)
                    .putString(choice1Key(widgetId), choice1)
                    .putString(choice2Key(widgetId), choice2)
                    .putInt(correctIdxKey(widgetId), correctIdx)
                    .putBoolean(answeredKey(widgetId), false)
                    .putInt(selectedIdxKey(widgetId), -1)
                    .apply()
            }

            val views = RemoteViews(context.packageName, R.layout.widget_quiz)

            // 배경 색상
            val rgb = AppPrefs.getWidgetColor(context)
            val opacity = AppPrefs.getWidgetOpacity(context)
            val alpha = (opacity * 255 / 100).coerceIn(0, 255)
            val bgColor = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            views.setImageViewBitmap(R.id.widgetBgImage, makeRoundedBitmap(bgColor, 16f))

            views.setTextViewText(R.id.widgetQuizGrade, target.grade.ifBlank { "기본" })
            views.setTextViewText(R.id.widgetQuizQuestion, "[${target.topicTitle}]\n이 주제의 두문자는?")

            views.setTextViewText(R.id.widgetChoice1, "① $choice0")
            views.setTextViewText(R.id.widgetChoice2, "② $choice1")
            views.setTextViewText(R.id.widgetChoice3, "③ $choice2")

            val defaultFill = Color.parseColor("#15000000")
            val correctColor = Color.parseColor("#58CC02")
            val wrongColor = Color.parseColor("#FF4B4B")

            fun setButtonColor(viewId: Int, idx: Int) {
                if (!answered) {
                    views.setInt(viewId, "setBackgroundColor", defaultFill)
                } else {
                    when {
                        idx == correctIdx -> views.setInt(viewId, "setBackgroundColor", correctColor)
                        idx == selectedIdx && idx != correctIdx -> views.setInt(viewId, "setBackgroundColor", wrongColor)
                        else -> views.setInt(viewId, "setBackgroundColor", defaultFill)
                    }
                }
            }

            setButtonColor(R.id.widgetChoice1, 0)
            setButtonColor(R.id.widgetChoice2, 1)
            setButtonColor(R.id.widgetChoice3, 2)

            fun setClick(action: String, requestCode: Int, viewId: Int) {
                val intent = Intent(context, QuizWidgetProvider::class.java).apply {
                    this.action = action
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
                val pi = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(viewId, pi)
            }

            setClick(ACTION_CHOICE_0, widgetId + 300000, R.id.widgetChoice1)
            setClick(ACTION_CHOICE_1, widgetId + 400000, R.id.widgetChoice2)
            setClick(ACTION_CHOICE_2, widgetId + 500000, R.id.widgetChoice3)
            setClick(ACTION_NEXT, widgetId + 600000, R.id.widgetQuizNext)

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
        val widgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            ?: run {
                val singleId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (singleId != AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf(singleId)
                else manager.getAppWidgetIds(ComponentName(context, QuizWidgetProvider::class.java))
            }

        if (widgetIds.isEmpty()) return

        val p = prefs(context)
        when (intent.action) {
            ACTION_CHOICE_0, ACTION_CHOICE_1, ACTION_CHOICE_2 -> {
                val chosenIdx = when (intent.action) {
                    ACTION_CHOICE_0 -> 0
                    ACTION_CHOICE_1 -> 1
                    else -> 2
                }
                for (widgetId in widgetIds) {
                    val answered = p.getBoolean(answeredKey(widgetId), false)
                    if (answered) continue
                    p.edit()
                        .putBoolean(answeredKey(widgetId), true)
                        .putInt(selectedIdxKey(widgetId), chosenIdx)
                        .apply()
                    updateWidget(context, manager, widgetId)
                }
            }
            ACTION_NEXT -> {
                for (widgetId in widgetIds) {
                    p.edit().remove(targetIdKey(widgetId)).apply()
                    updateWidget(context, manager, widgetId)
                }
            }
        }
    }
}
