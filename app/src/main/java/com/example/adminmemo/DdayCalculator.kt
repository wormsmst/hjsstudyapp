package com.example.adminmemo

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateFormatters {
    private val full = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
    private val day = SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREA)

    fun dateTime(millis: Long): String =
        if (millis <= 0L) "없음" else full.format(Date(millis))

    fun dateOnly(millis: Long): String =
        if (millis <= 0L) "미설정" else day.format(Date(millis))
}

object DdayCalculator {
    fun daysUntil(targetMillis: Long): Int {
        val today = startOfDay(Calendar.getInstance())
        val target = Calendar.getInstance().apply { timeInMillis = targetMillis }
        val exam = startOfDay(target)
        val diff = exam.timeInMillis - today.timeInMillis
        return (diff / (24L * 60L * 60L * 1000L)).toInt()
    }

    /** 오늘 포함, 아직 시험일이 지나지 않았으면 true */
    fun isUpcoming(millis: Long): Boolean = millis > 0L && daysUntil(millis) >= 0

    fun ddayToken(millis: Long): String {
        val days = daysUntil(millis)
        return when {
            days > 0 -> "D-$days"
            days == 0 -> "D-Day"
            else -> "D+${-days}"
        }
    }

    data class HomeDday(
        val main: String,
        val second: String?,
        val sub: String
    )

    fun homeDisplay(context: Context): HomeDday {
        val d2 = AppPrefs.getExam2DateMillis(context)
        return if (isUpcoming(d2)) {
            HomeDday(
                main = "2차 시험일까지  ${ddayToken(d2)}",
                second = null,
                sub = DateFormatters.dateOnly(d2)
            )
        } else {
            HomeDday(
                main = "2차 시험일을 설정해주세요",
                second = null,
                sub = "탭해서 2차 시험일을 정할 수 있어요"
            )
        }
    }

    fun label(context: Context): String = homeDisplay(context).main

    fun subLabel(context: Context): String = homeDisplay(context).sub

    private fun startOfDay(cal: Calendar): Calendar {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }
}
