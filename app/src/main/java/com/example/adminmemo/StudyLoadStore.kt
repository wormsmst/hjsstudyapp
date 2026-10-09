package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.Calendar

private data class StudyLoadBook(
    var cardsPerHalf: Int = 3,
    var minutes: MutableMap<String, Int> = mutableMapOf(),
    var lastMinutes: MutableMap<String, Int> = mutableMapOf(),
    var updatedAt: Long = 0L
)

/** 요일별 공부시간(30분 단위)과 30분당 인출 장수. */
object StudyLoadStore {
    private const val PREFS = "study_load"
    private const val FILE = "study_load.json"
    private const val KEY_CARDS_PER_HALF = "cards_per_half"
    const val STEP_MIN = 30
    const val MAX_MIN = 12 * 60
    private const val DEFAULT_CARDS = 3
    private const val DEFAULT_WEEKDAY_MIN = 120
    private const val DEFAULT_WEEKEND_MIN = 180
    private val gson = Gson()

    val weekDays = listOf(
        Calendar.MONDAY to "월요일",
        Calendar.TUESDAY to "화요일",
        Calendar.WEDNESDAY to "수요일",
        Calendar.THURSDAY to "목요일",
        Calendar.FRIDAY to "금요일",
        Calendar.SATURDAY to "토요일",
        Calendar.SUNDAY to "일요일"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun parse(json: String): StudyLoadBook {
        if (json.isBlank()) return StudyLoadBook()
        return try {
            val book = gson.fromJson(json, StudyLoadBook::class.java) ?: StudyLoadBook()
            book.minutes = book.minutes ?: mutableMapOf()
            book.lastMinutes = book.lastMinutes ?: mutableMapOf()
            book
        } catch (_: Exception) {
            StudyLoadBook()
        }
    }

    private fun read(context: Context): StudyLoadBook {
        val f = file(context)
        if (f.exists()) return parse(f.readText(Charsets.UTF_8))
        return migratePrefs(context)
    }

    private fun migratePrefs(context: Context): StudyLoadBook {
        val p = prefs(context)
        val book = StudyLoadBook(
            cardsPerHalf = p.getInt(KEY_CARDS_PER_HALF, DEFAULT_CARDS).coerceIn(1, 20),
            updatedAt = 0L
        )
        weekDays.forEach { (dow, _) ->
            if (p.contains(minKey(dow))) {
                book.minutes[dow.toString()] = p.getInt(minKey(dow), defaultMinutes(dow))
            }
            if (p.contains(lastKey(dow))) {
                book.lastMinutes[dow.toString()] = p.getInt(lastKey(dow), defaultMinutes(dow))
            }
        }
        return book
    }

    private fun write(context: Context, book: StudyLoadBook, notify: Boolean = true) {
        if (notify) book.updatedAt = System.currentTimeMillis()
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
        if (notify) {
            AppPrefs.setLocalSyncTimestamp(context, book.updatedAt)
            FirebaseSyncManager.notifyProgressChanged(context)
        }
    }

    fun cardsPerHalfHour(context: Context): Int =
        read(context).cardsPerHalf.coerceIn(1, 20)

    fun setCardsPerHalfHour(context: Context, n: Int) {
        val book = read(context)
        book.cardsPerHalf = n.coerceIn(1, 20)
        write(context, book)
    }

    private fun defaultMinutes(dow: Int): Int =
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) DEFAULT_WEEKEND_MIN
        else DEFAULT_WEEKDAY_MIN

    fun minutesFor(context: Context, dow: Int): Int {
        val raw = read(context).minutes[dow.toString()] ?: defaultMinutes(dow)
        return (raw / STEP_MIN * STEP_MIN).coerceIn(0, MAX_MIN)
    }

    fun setMinutes(context: Context, dow: Int, minutes: Int) {
        val v = (minutes / STEP_MIN * STEP_MIN).coerceIn(0, MAX_MIN)
        val book = read(context)
        book.minutes[dow.toString()] = v
        if (v > 0) book.lastMinutes[dow.toString()] = v
        write(context, book)
    }

    fun isRest(context: Context, dow: Int): Boolean = minutesFor(context, dow) <= 0

    fun setRest(context: Context, dow: Int, rest: Boolean) {
        if (rest) {
            val book = read(context)
            book.minutes[dow.toString()] = 0
            write(context, book)
        } else {
            val last = read(context).lastMinutes[dow.toString()] ?: defaultMinutes(dow)
            setMinutes(context, dow, last.coerceAtLeast(STEP_MIN))
        }
    }

    fun dowAt(now: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        if (cal.get(Calendar.HOUR_OF_DAY) < TodayTtsStore.STUDY_DAY_ROLL_HOUR) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal.get(Calendar.DAY_OF_WEEK)
    }

    fun todayMinutes(context: Context, now: Long = System.currentTimeMillis()): Int =
        minutesFor(context, dowAt(now))

    fun todayCards(context: Context, now: Long = System.currentTimeMillis()): Int {
        val blocks = todayMinutes(context, now) / STEP_MIN
        if (blocks <= 0) return 0
        return blocks * cardsPerHalfHour(context)
    }

    /** 15분 단위 보충. 30분당 인출을 올림으로 환산한다. */
    fun extraCardCount(context: Context, minutes: Int): Int {
        val n = cardsPerHalfHour(context)
        val m = minutes.coerceIn(15, 45)
        return ((n * m + 29) / 30).coerceAtLeast(1)
    }

    fun formatMinutes(minutes: Int): String {
        if (minutes <= 0) return "휴식"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}시간 ${m}분"
            h > 0 -> "${h}시간"
            else -> "${m}분"
        }
    }

    fun todayLine(context: Context): String {
        val min = todayMinutes(context)
        if (min <= 0) return "오늘은 휴식일"
        return "오늘 ${formatMinutes(min)}  ·  인출 ${todayCards(context)}장"
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parse(localJson)
        val b = parse(cloudJson)
        fun placeholder(book: StudyLoadBook): Boolean =
            book.minutes.isEmpty() && book.lastMinutes.isEmpty() && book.cardsPerHalf == DEFAULT_CARDS
        val newer = when {
            placeholder(a) && !placeholder(b) -> b
            placeholder(b) && !placeholder(a) -> a
            a.updatedAt <= 0L && b.updatedAt <= 0L -> if (!placeholder(b)) b else a
            a.updatedAt <= 0L -> b
            b.updatedAt <= 0L -> a
            a.updatedAt >= b.updatedAt -> a
            else -> b
        }
        return gson.toJson(newer)
    }

    private fun minKey(dow: Int) = "min_$dow"
    private fun lastKey(dow: Int) = "last_min_$dow"
}
