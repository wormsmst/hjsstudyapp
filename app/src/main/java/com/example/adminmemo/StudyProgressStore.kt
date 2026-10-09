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
    val memory: Int,
    val recallCount: Int = 0,
    val target: Int = 5
)

data class RecallCoverage(
    val total: Int,
    val done: Int,
    val remaining: Int,
    val weak: Int,
    val days: Int,
    val perDay: Int,
    val hasDate: Boolean
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
    const val KIND_RECALL = "quiz"
    const val KIND_EXAM = "exam"

    val EXAM1_SUBJECTS = listOf("민법", "행정절차론")
    val EXAM2_SUBJECTS = listOf("사무관리론", "행정사실무법")

    private const val FILE = "study_log.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun todayKey(): String = TodayTtsStore.todayKey()

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
            write(context, log)
        }
        return log
    }

    fun todayTopicKeys(context: Context): Set<String> = rolled(context).todayTopics.toSet()

    fun markTopic(
        context: Context,
        subject: String,
        topicTitle: String,
        kind: String = KIND_STUDY
    ) {
        val log = rolled(context)
        log.lastAt = System.currentTimeMillis()
        val key = "${canonicalizeSubject(subject)}|$topicTitle"
        if (key !in log.todayTopics) log.todayTopics.add(key)
        when (kind) {
            KIND_QUIZ, KIND_RECALL -> log.todayQuiz = true
            KIND_EXAM -> log.todayExam = true
            else -> log.todayStudy = true
        }
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
        val total: Int,
        val starSum: Int,
        val stable: Int
    ) {
        val masteredPct: Int
            get() = if (total == 0) 0 else (master * 100 / total)
        val fillPct: Int
            get() = if (total == 0) 0 else (starSum * 100 / (total * 5))
        val stablePct: Int
            get() = if (total == 0) 0 else (stable * 100 / total)
    }

    data class MasterySnap(
        val cards: Int,
        val starSum: Int,
        val stable: Int
    ) {
        val fillPct: Int
            get() = if (cards == 0) 0 else (starSum * 100 / (cards * 5))
        val stablePct: Int
            get() = if (cards == 0) 0 else (stable * 100 / cards)
    }

    private fun conceptCards(context: Context) =
        CardStore.getAllCards(context).filter { it.type == "concept" && it.topicTitle.isNotBlank() }

    private fun starPoints(context: Context, card: Card): Int {
        if (RecallStore.recallCount(context, card.id) <= 0) return 0
        return CardStore.getMemoryLevel(context, card.subject, card.topicTitle).coerceIn(1, 5)
    }

    private fun isStable(context: Context, card: Card): Boolean {
        if (RecallStore.recallCount(context, card.id) <= 0) return false
        return CardStore.getMemoryLevel(context, card.subject, card.topicTitle) >= 4
    }

    fun mastery(context: Context): MasterySnap {
        val list = conceptCards(context)
        return MasterySnap(
            cards = list.size,
            starSum = list.sumOf { starPoints(context, it) },
            stable = list.count { isStable(context, it) }
        )
    }

    fun masteryLine(context: Context): String {
        val showA = AppPrefs.showAchievePct(context)
        val showS = AppPrefs.showStablePct(context)
        if (!showA && !showS) return ""
        val snap = mastery(context)
        val parts = mutableListOf<String>()
        if (showA) parts += "달성도 ${snap.fillPct}%"
        if (showS) parts += "안정권 ${snap.stablePct}%"
        return parts.joinToString("  ·  ")
    }

    fun subjectMasterySuffix(context: Context, bar: SubjectBar): String {
        val showA = AppPrefs.showAchievePct(context)
        val showS = AppPrefs.showStablePct(context)
        if (!showA && !showS) return ""
        val parts = mutableListOf<String>()
        if (showA) parts += "달성도 ${bar.fillPct}%"
        if (showS) parts += "안정권 ${bar.stablePct}%"
        return "  (${parts.joinToString(" · ")})"
    }

    fun masteryHint(context: Context): String {
        val showA = AppPrefs.showAchievePct(context)
        val showS = AppPrefs.showStablePct(context)
        val parts = mutableListOf<String>()
        if (showA) parts += "달성도는 암기정도를 5칸까지 채운 비율입니다. 인출 전 장은 0입니다."
        if (showS) parts += "안정권은 암기정도 4칸 이상인 장의 비율입니다."
        return parts.joinToString(" ")
    }

    fun subjectBars(context: Context): List<SubjectBar> {
        val concepts = conceptCards(context)
        return CardStore.getSubjects(context)
            .map { canonicalizeSubject(it) }
            .distinct()
            .map { subject ->
                val list = concepts.filter { canonicalizeSubject(it.subject) == subject }
                var unseen = 0
                var weak = 0
                var mid = 0
                var master = 0
                var starSum = 0
                var stable = 0
                list.forEach {
                    val n = RecallStore.recallCount(context, it.id)
                    val mem = CardStore.getMemoryLevel(context, it.subject, it.topicTitle)
                    when {
                        n <= 0 -> unseen++
                        mem <= 2 -> weak++
                        mem == 3 -> mid++
                        else -> master++
                    }
                    starSum += starPoints(context, it)
                    if (isStable(context, it)) stable++
                }
                SubjectBar(subject, unseen, weak, mid, master, list.size, starSum, stable)
            }
    }

    fun recallTarget(recallCount: Int, memory: Int): Int =
        if (recallCount > 0 && memory <= 2) 10 else 5

    fun weakCount(context: Context, subjects: List<String>? = null): Int {
        val want = subjects?.map { canonicalizeSubject(it) }?.toSet()
        val concepts = CardStore.getAllCards(context).filter { it.type == "concept" }
        return concepts.count { card ->
            val sub = canonicalizeSubject(card.subject)
            val n = RecallStore.recallCount(context, card.id)
            val mem = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
            (want == null || sub in want) && n > 0 && mem <= 2
        }
    }

    fun recallCoverage(context: Context): RecallCoverage {
        val concepts = CardStore.getAllCards(context).filter { it.type == "concept" && it.topicTitle.isNotBlank() }
        var remaining = 0
        var done = 0
        var weak = 0
        concepts.forEach { card ->
            val n = RecallStore.recallCount(context, card.id)
            val mem = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
            val target = recallTarget(n, mem)
            if (target == 10) weak++
            val rem = (target - n).coerceAtLeast(0)
            remaining += rem
            if (rem == 0) done++
        }
        val millis = AppPrefs.getExam2DateMillis(context)
        val upcoming = DdayCalculator.isUpcoming(millis)
        val days = if (upcoming) DdayCalculator.daysUntil(millis).coerceAtLeast(1) else 0
        val perDay = if (upcoming && remaining > 0) {
            ceil(remaining.toDouble() / days).toInt().coerceIn(1, 80)
        } else 0
        return RecallCoverage(concepts.size, done, remaining, weak, days, perDay, upcoming)
    }

    fun examPaces(context: Context): List<ExamPace> {
        return listOf(
            paceFor(context, "2차", EXAM1_SUBJECTS + EXAM2_SUBJECTS, AppPrefs.getExam2DateMillis(context))
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

    fun dailyGoal(context: Context): Int = RecallStore.sessionSize(context).coerceAtLeast(0)

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
                val n = RecallStore.recallCount(context, it.id)
                val mem = CardStore.getMemoryLevel(context, it.subject, it.topicTitle)
                WeakTopic(
                    subject = canonicalizeSubject(it.subject),
                    topicTitle = it.topicTitle,
                    cardId = it.id,
                    memory = mem,
                    recallCount = n,
                    target = recallTarget(n, mem)
                )
            }
            .filter { it.recallCount > 0 && it.memory <= 2 }
            .sortedWith(compareBy({ it.memory }, { it.recallCount }, { it.subject }, { naturalSortKey(it.topicTitle) }))
            .distinctBy { "${it.subject}|${it.topicTitle}" }
            .take(limit)
    }

    fun paceLine(context: Context): String {
        val cov = recallCoverage(context)
        val cover = "전 주제 인출 ${cov.done}/${cov.total}  ·  남은 횟수 ${cov.remaining}회" +
            if (cov.weak > 0) "  ·  약점(목표 10회) ${cov.weak}개" else ""
        val dateLine = if (!cov.hasDate) {
            "2차 시험일을 정하면 남은 횟수를 날짜에 나눠 드려요"
        } else {
            "2차까지 ${cov.days}일  ·  하루 권장 약 ${cov.perDay}회"
        }
        val load = StudyLoadStore.todayLine(context)
        return "오늘 공부분량은 $load 입니다.\n" +
            "기본 목표 5회, 인출에서 막히면 그 주제는 10회입니다.\n$cover\n$dateLine"
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
        val examAt = maxOf(
            a.examLogs.maxOfOrNull { it.at } ?: 0L,
            b.examLogs.maxOfOrNull { it.at } ?: 0L
        )
        val out = StudyLog(
            lastAt = maxOf(a.lastAt, b.lastAt, examAt),
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
