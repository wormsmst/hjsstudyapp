package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.Calendar
import kotlin.math.ceil

data class ExamLog(val at: Long = 0L, val subject: String = "")

data class DayCount(val key: String = "", val count: Int = 0)

data class DayFlags(
    val key: String = "",
    val study: Boolean = false,
    val quiz: Boolean = false,
    val exam: Boolean = false
)

data class WeakTopic(
    val subject: String,
    val topicTitle: String,
    val cardId: String,
    val memory: Int
)

data class ExamPace(
    val label: String,
    val subjects: List<String>,
    val weak: Int,
    val days: Int,
    val perDay: Int,
    val hasDate: Boolean
)

private data class StudyLog(
    var lastAt: Long = 0L,
    var todayKey: String = "",
    var todayTopics: MutableList<String> = mutableListOf(),
    var examLogs: MutableList<ExamLog> = mutableListOf(),
    var dayHistory: MutableList<DayCount> = mutableListOf(),
    var dayFlags: MutableList<DayFlags> = mutableListOf(),
    var todayStudy: Boolean = false,
    var todayQuiz: Boolean = false,
    var todayExam: Boolean = false
)

object StudyProgressStore {
    const val KIND_STUDY = "study"
    const val KIND_QUIZ = "quiz"
    const val KIND_EXAM = "exam"

    val EXAM1_SUBJECTS = listOf("민법", "행정절차론")
    val EXAM2_SUBJECTS = listOf("사무관리론", "행정사실무법")

    private const val FILE = "study_log.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return dayKey(c)
    }

    private fun dayKey(c: Calendar): String =
        "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)}"

    private fun read(context: Context): StudyLog {
        val f = file(context)
        if (!f.exists()) return StudyLog()
        return try {
            val log = gson.fromJson(f.readText(Charsets.UTF_8), StudyLog::class.java) ?: StudyLog()
            log.todayTopics = log.todayTopics ?: mutableListOf()
            log.examLogs = log.examLogs ?: mutableListOf()
            log.dayHistory = log.dayHistory ?: mutableListOf()
            log.dayFlags = log.dayFlags ?: mutableListOf()
            log
        } catch (_: Exception) {
            StudyLog()
        }
    }

    private fun write(context: Context, log: StudyLog) {
        file(context).writeText(gson.toJson(log), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    private fun rolled(context: Context): StudyLog {
        val log = read(context)
        val key = todayKey()
        if (log.todayKey != key) {
            if (log.todayKey.isNotBlank()) {
                log.dayHistory.removeAll { it.key == log.todayKey }
                log.dayHistory.add(DayCount(log.todayKey, log.todayTopics.size))
                if (log.dayHistory.size > 30) {
                    log.dayHistory = log.dayHistory.takeLast(30).toMutableList()
                }
                log.dayFlags.removeAll { it.key == log.todayKey }
                log.dayFlags.add(
                    DayFlags(log.todayKey, log.todayStudy, log.todayQuiz, log.todayExam)
                )
                if (log.dayFlags.size > 30) {
                    log.dayFlags = log.dayFlags.takeLast(30).toMutableList()
                }
            }
            log.todayKey = key
            log.todayTopics = mutableListOf()
            log.todayStudy = false
            log.todayQuiz = false
            log.todayExam = false
        }
        return log
    }

    fun todayTopicKeys(context: Context): Set<String> = rolled(context).todayTopics.toSet()

    fun markTopic(context: Context, subject: String, topicTitle: String) {
        val log = rolled(context)
        log.lastAt = System.currentTimeMillis()
        val key = "${canonicalizeSubject(subject)}|$topicTitle"
        if (key !in log.todayTopics) log.todayTopics.add(key)
        log.todayStudy = true
        write(context, log)
    }

    fun markActivity(context: Context, subject: String, kind: String = KIND_STUDY) {
        val log = rolled(context)
        log.lastAt = System.currentTimeMillis()
        val key = "${canonicalizeSubject(subject)}|"
        if (subject.isNotBlank() && key !in log.todayTopics) log.todayTopics.add(key)
        when (kind) {
            KIND_QUIZ -> log.todayQuiz = true
            KIND_EXAM -> log.todayExam = true
            else -> log.todayStudy = true
        }
        write(context, log)
    }

    fun recordExam(context: Context, subject: String) {
        val log = rolled(context)
        log.lastAt = System.currentTimeMillis()
        log.todayExam = true
        log.examLogs.add(ExamLog(System.currentTimeMillis(), canonicalizeSubject(subject)))
        if (log.examLogs.size > 40) {
            log.examLogs = log.examLogs.takeLast(40).toMutableList()
        }
        write(context, log)
    }

    fun lastAt(context: Context): Long = read(context).lastAt

    fun todayTopicCount(context: Context): Int {
        val log = rolled(context)
        return log.todayTopics.size
    }

    fun examLogs(context: Context): List<ExamLog> = read(context).examLogs

    data class SubjectBar(
        val subject: String,
        val unseen: Int,
        val weak: Int,
        val mid: Int,
        val master: Int,
        val total: Int
    ) {
        val masteredPct: Int
            get() = if (total == 0) 0 else (master * 100 / total)
    }

    fun subjectBars(context: Context): List<SubjectBar> {
        val concepts = CardStore.getAllCards(context).filter { it.type == "concept" }
        return CardStore.getSubjects(context)
            .map { canonicalizeSubject(it) }
            .distinct()
            .map { subject ->
                val list = concepts.filter { canonicalizeSubject(it.subject) == subject }
                var unseen = 0
                var weak = 0
                var mid = 0
                var master = 0
                list.forEach {
                    when (CardStore.getMemoryLevel(context, it.subject, it.topicTitle)) {
                        1 -> unseen++
                        2 -> weak++
                        3 -> mid++
                        else -> master++
                    }
                }
                SubjectBar(subject, unseen, weak, mid, master, list.size)
            }
    }

    fun weakCount(context: Context, subjects: List<String>? = null): Int {
        val want = subjects?.map { canonicalizeSubject(it) }?.toSet()
        val concepts = CardStore.getAllCards(context).filter { it.type == "concept" }
        return concepts.count { card ->
            val sub = canonicalizeSubject(card.subject)
            (want == null || sub in want) &&
                CardStore.getMemoryLevel(context, card.subject, card.topicTitle) <= 2
        }
    }

    fun examPaces(context: Context): List<ExamPace> {
        return listOf(
            paceFor(context, "1차", EXAM1_SUBJECTS, AppPrefs.getExam1DateMillis(context)),
            paceFor(context, "2차", EXAM2_SUBJECTS, AppPrefs.getExam2DateMillis(context))
        )
    }

    private fun paceFor(
        context: Context,
        label: String,
        subjects: List<String>,
        millis: Long
    ): ExamPace {
        val weak = weakCount(context, subjects)
        val upcoming = DdayCalculator.isUpcoming(millis)
        val days = if (upcoming) DdayCalculator.daysUntil(millis).coerceAtLeast(1) else 0
        val perDay = if (upcoming) ceil(weak.toDouble() / days).toInt().coerceIn(1, 40) else 0
        return ExamPace(label, subjects, weak, days, perDay, upcoming)
    }

    fun dailyGoal(context: Context): Int = RecallStore.sessionSize()

    fun last7Counts(context: Context): List<Int> {
        val log = rolled(context)
        val map = log.dayHistory.associate { it.key to it.count }.toMutableMap()
        map[log.todayKey] = log.todayTopics.size
        val out = mutableListOf<Int>()
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            out.add(map[dayKey(c)] ?: 0)
        }
        return out
    }

    fun last7Flags(context: Context): List<DayFlags> {
        val log = rolled(context)
        val map = log.dayFlags.associateBy { it.key }.toMutableMap()
        map[log.todayKey] = DayFlags(log.todayKey, log.todayStudy, log.todayQuiz, log.todayExam)
        val out = mutableListOf<DayFlags>()
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val k = dayKey(c)
            out.add(map[k] ?: DayFlags(k))
        }
        return out
    }

    fun weekDayLabels(): List<String> {
        val names = listOf("일", "월", "화", "수", "목", "금", "토")
        val out = mutableListOf<String>()
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            out.add(names[c.get(Calendar.DAY_OF_WEEK) - 1])
        }
        return out
    }

    fun weakTopics(context: Context, limit: Int = 12): List<WeakTopic> {
        return CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() }
            .map {
                WeakTopic(
                    subject = canonicalizeSubject(it.subject),
                    topicTitle = it.topicTitle,
                    cardId = it.id,
                    memory = CardStore.getMemoryLevel(context, it.subject, it.topicTitle)
                )
            }
            .filter { it.memory <= 2 }
            .sortedWith(compareBy({ it.memory }, { it.subject }, { naturalSortKey(it.topicTitle) }))
            .distinctBy { "${it.subject}|${it.topicTitle}" }
            .take(limit)
    }

    fun paceLine(context: Context): String {
        val lines = examPaces(context).map { p ->
            if (!p.hasDate) {
                "${p.label} 시험일을 정하면 약점 ${p.weak}개를 나눠 드려요"
            } else {
                "${p.label} 약점 ${p.weak}개 · ${p.days}일 · 하루 약 ${p.perDay}개"
            }
        }
        return "하루 인출은 평일 ${RecallStore.WEEKDAY_SESSION}장, 주말 ${RecallStore.WEEKEND_SESSION}장입니다.\n" + lines.joinToString("\n")
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parse(localJson)
        val b = parse(cloudJson)
        val today = todayKey()
        val hist = mutableMapOf<String, Int>()
        val flags = mutableMapOf<String, DayFlags>()
        fun absorb(log: StudyLog) {
            log.dayHistory.forEach { hist[it.key] = maxOf(hist[it.key] ?: 0, it.count) }
            log.dayFlags.forEach { old ->
                val cur = flags[old.key]
                flags[old.key] = DayFlags(
                    old.key,
                    (cur?.study == true) || old.study,
                    (cur?.quiz == true) || old.quiz,
                    (cur?.exam == true) || old.exam
                )
            }
            if (log.todayKey.isNotBlank()) {
                hist[log.todayKey] = maxOf(hist[log.todayKey] ?: 0, log.todayTopics.size)
                val cur = flags[log.todayKey]
                flags[log.todayKey] = DayFlags(
                    log.todayKey,
                    (cur?.study == true) || log.todayStudy,
                    (cur?.quiz == true) || log.todayQuiz,
                    (cur?.exam == true) || log.todayExam
                )
            }
        }
        absorb(a)
        absorb(b)
        val topics = linkedSetOf<String>()
        if (a.todayKey == today) topics.addAll(a.todayTopics)
        if (b.todayKey == today) topics.addAll(b.todayTopics)
        val todayFlag = flags[today]
        val out = StudyLog(
            lastAt = maxOf(a.lastAt, b.lastAt),
            todayKey = today,
            todayTopics = topics.toMutableList(),
            examLogs = (a.examLogs + b.examLogs)
                .distinctBy { "${it.at}|${it.subject}" }
                .sortedBy { it.at }
                .takeLast(80)
                .toMutableList(),
            dayHistory = hist.filter { it.key != today }
                .map { DayCount(it.key, it.value) }
                .sortedBy { it.key }
                .takeLast(30)
                .toMutableList(),
            dayFlags = flags.filter { it.key != today }
                .values
                .sortedBy { it.key }
                .takeLast(30)
                .toMutableList(),
            todayStudy = (a.todayKey == today && a.todayStudy) ||
                (b.todayKey == today && b.todayStudy) ||
                (todayFlag?.study == true),
            todayQuiz = (a.todayKey == today && a.todayQuiz) ||
                (b.todayKey == today && b.todayQuiz) ||
                (todayFlag?.quiz == true),
            todayExam = (a.todayKey == today && a.todayExam) ||
                (b.todayKey == today && b.todayExam) ||
                (todayFlag?.exam == true)
        )
        return gson.toJson(out)
    }

    private fun parse(json: String): StudyLog {
        if (json.isBlank()) return StudyLog()
        return try {
            val log = gson.fromJson(json, StudyLog::class.java) ?: StudyLog()
            log.todayTopics = log.todayTopics ?: mutableListOf()
            log.examLogs = log.examLogs ?: mutableListOf()
            log.dayHistory = log.dayHistory ?: mutableListOf()
            log.dayFlags = log.dayFlags ?: mutableListOf()
            log
        } catch (_: Exception) {
            StudyLog()
        }
    }
}
