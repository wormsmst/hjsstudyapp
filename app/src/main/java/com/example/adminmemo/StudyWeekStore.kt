package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.Calendar

private data class WeekDaySnap(
    var recalled: MutableList<String> = mutableListOf(),
    var news: MutableList<String> = mutableListOf(),
    var cases: Int = 0,
    var exams: Int = 0
)

private data class StudyWeekBook(
    var days: MutableMap<String, WeekDaySnap> = mutableMapOf(),
    var updatedAt: Long = 0L
)

data class WeekStudyStat(
    val mondayKey: String,
    val rangeLabel: String,
    val recalled: Int,
    val news: Int,
    val cases: Int,
    val exams: Int,
    val isCurrent: Boolean
)

/** 공부일 기준 주간 인출 장수·신규 장수·사례·모의고사. */
object StudyWeekStore {
    private const val FILE = "study_week.json"
    private const val KEEP_DAYS = 140
    private const val WEEK_DAYS = 7
    const val WINDOW = 5
    const val STEP = 3
    const val MAX_SHIFT = 18
    private val gson = Gson()
    @Volatile private var cached: StudyWeekBook? = null

    fun invalidateCache() {
        cached = null
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun parse(json: String): StudyWeekBook {
        if (json.isBlank()) return StudyWeekBook()
        return try {
            val book = gson.fromJson(json, StudyWeekBook::class.java) ?: StudyWeekBook()
            book.days = book.days ?: mutableMapOf()
            val canonDays = mutableMapOf<String, WeekDaySnap>()
            book.days.forEach { (raw, snap) ->
                snap.recalled = snap.recalled?.distinct()?.toMutableList() ?: mutableListOf()
                snap.news = snap.news?.distinct()?.toMutableList() ?: mutableListOf()
                snap.cases = snap.cases.coerceAtLeast(0)
                snap.exams = snap.exams.coerceAtLeast(0)
                val key = TodayTtsStore.canonKey(raw)
                val old = canonDays[key]
                canonDays[key] = if (old == null) snap else WeekDaySnap(
                    recalled = (old.recalled + snap.recalled).distinct().toMutableList(),
                    news = (old.news + snap.news).distinct().toMutableList(),
                    cases = maxOf(old.cases, snap.cases),
                    exams = maxOf(old.exams, snap.exams)
                )
            }
            book.days = canonDays
            book
        } catch (_: Exception) {
            StudyWeekBook()
        }
    }

    private fun read(context: Context): StudyWeekBook {
        cached?.let { return it }
        val f = file(context)
        val book = if (!f.exists()) StudyWeekBook() else parse(f.readText(Charsets.UTF_8))
        cached = book
        return book
    }

    private fun write(context: Context, book: StudyWeekBook) {
        book.updatedAt = System.currentTimeMillis()
        cached = book
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    private fun prune(book: StudyWeekBook) {
        val keep = (0 until KEEP_DAYS).map { dayKeyDaysAgo(it) }.toSet()
        book.days.keys.retainAll(keep)
    }

    private fun dayKeyDaysAgo(days: Int): String {
        val c = TodayTtsStore.studyCalendar()
        c.add(Calendar.DAY_OF_YEAR, -days)
        return TodayTtsStore.keyOf(c)
    }

    private fun mondayCal(from: Calendar = TodayTtsStore.studyCalendar()): Calendar {
        val c = from.clone() as Calendar
        val back = (c.get(Calendar.DAY_OF_WEEK) + 7 - Calendar.MONDAY) % 7
        c.add(Calendar.DAY_OF_YEAR, -back)
        return c
    }

    private fun dayOf(book: StudyWeekBook, key: String): WeekDaySnap =
        book.days.getOrPut(TodayTtsStore.canonKey(key)) { WeekDaySnap() }

    fun noteRecall(context: Context, cardId: String, isCase: Boolean, wasNew: Boolean) {
        if (cardId.isBlank()) return
        val book = read(context)
        val snap = dayOf(book, TodayTtsStore.todayKey())
        if (isCase) {
            snap.cases += 1
        } else {
            if (cardId !in snap.recalled) snap.recalled.add(cardId)
            if (wasNew && cardId !in snap.news) snap.news.add(cardId)
        }
        prune(book)
        write(context, book)
    }

    /** 오늘 학습했으면 오늘부터, 아직이면 어제까지. 하루가 비면 끊긴다. */
    fun studyStreak(context: Context): Int {
        val book = read(context)
        val graded = DailyQuestStore.gradedIdsByDay(context)
        val markDays = RecallStore.lastGradeMarks(context).map { it.second }.toHashSet()
        val progressDays = StudyProgressStore.studiedDayKeys(context)
        val readDays = ReadPassStore.daysWithPass(context)
        fun realId(id: String) = id.isNotBlank() && !id.startsWith("seed:")
        fun hit(offset: Int): Boolean {
            val key = dayKeyDaysAgo(offset)
            val snap = book.days[key]
            if (snap != null && (
                snap.recalled.any { realId(it) } || snap.cases > 0 || snap.exams > 0
            )) return true
            if (!graded[key].isNullOrEmpty()) return true
            if (key in markDays || key in progressDays || key in readDays) return true
            val q = DailyQuestStore.daySnap(context, key)
            return q != null && (
                q.recallDone || q.gradedCount() > 0 || q.quests.any { it.done || it.progress > 0 }
            )
        }
        val start = if (hit(0)) 0 else 1
        if (!hit(start)) return 0
        var n = 0
        var d = start
        while (d < KEEP_DAYS && hit(d)) {
            n++
            d++
        }
        return n
    }

    fun everRecalledIds(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        read(context).days.values.forEach { snap ->
            snap.recalled.forEach { id ->
                if (id.isNotBlank() && !id.startsWith("seed:")) out.add(id)
            }
            snap.news.forEach { id ->
                if (id.isNotBlank() && !id.startsWith("seed:")) out.add(id)
            }
        }
        return out
    }

    fun todayNewIds(context: Context): Set<String> {
        val snap = read(context).days[TodayTtsStore.todayKey()] ?: return emptySet()
        return snap.news.filter { it.isNotBlank() && !it.startsWith("seed:") }.toSet()
    }

    /** 최근 7일 처음 인출한 장. 클라우드 주간 기록만 써서 기기마다 같게 맞춘다. */
    fun last7NewCount(context: Context): Int {
        val ids = linkedSetOf<String>()
        (0 until 7).forEach { ago ->
            dayNews(context, dayKeyDaysAgo(ago)).forEach { ids.add(it) }
        }
        return ids.size
    }

    fun dayRecalled(context: Context, date: String): List<String> =
        read(context).days[TodayTtsStore.canonKey(date)]?.recalled.orEmpty()
            .filter { it.isNotBlank() && !it.startsWith("seed:") }

    fun dayNews(context: Context, date: String): List<String> =
        read(context).days[TodayTtsStore.canonKey(date)]?.news.orEmpty()
            .filter { it.isNotBlank() && !it.startsWith("seed:") }

    fun dayCases(context: Context, date: String): Int =
        read(context).days[TodayTtsStore.canonKey(date)]?.cases?.coerceAtLeast(0) ?: 0

    fun dayExams(context: Context, date: String): Int =
        read(context).days[TodayTtsStore.canonKey(date)]?.exams?.coerceAtLeast(0) ?: 0

    fun recentHitKeys(context: Context, n: Int): List<String> {
        val book = read(context)
        return (0 until n).map { dayKeyDaysAgo(it) }.filter { key ->
            val snap = book.days[key] ?: return@filter false
            snap.recalled.any { it.isNotBlank() && !it.startsWith("seed:") } ||
                snap.news.any { it.isNotBlank() && !it.startsWith("seed:") } ||
                snap.cases > 0 || snap.exams > 0
        }
    }

    fun overlayDay(
        context: Context,
        date: String,
        recalled: Collection<String>,
        news: Collection<String>,
        cases: Int,
        exams: Int
    ) {
        if (date.isBlank()) return
        val book = read(context)
        val snap = dayOf(book, date)
        recalled.forEach { id ->
            if (id.isNotBlank() && !id.startsWith("seed:") && id !in snap.recalled) snap.recalled.add(id)
        }
        news.forEach { id ->
            if (id.isNotBlank() && !id.startsWith("seed:") && id !in snap.news) snap.news.add(id)
        }
        snap.cases = maxOf(snap.cases, cases)
        snap.exams = maxOf(snap.exams, exams)
        prune(book)
        book.updatedAt = System.currentTimeMillis()
        cached = book
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
    }

    fun noteExam(context: Context) {
        val book = read(context)
        val snap = dayOf(book, TodayTtsStore.todayKey())
        snap.exams += 1
        prune(book)
        write(context, book)
    }

    fun window(context: Context, shift: Int, size: Int = WINDOW): List<WeekStudyStat> {
        val book = read(context)
        val currentMon = TodayTtsStore.keyOf(mondayCal())
        val start = mondayCal()
        start.add(Calendar.DAY_OF_YEAR, -7 * shift.coerceAtLeast(0))
        val graded = DailyQuestStore.gradedIdsByDay(context)
        val marks = RecallStore.lastGradeMarks(context)
        val examsByDay = mutableMapOf<String, Int>()
        StudyProgressStore.examLogs(context).forEach { log ->
            val day = TodayTtsStore.dayKey(log.at)
            if (day.isNotBlank()) examsByDay[day] = (examsByDay[day] ?: 0) + 1
        }
        return (0 until size).map { i ->
            val mon = start.clone() as Calendar
            mon.add(Calendar.DAY_OF_YEAR, -7 * i)
            statFor(book, mon, currentMon, graded, marks, examsByDay)
        }
    }

    private fun statFor(
        book: StudyWeekBook,
        monday: Calendar,
        currentMon: String,
        graded: Map<String, List<String>>,
        marks: List<Triple<String, String, Int>>,
        examsByDay: Map<String, Int>
    ): WeekStudyStat {
        val recalled = linkedSetOf<String>()
        val news = linkedSetOf<String>()
        val caseIds = linkedSetOf<String>()
        var caseHits = 0
        var exams = 0
        val monKey = TodayTtsStore.keyOf(monday)
        val weekDays = (0 until WEEK_DAYS).map { i ->
            val d = monday.clone() as Calendar
            d.add(Calendar.DAY_OF_YEAR, i)
            TodayTtsStore.keyOf(d)
        }
        val weekSet = weekDays.toSet()
        weekDays.forEach { key ->
            val snap = book.days[key]
            if (snap != null) {
                snap.recalled.forEach { id ->
                    if (id.startsWith("case_")) caseIds.add(id) else recalled.add(id)
                }
                news.addAll(snap.news.filter { !it.startsWith("seed:") })
                caseHits += snap.cases
            }
            graded[key].orEmpty().forEach { id ->
                if (id.startsWith("case_")) caseIds.add(id) else recalled.add(id)
            }
            exams += maxOf(snap?.exams ?: 0, examsByDay[key] ?: 0)
        }
        marks.forEach { (id, day, count) ->
            if (day !in weekSet) return@forEach
            if (id.startsWith("case_")) {
                caseIds.add(id)
            } else {
                recalled.add(id)
                if (count == 1) news.add(id)
            }
        }
        val sun = monday.clone() as Calendar
        sun.add(Calendar.DAY_OF_YEAR, WEEK_DAYS - 1)
        val sunKey = TodayTtsStore.keyOf(sun)
        return WeekStudyStat(
            mondayKey = monKey,
            rangeLabel = "${shortDate(monKey)}–${shortDate(sunKey)}",
            recalled = recalled.size,
            news = news.size,
            cases = maxOf(caseHits, caseIds.size),
            exams = exams,
            isCurrent = monKey == currentMon
        )
    }

    private fun shortDate(key: String): String {
        val p = key.split("-")
        if (p.size < 3) return key
        val m = p[1].toIntOrNull() ?: return key
        val d = p[2].toIntOrNull() ?: return key
        return "$m/$d"
    }

    fun todayVolume(json: String): Int {
        if (json.isBlank()) return 0
        val snap = parse(json).days[TodayTtsStore.todayKey()] ?: return 0
        return snap.recalled.distinct().count { it.isNotBlank() && !it.startsWith("seed:") } +
            snap.news.distinct().count { it.isNotBlank() && !it.startsWith("seed:") } +
            snap.cases.coerceAtLeast(0) +
            snap.exams.coerceAtLeast(0)
    }

    fun isHollowJson(json: String): Boolean {
        if (json.isBlank()) return true
        val book = parse(json)
        return book.days.values.none {
            it.recalled.isNotEmpty() || it.news.isNotEmpty() || it.cases > 0 || it.exams > 0
        }
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parse(localJson)
        val b = parse(cloudJson)
        if (isHollowJson(localJson) && !isHollowJson(cloudJson)) return gson.toJson(b)
        if (isHollowJson(cloudJson) && !isHollowJson(localJson)) return gson.toJson(a)
        val out = StudyWeekBook(updatedAt = maxOf(a.updatedAt, b.updatedAt))
        (a.days.keys + b.days.keys).forEach { key ->
            val la = a.days[key]
            val lb = b.days[key]
            if (la == null) {
                out.days[key] = lb ?: return@forEach
                return@forEach
            }
            if (lb == null) {
                out.days[key] = la
                return@forEach
            }
            out.days[key] = WeekDaySnap(
                recalled = (la.recalled + lb.recalled).distinct().toMutableList(),
                news = (la.news + lb.news).distinct().toMutableList(),
                cases = maxOf(la.cases, lb.cases),
                exams = maxOf(la.exams, lb.exams)
            )
        }
        return gson.toJson(out)
    }
}
