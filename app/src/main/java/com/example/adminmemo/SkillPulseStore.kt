package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private data class SkillDaySnap(
    val key: String = "",
    val stable: Int = 0,
    val starSum: Int = 0,
    val unseen: Int = 0,
    val cards: Int = 0,
    val fillPct: Int? = null,
    val stablePct: Int? = null
)

data class WeekPctDelta(
    val achieve: Int? = null,
    val stable: Int? = null,
    val newCards: Int? = null
)

private data class SkillSessionSnap(
    val at: Long = 0L,
    val good: Int = 0,
    val half: Int = 0,
    val miss: Int = 0
)

private data class SkillPulseBook(
    var days: MutableMap<String, SkillDaySnap> = mutableMapOf(),
    var sessions: MutableList<SkillSessionSnap> = mutableListOf()
)

/** 지난주·최근 인출과 비교해, 실제로 늘었을 때만 한 줄을 만든다. */
object SkillPulseStore {
    private const val FILE = "skill_pulse.json"
    private val gson = Gson()
    @Volatile private var cached: SkillPulseBook? = null

    fun invalidateCache() {
        cached = null
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun parseBook(json: String): SkillPulseBook {
        if (json.isBlank()) return SkillPulseBook()
        return try {
            val parsed = gson.fromJson(json, SkillPulseBook::class.java) ?: SkillPulseBook()
            parsed.days = parsed.days ?: mutableMapOf()
            parsed.sessions = parsed.sessions ?: mutableListOf()
            parsed
        } catch (_: Exception) {
            SkillPulseBook()
        }
    }

    private fun read(context: Context): SkillPulseBook {
        cached?.let { return it }
        val f = file(context)
        val book = if (!f.exists()) SkillPulseBook() else parseBook(f.readText(Charsets.UTF_8))
        cached = book
        return book
    }

    private fun write(context: Context, book: SkillPulseBook) {
        cached = book
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun isHollowJson(json: String): Boolean {
        if (json.isBlank()) return true
        return try {
            parseBook(json).days.isEmpty()
        } catch (_: Exception) {
            true
        }
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parseBook(localJson)
        val b = parseBook(cloudJson)
        if (isHollowJson(localJson) && !isHollowJson(cloudJson)) return gson.toJson(b)
        if (isHollowJson(cloudJson) && !isHollowJson(localJson)) return gson.toJson(a)
        val days = linkedMapOf<String, SkillDaySnap>()
        (a.days.keys + b.days.keys).forEach { key ->
            val la = a.days[key]
            val lb = b.days[key]
            days[key] = when {
                la == null -> lb!!
                lb == null -> la
                else -> mergeDay(la, lb)
            }
        }
        val sessions = (a.sessions + b.sessions)
            .distinctBy { "${it.at}|${it.good}|${it.half}|${it.miss}" }
            .sortedBy { it.at }
            .takeLast(30)
            .toMutableList()
        return gson.toJson(SkillPulseBook(days = days, sessions = sessions))
    }

    private fun mergeDay(a: SkillDaySnap, b: SkillDaySnap) = SkillDaySnap(
        key = a.key.ifBlank { b.key },
        stable = maxOf(a.stable, b.stable),
        starSum = maxOf(a.starSum, b.starSum),
        unseen = when {
            a.unseen <= 0 -> b.unseen
            b.unseen <= 0 -> a.unseen
            else -> minOf(a.unseen, b.unseen)
        },
        cards = maxOf(a.cards, b.cards),
        fillPct = listOfNotNull(a.fillPct, b.fillPct).maxOrNull(),
        stablePct = listOfNotNull(a.stablePct, b.stablePct).maxOrNull()
    )

    private fun todayKey(): String = TodayTtsStore.todayKey()

    private fun dayKeyDaysAgo(days: Int): String {
        val c = TodayTtsStore.studyCalendar()
        c.add(Calendar.DAY_OF_YEAR, -days)
        return TodayTtsStore.keyOf(c)
    }

    private fun liveSnap(context: Context): SkillDaySnap {
        val cards = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() }
        val mastery = StudyProgressStore.mastery(context)
        val counts = RecallStore.allRecallCounts(context)
        val unseen = cards.count { (counts[it.id] ?: 0) <= 0 }
        return SkillDaySnap(
            key = todayKey(),
            stable = mastery.stable,
            starSum = mastery.starSum,
            unseen = unseen,
            cards = cards.size,
            fillPct = mastery.fillPct,
            stablePct = mastery.stablePct
        )
    }

    fun unseenByDay(context: Context): Map<String, Int> {
        capture(context)
        return read(context).days.mapValues { it.value.unseen }
    }

    fun weekPctDelta(context: Context): WeekPctDelta {
        capture(context)
        val now = StudyProgressStore.mastery(context)
        val week = read(context).days[dayKeyDaysAgo(7)] ?: return WeekPctDelta()
        val weekFill = week.fillPct
        val weekStable = week.stablePct
            ?: week.cards.takeIf { it > 0 }?.let { week.stable * 100 / it }
        return WeekPctDelta(
            achieve = weekFill?.let { now.fillPct - it },
            stable = weekStable?.let { now.stablePct - it }
        )
    }

    fun capture(context: Context) {
        val book = read(context)
        val snap = liveSnap(context)
        val keep = (0..40).map { dayKeyDaysAgo(it) }.toSet()
        val same = book.days[snap.key] == snap && book.days.keys.all { it in keep }
        if (same) return
        book.days[snap.key] = snap
        book.days.keys.retainAll(keep)
        write(context, book)
    }

    fun homeLine(context: Context): String {
        capture(context)
        val book = read(context)
        val now = book.days[todayKey()] ?: return ""
        val week = weekSnap(book, now.key) ?: yesterdaySnap(book, now.key) ?: return ""
        val stableGain = now.stable - week.stable
        val weekUnseenDrop = beforeThisWeekSnap(book, now.key)?.let { it.unseen - now.unseen }
        val sevenUnseenDrop = week.unseen - now.unseen
        val todayUnseenDrop = yesterdaySnap(book, now.key)?.let { it.unseen - now.unseen } ?: 0
        val starGain = now.starSum - week.starSum
        val vsWeek = week.key != dayKeyDaysAgo(1)
        val span = if (vsWeek) "지난주보다" else "어제보다"
        val core = when {
            stableGain >= 2 -> "안정권 장이 $span ${stableGain}장 늘었어요."
            weekUnseenDrop != null && weekUnseenDrop >= 2 ->
                "이번주 신규인출이 ${weekUnseenDrop}장이에요."
            weekUnseenDrop == null && sevenUnseenDrop >= 2 ->
                "최근 7일 신규인출이 ${sevenUnseenDrop}장이에요."
            todayUnseenDrop >= 2 -> "오늘 신규인출이 ${todayUnseenDrop}장이에요."
            starGain >= 3 -> "암기 칸 합이 $span ${starGain} 늘었어요."
            stableGain >= 1 -> "안정권 장이 $span ${stableGain}장 늘었어요."
            else -> ""
        }
        if (core.isBlank()) return ""
        val pace = paceTail(context, now, week, vsWeek)
        return if (pace.isBlank()) core else "$core $pace"
    }

    private fun weekSnap(book: SkillPulseBook, today: String): SkillDaySnap? {
        for (d in listOf(7, 6, 8, 5, 9, 10, 14)) {
            book.days[dayKeyDaysAgo(d)]?.let { if (it.key != today) return it }
        }
        return null
    }

    /** 이번 주 월요일 직전(일·토…) 스냅. 월~오늘 신규 인출 비교용. */
    private fun beforeThisWeekSnap(book: SkillPulseBook, today: String): SkillDaySnap? {
        val backToMonday = (TodayTtsStore.studyCalendar().get(Calendar.DAY_OF_WEEK)
            + 7 - Calendar.MONDAY) % 7
        for (extra in 1..10) {
            book.days[dayKeyDaysAgo(backToMonday + extra)]?.let { if (it.key != today) return it }
        }
        return null
    }

    private fun yesterdaySnap(book: SkillPulseBook, today: String): SkillDaySnap? =
        book.days[dayKeyDaysAgo(1)]?.takeIf { it.key != today }

    private fun paceTail(
        context: Context,
        now: SkillDaySnap,
        week: SkillDaySnap,
        vsWeek: Boolean
    ): String {
        if (!vsWeek) return ""
        val gain = now.stable - week.stable
        if (gain < 2) return ""
        val exam = AppPrefs.getExam2DateMillis(context)
        if (!DdayCalculator.isUpcoming(exam)) return ""
        val days = TimeUnit.MILLISECONDS.toDays(exam - System.currentTimeMillis()).toInt().coerceAtLeast(1)
        if (days < 8) return ""
        val weeks = days / 7f
        val proj = (now.stable + gain * weeks).roundToInt().coerceAtMost(now.cards)
        if (proj <= now.stable + 4) return ""
        return "이 속도면 2차 전에 안정권 약 ${proj}장."
    }

    fun recordSession(context: Context, good: Int, half: Int, miss: Int): String {
        val n = good + half + miss
        val book = read(context)
        val line = if (n >= 4) sessionLine(book, good, n) else ""
        book.sessions.add(
            SkillSessionSnap(
                at = System.currentTimeMillis(),
                good = good,
                half = half,
                miss = miss
            )
        )
        if (book.sessions.size > 30) {
            book.sessions = book.sessions.takeLast(30).toMutableList()
        }
        write(context, book)
        capture(context)
        return line
    }

    private fun sessionLine(book: SkillPulseBook, good: Int, n: Int): String {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val prev = book.sessions.filter {
            it.at >= since && it.good + it.half + it.miss >= 4
        }
        if (prev.size < 2) return ""
        val avgGood = prev.map { it.good }.average()
        val delta = (good - avgGood).roundToInt()
        if (delta >= 2) return "오늘 잘 떠올린 장이 최근 인출보다 ${delta}장 많아요."
        val thisPct = good * 100 / n
        val avgPct = prev.map {
            val t = it.good + it.half + it.miss
            if (t <= 0) 0 else it.good * 100 / t
        }.average()
        if (thisPct >= avgPct + 8) return "오늘 잘 떠올린 비율이 최근 인출보다 높아요."
        return ""
    }
}
