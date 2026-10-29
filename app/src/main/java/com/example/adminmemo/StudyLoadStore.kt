package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.Calendar

private data class StudyLoadBook(
    var cardsPerHalf: Int = 3,
    var minutes: MutableMap<String, Int> = mutableMapOf(),
    var lastMinutes: MutableMap<String, Int> = mutableMapOf(),
    var complyPct: Int = 100,
    var recallTarget: Int = 5,
    var newPerDay: Int? = null,
    var updatedAt: Long = 0L
)

data class LoadForecast(
    val topics: Int,
    val target: Int,
    val weekPlan: Int,
    val weekEffective: Int,
    val remaining: Int,
    val days: Int?,
    val doneAt: Long?,
    val alreadyDone: Boolean
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
    private const val DEFAULT_NEW_PER_DAY = 3
    private const val FORECAST_MAX_DAYS = 3650
    val COMPLY_PCTS = listOf(50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100)
    val RECALL_TARGETS = listOf(3, 4, 5, 6, 7, 8, 9, 10)
    val NEW_PER_DAYS = (0..10).toList()
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
            book.complyPct = COMPLY_PCTS.find { it == book.complyPct } ?: 100
            book.recallTarget = RECALL_TARGETS.find { it == book.recallTarget } ?: 5
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
        return "공부 ${formatMinutes(min)}  ·  인출 ${todayCards(context)}장"
    }

    fun complyPct(context: Context): Int = read(context).complyPct

    fun setComplyPct(context: Context, pct: Int) {
        val book = read(context)
        book.complyPct = COMPLY_PCTS.find { it == pct } ?: 100
        write(context, book)
    }

    fun recallTarget(context: Context): Int = read(context).recallTarget

    fun setRecallTarget(context: Context, n: Int) {
        val book = read(context)
        book.recallTarget = RECALL_TARGETS.find { it == n } ?: 5
        write(context, book)
    }

    fun stepComplyPct(context: Context, dir: Int) {
        val i = (COMPLY_PCTS.indexOf(complyPct(context)).takeIf { it >= 0 } ?: COMPLY_PCTS.lastIndex) + dir
        if (i in COMPLY_PCTS.indices) setComplyPct(context, COMPLY_PCTS[i])
    }

    fun stepRecallTarget(context: Context, dir: Int) {
        val i = (RECALL_TARGETS.indexOf(recallTarget(context)).takeIf { it >= 0 } ?: 2) + dir
        if (i in RECALL_TARGETS.indices) setRecallTarget(context, RECALL_TARGETS[i])
    }

    fun newPerDay(context: Context): Int =
        (read(context).newPerDay ?: DEFAULT_NEW_PER_DAY).coerceIn(NEW_PER_DAYS.first(), NEW_PER_DAYS.last())

    fun setNewPerDay(context: Context, n: Int) {
        val book = read(context)
        book.newPerDay = n.coerceIn(NEW_PER_DAYS.first(), NEW_PER_DAYS.last())
        write(context, book)
    }

    fun stepNewPerDay(context: Context, dir: Int) {
        val i = (NEW_PER_DAYS.indexOf(newPerDay(context)).takeIf { it >= 0 } ?: NEW_PER_DAYS.indexOf(DEFAULT_NEW_PER_DAY)) + dir
        if (i in NEW_PER_DAYS.indices) setNewPerDay(context, NEW_PER_DAYS[i])
    }

    fun plannedCardsFor(context: Context, dow: Int): Int {
        val min = minutesFor(context, dow)
        if (min <= 0) return 0
        return (min / STEP_MIN) * cardsPerHalfHour(context)
    }

    fun effectiveCardsFor(context: Context, dow: Int): Int {
        val plan = plannedCardsFor(context, dow)
        if (plan <= 0) return 0
        return ((plan * complyPct(context) + 50) / 100).coerceIn(1, plan)
    }

    fun weekPlannedCards(context: Context): Int =
        weekDays.sumOf { plannedCardsFor(context, it.first) }

    fun weekEffectiveCards(context: Context): Int =
        weekDays.sumOf { effectiveCardsFor(context, it.first) }

    fun forecast(context: Context): LoadForecast {
        val target = recallTarget(context)
        val byTopic = linkedMapOf<String, Int>()
        for (card in CardStore.getAllCards(context)) {
            if (card.type != "concept") continue
            if (card.topicTitle.isBlank() || card.back.isBlank()) continue
            val key = canonicalizeSubject(card.subject) + "\u0000" + card.topicTitle.trim()
            val n = RecallStore.recallCount(context, card.id).coerceAtLeast(0)
            byTopic[key] = maxOf(byTopic[key] ?: 0, n)
        }
        val counts = byTopic.values.toIntArray()
        val remaining = counts.sumOf { (target - it).coerceAtLeast(0) }
        val weekPlan = weekPlannedCards(context)
        val weekEff = weekEffectiveCards(context)
        val topics = byTopic.size
        if (topics == 0) {
            return LoadForecast(0, target, weekPlan, weekEff, 0, 0, System.currentTimeMillis(), true)
        }
        if (remaining <= 0) {
            return LoadForecast(topics, target, weekPlan, weekEff, 0, 0, System.currentTimeMillis(), true)
        }
        if (weekEff <= 0) {
            return LoadForecast(topics, target, weekPlan, weekEff, remaining, null, null, false)
        }
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < TodayTtsStore.STUDY_DAY_ROLL_HOUR) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var days = 0
        val dailyNew = newPerDay(context)
        while (days < FORECAST_MAX_DAYS) {
            days++
            var slots = effectiveCardsFor(context, cal.get(Calendar.DAY_OF_WEEK))
            val used = mutableSetOf<Int>()
            var fresh = 0
            if (slots > 0) {
                for (i in counts.indices) {
                    if (fresh >= dailyNew || slots <= 0) break
                    if (counts[i] != 0) continue
                    counts[i] = 1
                    used.add(i)
                    fresh++
                    slots--
                }
                while (slots > 0) {
                    var best = -1
                    var bestCnt = Int.MAX_VALUE
                    for (i in counts.indices) {
                        if (i in used) continue
                        val n = counts[i]
                        if (n in 1 until target && n < bestCnt) {
                            best = i
                            bestCnt = n
                        }
                    }
                    if (best < 0) break
                    counts[best]++
                    used.add(best)
                    slots--
                }
            }
            if (counts.all { it >= target }) {
                return LoadForecast(topics, target, weekPlan, weekEff, remaining, days, cal.timeInMillis, false)
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return LoadForecast(topics, target, weekPlan, weekEff, remaining, null, null, false)
    }

    /** 지금 분량(신규 인출·요일 슬롯)으로 미인출 장이 0이 되기까지 걸리는 날. 계산 불가면 null. */
    fun daysToOpenUnseen(context: Context, unseen: Int): Int? {
        if (unseen <= 0) return 0
        val dailyNew = newPerDay(context)
        if (dailyNew <= 0 || weekEffectiveCards(context) <= 0) return null
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < TodayTtsStore.STUDY_DAY_ROLL_HOUR) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var left = unseen
        var days = 0
        while (days < FORECAST_MAX_DAYS && left > 0) {
            days++
            val slots = effectiveCardsFor(context, cal.get(Calendar.DAY_OF_WEEK))
            if (slots > 0) left -= minOf(dailyNew, slots, left)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return if (left > 0) null else days
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
