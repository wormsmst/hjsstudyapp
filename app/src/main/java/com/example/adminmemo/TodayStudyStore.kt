package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.Calendar

data class TodayRecallMark(
    var cardId: String = "",
    var recallCount: Int = 0,
    var reps: Int = 0,
    var lapses: Int = 0,
    var lastGradeAt: Long = 0L
)

private data class TodayStudyBook(
    var date: String = "",
    var recalled: MutableList<String> = mutableListOf(),
    var news: MutableList<String> = mutableListOf(),
    var cases: Int = 0,
    var exams: Int = 0,
    var topics: MutableList<String> = mutableListOf(),
    var study: Boolean = false,
    var quiz: Boolean = false,
    var exam: Boolean = false,
    var readIds: MutableList<String> = mutableListOf(),
    var recallDone: Boolean = false,
    var gradedIds: MutableList<String> = mutableListOf(),
    var quests: MutableList<DailyQuest> = mutableListOf(),
    var marks: MutableList<TodayRecallMark> = mutableListOf(),
    var days: MutableMap<String, TodayStudyBook> = mutableMapOf()
)

/** 최근 며칠 공부를 오늘 파일에 같이 실어, 폰·태블릿이 주간 샤드 없이도 맞춘다. */
object TodayStudyStore {
    const val FILE = "today_study.json"
    private const val KEEP_DAYS = 14
    private val gson = Gson()
    @Volatile private var capturing = false

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun canon(date: String) = TodayTtsStore.canonKey(date)

    private fun keepKeys(): Set<String> {
        val c = TodayTtsStore.studyCalendar()
        return (0 until KEEP_DAYS).map {
            val d = c.clone() as Calendar
            d.add(Calendar.DAY_OF_YEAR, -it)
            TodayTtsStore.keyOf(d)
        }.toSet()
    }

    private fun hydrate(b: TodayStudyBook): TodayStudyBook {
        b.recalled = b.recalled ?: mutableListOf()
        b.news = b.news ?: mutableListOf()
        b.topics = b.topics ?: mutableListOf()
        b.readIds = b.readIds ?: mutableListOf()
        b.gradedIds = b.gradedIds ?: mutableListOf()
        b.quests = b.quests ?: mutableListOf()
        b.marks = b.marks ?: mutableListOf()
        b.days = b.days ?: mutableMapOf()
        if (b.date.isNotBlank()) b.date = canon(b.date)
        val nested = mutableMapOf<String, TodayStudyBook>()
        b.days.forEach { (k, v) ->
            nested[canon(k)] = hydrateDay(v)
        }
        b.days = nested
        val slice = sliceOf(b)
        if (!isDayHollow(slice) && b.date.isNotBlank()) {
            val old = b.days[b.date]
            b.days[b.date] = if (old == null) slice else union(old, slice)
        }
        return b
    }

    private fun hydrateDay(b: TodayStudyBook): TodayStudyBook {
        b.recalled = b.recalled ?: mutableListOf()
        b.news = b.news ?: mutableListOf()
        b.topics = b.topics ?: mutableListOf()
        b.readIds = b.readIds ?: mutableListOf()
        b.gradedIds = b.gradedIds ?: mutableListOf()
        b.quests = b.quests ?: mutableListOf()
        b.marks = b.marks ?: mutableListOf()
        b.days = mutableMapOf()
        if (b.date.isNotBlank()) b.date = canon(b.date)
        return b
    }

    private fun parse(json: String): TodayStudyBook {
        if (json.isBlank()) return TodayStudyBook()
        return try {
            hydrate(gson.fromJson(json, TodayStudyBook::class.java) ?: TodayStudyBook())
        } catch (_: Exception) {
            TodayStudyBook()
        }
    }

    private fun sliceOf(b: TodayStudyBook) = TodayStudyBook(
        date = b.date,
        recalled = b.recalled.toMutableList(),
        news = b.news.toMutableList(),
        cases = b.cases,
        exams = b.exams,
        topics = b.topics.toMutableList(),
        study = b.study,
        quiz = b.quiz,
        exam = b.exam,
        readIds = b.readIds.toMutableList(),
        recallDone = b.recallDone,
        gradedIds = b.gradedIds.toMutableList(),
        quests = b.quests.map { it.copy() }.toMutableList(),
        marks = b.marks.map { it.copy() }.toMutableList()
    )

    private fun flatten(pack: TodayStudyBook): TodayStudyBook {
        val today = TodayTtsStore.todayKey()
        val t = pack.days[today] ?: TodayStudyBook(date = today)
        return sliceOf(t).copy(date = today, days = pack.days)
    }

    private fun isDayHollow(b: TodayStudyBook): Boolean =
        b.recalled.isEmpty() && b.news.isEmpty() && b.cases <= 0 && b.exams <= 0 &&
            b.topics.isEmpty() && !b.study && !b.quiz && !b.exam &&
            b.readIds.isEmpty() && !b.recallDone && b.gradedIds.isEmpty() &&
            b.quests.none { it.progress > 0 || it.done } && b.marks.isEmpty()

    fun isHollowJson(json: String): Boolean {
        val b = parse(json)
        if (!isDayHollow(sliceOf(b))) return false
        return b.days.values.all { isDayHollow(it) }
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parse(localJson)
        val b = parse(cloudJson)
        val days = mutableMapOf<String, TodayStudyBook>()
        (a.days.keys + b.days.keys).forEach { k ->
            val la = a.days[k]
            val lb = b.days[k]
            days[k] = when {
                la == null -> lb!!
                lb == null -> la
                else -> union(la, lb)
            }
        }
        val keep = keepKeys()
        days.keys.filter { it !in keep }.toList().forEach { days.remove(it) }
        val pack = TodayStudyBook(date = TodayTtsStore.todayKey(), days = days)
        return gson.toJson(flatten(pack))
    }

    private fun union(a: TodayStudyBook, b: TodayStudyBook): TodayStudyBook {
        val quests = DailyQuestStore.mergeQuestLists(listOf(a.quests, b.quests), preferFresh = true)
        val marks = linkedMapOf<String, TodayRecallMark>()
        (a.marks + b.marks).forEach { m ->
            if (m.cardId.isBlank()) return@forEach
            val old = marks[m.cardId]
            if (old == null) {
                marks[m.cardId] = m.copy()
            } else {
                marks[m.cardId] = TodayRecallMark(
                    cardId = m.cardId,
                    recallCount = maxOf(old.recallCount, m.recallCount),
                    reps = maxOf(old.reps, m.reps),
                    lapses = maxOf(old.lapses, m.lapses),
                    lastGradeAt = maxOf(old.lastGradeAt, m.lastGradeAt)
                )
            }
        }
        return TodayStudyBook(
            date = canon(a.date.ifBlank { b.date }),
            recalled = (a.recalled + b.recalled).distinct().filter { it.isNotBlank() }.toMutableList(),
            news = (a.news + b.news).distinct().filter { it.isNotBlank() }.toMutableList(),
            cases = maxOf(a.cases, b.cases),
            exams = maxOf(a.exams, b.exams),
            topics = (a.topics + b.topics).distinct().toMutableList(),
            study = a.study || b.study,
            quiz = a.quiz || b.quiz,
            exam = a.exam || b.exam,
            readIds = (a.readIds + b.readIds).distinct().toMutableList(),
            recallDone = a.recallDone || b.recallDone,
            gradedIds = (a.gradedIds + b.gradedIds).distinct().toMutableList(),
            quests = quests,
            marks = marks.values.toMutableList()
        )
    }

    fun rollIntoAccumulated(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        val pack = parse(f.readText(Charsets.UTF_8))
        val keep = keepKeys()
        var changed = false
        pack.days.keys.filter { it !in keep }.toList().forEach { k ->
            pack.days.remove(k)?.let { applyOntoLocal(context, it.copy(date = k)) }
            changed = true
        }
        if (!changed && (pack.date.isBlank() || pack.date == TodayTtsStore.todayKey())) return
        if (pack.date.isNotBlank() && pack.date !in keep) {
            applyOntoLocal(context, sliceOf(pack))
        }
        file(context).writeText(gson.toJson(flatten(pack.copy(date = TodayTtsStore.todayKey()))), Charsets.UTF_8)
    }

    fun captureFromStores(context: Context) {
        if (capturing) return
        capturing = true
        try {
            rollIntoAccumulated(context)
            val today = TodayTtsStore.todayKey()
            val prev = if (file(context).exists()) parse(file(context).readText(Charsets.UTF_8))
            else TodayStudyBook(date = today)
            val days = prev.days.toMutableMap()
            keepKeys().forEach { key ->
                val snap = dayFromStores(context, key)
                val old = days[key]
                days[key] = when {
                    old == null && isDayHollow(snap) -> return@forEach
                    old == null -> snap
                    else -> union(old, snap)
                }
            }
            val todaySnap = days[today] ?: dayFromStores(context, today)
            if (RecallSessionStore.openToday(context)) todaySnap.recallDone = false
            else todaySnap.recallDone = DailyQuestStore.recallDoneToday(context)
            days[today] = todaySnap
            file(context).writeText(
                gson.toJson(flatten(TodayStudyBook(date = today, days = days))),
                Charsets.UTF_8
            )
        } finally {
            capturing = false
        }
    }

    private fun dayFromStores(context: Context, key: String): TodayStudyBook {
        val flags = StudyProgressStore.flagsForDay(context, key)
        val snap = DailyQuestStore.daySnap(context, key)
        return TodayStudyBook(
            date = key,
            recalled = StudyWeekStore.dayRecalled(context, key).toMutableList(),
            news = StudyWeekStore.dayNews(context, key).toMutableList(),
            cases = StudyWeekStore.dayCases(context, key),
            exams = StudyWeekStore.dayExams(context, key),
            topics = if (key == TodayTtsStore.todayKey()) {
                StudyProgressStore.todayTopicKeys(context).toMutableList()
            } else mutableListOf(),
            study = flags.first,
            quiz = flags.second,
            exam = flags.third,
            readIds = ReadPassStore.idsOnDay(context, key).toMutableList(),
            recallDone = snap?.recallDone == true,
            gradedIds = snap?.gradedIds.orEmpty().toMutableList(),
            quests = snap?.quests.orEmpty().map { it.copy() }.toMutableList(),
            marks = RecallStore.exportMarksOnDay(context, key).toMutableList()
        )
    }

    fun foldIfStaleJson(context: Context, json: String) {
        applyAllDays(context, parse(json))
    }

    fun applyMerged(context: Context, json: String) {
        applyAllDays(context, parse(json))
    }

    private fun applyAllDays(context: Context, pack: TodayStudyBook) {
        val seen = mutableSetOf<String>()
        pack.days.forEach { (k, day) ->
            if (k.isBlank() || isDayHollow(day)) return@forEach
            applyOntoLocal(context, day.copy(date = k))
            seen.add(k)
        }
        val top = sliceOf(pack)
        if (pack.date.isNotBlank() && pack.date !in seen && !isDayHollow(top)) {
            applyOntoLocal(context, top)
        }
    }

    private fun applyOntoLocal(context: Context, book: TodayStudyBook) {
        if (book.date.isBlank()) return
        val date = canon(book.date)
        StudyWeekStore.overlayDay(
            context, date, book.recalled, book.news, book.cases, book.exams
        )
        val recalledToday = book.recallDone || book.gradedIds.isNotEmpty() ||
            book.quests.any { it.done || it.progress > 0 } || book.marks.isNotEmpty() ||
            book.recalled.any { it.isNotBlank() && !it.startsWith("seed:") } ||
            book.cases > 0 || book.exams > 0
        StudyProgressStore.overlayToday(
            context,
            date,
            book.topics,
            book.study || recalledToday,
            book.quiz || recalledToday,
            book.exam
        )
        ReadPassStore.overlayDay(context, date, book.readIds)
        DailyQuestStore.overlayToday(
            context, date, book.recallDone || recalledToday, book.gradedIds, book.quests
        )
        RecallStore.overlayMarks(context, book.marks)
    }

    const val HITS_DAYS = 14

    fun packHits(context: Context): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        hitKeys().forEach { key ->
            val snap = dayFromStores(context, key)
            if (isDayHollow(snap)) return@forEach
            out[key] = hitOf(snap)
        }
        return out
    }

    fun unionHits(local: Map<String, Any>, cloud: Any?): Map<String, Any> {
        val keep = hitKeys().toSet()
        val b = asHitDays(cloud)
        val keys = (local.keys + b.keys).map { canon(it) }.filter { it in keep }.toSet()
        val out = linkedMapOf<String, Any>()
        keys.forEach { k ->
            val ua = local[k] ?: local.entries.firstOrNull { canon(it.key) == k }?.value
            val ub = b[k] ?: b.entries.firstOrNull { canon(it.key) == k }?.value
            val merged = unionHit(ua, ub) ?: return@forEach
            out[k] = merged
        }
        return out
    }

    fun applyHits(context: Context, raw: Any?) {
        val days = asHitDays(raw)
        if (days.isEmpty()) return
        days.forEach { (k, v) ->
            val book = bookFromHit(k, v) ?: return@forEach
            if (isDayHollow(book)) return@forEach
            applyOntoLocal(context, book)
        }
    }

    private fun hitKeys(): List<String> {
        val c = TodayTtsStore.studyCalendar()
        return (0 until HITS_DAYS).map {
            val d = c.clone() as Calendar
            d.add(Calendar.DAY_OF_YEAR, -it)
            TodayTtsStore.keyOf(d)
        }
    }

    private fun hitOf(b: TodayStudyBook): Map<String, Any> {
        val recalled = b.recalled.filter { it.isNotBlank() && !it.startsWith("seed:") }
        val news = b.news.filter { it.isNotBlank() && !it.startsWith("seed:") }
        val graded = (b.gradedIds + recalled).distinct().filter { it.isNotBlank() }
        val done = b.recallDone || recalled.isNotEmpty() || graded.isNotEmpty() ||
            b.cases > 0 || b.exams > 0
        return hashMapOf(
            "recalled" to recalled,
            "news" to news,
            "cases" to b.cases,
            "exams" to b.exams,
            "recallDone" to done,
            "gradedIds" to graded,
            "study" to (b.study || done),
            "quiz" to (b.quiz || done),
            "exam" to b.exam
        )
    }

    private fun asHitDays(raw: Any?): Map<String, Any> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val out = linkedMapOf<String, Any>()
        map.forEach { (k, v) ->
            val key = canon(k?.toString().orEmpty())
            if (key.isBlank() || v == null) return@forEach
            out[key] = v
        }
        return out
    }

    private fun unionHit(a: Any?, b: Any?): Map<String, Any>? {
        if (a == null && b == null) return null
        if (a == null) return asHitMap(b)
        if (b == null) return asHitMap(a)
        val ha = asHitMap(a) ?: return asHitMap(b)
        val hb = asHitMap(b) ?: return ha
        val recalled = (asIds(ha["recalled"]) + asIds(hb["recalled"])).distinct()
        val news = (asIds(ha["news"]) + asIds(hb["news"])).distinct()
        val graded = (asIds(ha["gradedIds"]) + asIds(hb["gradedIds"])).distinct()
        return hashMapOf(
            "recalled" to recalled,
            "news" to news,
            "cases" to maxOf(asInt(ha["cases"]), asInt(hb["cases"])),
            "exams" to maxOf(asInt(ha["exams"]), asInt(hb["exams"])),
            "recallDone" to (asBool(ha["recallDone"]) || asBool(hb["recallDone"])),
            "gradedIds" to graded,
            "study" to (asBool(ha["study"]) || asBool(hb["study"])),
            "quiz" to (asBool(ha["quiz"]) || asBool(hb["quiz"])),
            "exam" to (asBool(ha["exam"]) || asBool(hb["exam"]))
        )
    }

    private fun asHitMap(v: Any?): Map<String, Any>? {
        val m = v as? Map<*, *> ?: return null
        val out = linkedMapOf<String, Any>()
        m.forEach { (k, value) ->
            val key = k?.toString().orEmpty()
            if (key.isNotBlank() && value != null) out[key] = value
        }
        return out
    }

    private fun bookFromHit(date: String, raw: Any): TodayStudyBook? {
        val m = asHitMap(raw) ?: return null
        val recalled = asIds(m["recalled"]).toMutableList()
        val graded = asIds(m["gradedIds"]).toMutableList()
        return TodayStudyBook(
            date = canon(date),
            recalled = recalled,
            news = asIds(m["news"]).toMutableList(),
            cases = asInt(m["cases"]),
            exams = asInt(m["exams"]),
            study = asBool(m["study"]),
            quiz = asBool(m["quiz"]),
            exam = asBool(m["exam"]),
            recallDone = asBool(m["recallDone"]) || recalled.isNotEmpty(),
            gradedIds = (if (graded.isNotEmpty()) graded else recalled).toMutableList()
        )
    }

    private fun asIds(v: Any?): List<String> =
        (v as? List<*>)?.mapNotNull { it?.toString() }
            ?.filter { it.isNotBlank() && !it.startsWith("seed:") }
            ?: emptyList()

    private fun asInt(v: Any?): Int = (v as? Number)?.toInt() ?: 0

    private fun asBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> false
    }

}
