package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File

data class RecallSessionSnap(
    val subject: String = "",
    val period: Int = 0,
    val caseMode: Boolean = false,
    val unseen: Boolean = false,
    val bodyWrite: Boolean = false,
    val retrain: Boolean = false,
    val forcedIds: List<String> = emptyList(),
    val queueIds: List<String> = emptyList(),
    val index: Int = 0,
    val resultIds: List<String> = emptyList(),
    val resultGrades: List<Int> = emptyList(),
    val retried: List<String> = emptyList(),
    val pendingRetry: List<String> = emptyList(),
    val selfMissByCard: Map<String, Int> = emptyMap(),
    val failKindByCard: Map<String, Int> = emptyMap(),
    val baseCount: Int = 0,
    val date: String = "",
    val finished: Boolean = false,
    val updatedAt: Long = 0L
)

object RecallSessionStore {
    const val CHECKPOINT = 1
    const val FILE = "recall_session.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun parse(json: String): RecallSessionSnap? {
        if (json.isBlank()) return null
        return try {
            gson.fromJson(json, RecallSessionSnap::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun load(context: Context): RecallSessionSnap? {
        val f = file(context)
        if (!f.exists()) return null
        return parse(f.readText(Charsets.UTF_8))
    }

    fun save(context: Context, snap: RecallSessionSnap) {
        val out = snap.copy(
            date = snap.date.ifBlank { TodayTtsStore.todayKey() },
            updatedAt = System.currentTimeMillis()
        )
        file(context).writeText(gson.toJson(out), Charsets.UTF_8)
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun clear(context: Context) {
        save(
            context,
            RecallSessionSnap(
                date = TodayTtsStore.todayKey(),
                finished = true
            )
        )
    }

    fun markFinished(context: Context) {
        val cur = load(context)
        save(
            context,
            (cur ?: RecallSessionSnap(date = TodayTtsStore.todayKey())).copy(finished = true)
        )
    }

    fun matches(
        snap: RecallSessionSnap,
        subject: String,
        period: Int,
        caseMode: Boolean,
        unseen: Boolean,
        bodyWrite: Boolean,
        retrain: Boolean,
        forcedIds: List<String>
    ): Boolean =
        snap.subject == subject &&
            snap.period == period &&
            snap.caseMode == caseMode &&
            snap.unseen == unseen &&
            snap.bodyWrite == bodyWrite &&
            snap.retrain == retrain &&
            snap.forcedIds == forcedIds

    fun unfinished(snap: RecallSessionSnap?): Boolean {
        if (snap == null || snap.finished) return false
        if (snap.queueIds.isEmpty()) return false
        if (snap.pendingRetry.isNotEmpty()) return true
        return snap.index < snap.queueIds.size
    }

    fun isToday(snap: RecallSessionSnap): Boolean =
        snap.date == TodayTtsStore.todayKey()

    fun openToday(context: Context): Boolean {
        val snap = load(context) ?: return false
        return unfinished(snap) && isToday(snap)
    }

    fun completedToday(context: Context): Boolean {
        val snap = load(context) ?: return false
        if (!isToday(snap) || snap.queueIds.isEmpty()) return false
        if (snap.finished) return true
        return snap.pendingRetry.isEmpty() && snap.index >= snap.queueIds.size
    }

    fun isOpenJson(json: String): Boolean {
        val snap = parse(json) ?: return false
        return unfinished(snap) && isToday(snap)
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val today = TodayTtsStore.todayKey()
        fun todayOf(json: String): RecallSessionSnap? {
            val snap = parse(json) ?: return null
            return if (snap.date == today) snap else null
        }
        val pick = mergeSnaps(todayOf(localJson), todayOf(cloudJson))
        return if (pick == null) "" else gson.toJson(pick)
    }

    fun packHit(context: Context): Map<String, Any>? {
        val snap = load(context) ?: return null
        if (!isToday(snap)) return null
        if (snap.queueIds.isEmpty() && snap.resultIds.isEmpty()) return null
        return hashMapOf(
            "subject" to snap.subject,
            "period" to snap.period,
            "caseMode" to snap.caseMode,
            "unseen" to snap.unseen,
            "bodyWrite" to snap.bodyWrite,
            "retrain" to snap.retrain,
            "forcedIds" to snap.forcedIds,
            "queueIds" to snap.queueIds,
            "index" to snap.index,
            "resultIds" to snap.resultIds,
            "resultGrades" to snap.resultGrades,
            "retried" to snap.retried,
            "pendingRetry" to snap.pendingRetry,
            "baseCount" to snap.baseCount,
            "date" to snap.date,
            "finished" to snap.finished,
            "updatedAt" to snap.updatedAt
        )
    }

    fun overlayHit(context: Context, raw: Any?) {
        val incoming = fromHit(raw)?.takeIf { isToday(it) } ?: return
        val local = load(context)?.takeIf { isToday(it) }
        val merged = mergeSnaps(local, incoming) ?: return
        file(context).writeText(gson.toJson(merged), Charsets.UTF_8)
    }

    fun mergeHit(a: Any?, b: Any?): Map<String, Any>? {
        val merged = mergeSnaps(fromHit(a), fromHit(b)) ?: return null
        return packHitMap(merged)
    }

    private fun sameScope(a: RecallSessionSnap, b: RecallSessionSnap) =
        a.subject == b.subject &&
            a.period == b.period &&
            a.caseMode == b.caseMode &&
            a.unseen == b.unseen &&
            a.bodyWrite == b.bodyWrite &&
            a.retrain == b.retrain &&
            a.forcedIds == b.forcedIds

    private fun progressOf(s: RecallSessionSnap) = s.resultIds.size * 10 + s.index

    private fun mergeSnaps(a: RecallSessionSnap?, b: RecallSessionSnap?): RecallSessionSnap? {
        if (a == null) return b
        if (b == null) return a
        if (!sameScope(a, b)) {
            val pa = progressOf(a)
            val pb = progressOf(b)
            return when {
                pa > pb -> a
                pb > pa -> b
                a.updatedAt >= b.updatedAt -> a
                else -> b
            }
        }
        val grades = linkedMapOf<String, Int>()
        fun putGrades(s: RecallSessionSnap) {
            s.resultIds.forEachIndexed { i, id ->
                if (id.isBlank()) return@forEachIndexed
                val g = s.resultGrades.getOrNull(i) ?: return@forEachIndexed
                grades[id] = g
            }
        }
        if (a.updatedAt >= b.updatedAt) {
            putGrades(b)
            putGrades(a)
        } else {
            putGrades(a)
            putGrades(b)
        }
        val queue = when {
            a.queueIds == b.queueIds -> a.queueIds
            a.index > b.index -> a.queueIds
            b.index > a.index -> b.queueIds
            a.queueIds.size >= b.queueIds.size -> a.queueIds
            else -> b.queueIds
        }
        val index = maxOf(a.index, b.index).coerceIn(0, queue.size.coerceAtLeast(0))
        val pending = (a.pendingRetry + b.pendingRetry).distinct().filter { it.isNotBlank() }
        val retried = (a.retried + b.retried).distinct().filter { it.isNotBlank() }
        val leader = if (progressOf(a) >= progressOf(b)) a else b
        val done = (leader.finished || index >= queue.size) && pending.isEmpty()
        val miss = (a.selfMissByCard.keys + b.selfMissByCard.keys).associateWith { id ->
            maxOf(a.selfMissByCard[id] ?: 0, b.selfMissByCard[id] ?: 0)
        }
        val fail = (a.failKindByCard.keys + b.failKindByCard.keys).associateWith { id ->
            val fa = a.failKindByCard[id] ?: 0
            val fb = b.failKindByCard[id] ?: 0
            if (a.updatedAt >= b.updatedAt && fa != 0) fa else if (fb != 0) fb else fa
        }
        return RecallSessionSnap(
            subject = leader.subject,
            period = leader.period,
            caseMode = leader.caseMode,
            unseen = leader.unseen,
            bodyWrite = leader.bodyWrite,
            retrain = leader.retrain,
            forcedIds = leader.forcedIds,
            queueIds = queue,
            index = index,
            resultIds = grades.keys.toList(),
            resultGrades = grades.values.toList(),
            retried = retried,
            pendingRetry = pending.filter { it !in retried },
            selfMissByCard = miss,
            failKindByCard = fail,
            baseCount = maxOf(a.baseCount, b.baseCount, queue.size),
            date = TodayTtsStore.todayKey(),
            finished = done,
            updatedAt = maxOf(a.updatedAt, b.updatedAt)
        )
    }

    private fun fromHit(raw: Any?): RecallSessionSnap? {
        val m = raw as? Map<*, *> ?: return null
        fun str(key: String) = m[key]?.toString().orEmpty()
        fun num(key: String) = (m[key] as? Number)?.toLong() ?: 0L
        fun int(key: String) = (m[key] as? Number)?.toInt() ?: 0
        fun bool(key: String) = when (val v = m[key]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> false
        }
        fun strs(key: String) =
            (m[key] as? List<*>)?.mapNotNull { it?.toString() }?.filter { it.isNotBlank() } ?: emptyList()
        fun ints(key: String) =
            (m[key] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        val snap = RecallSessionSnap(
            subject = str("subject"),
            period = int("period"),
            caseMode = bool("caseMode"),
            unseen = bool("unseen"),
            bodyWrite = bool("bodyWrite"),
            retrain = bool("retrain"),
            forcedIds = strs("forcedIds"),
            queueIds = strs("queueIds"),
            index = int("index"),
            resultIds = strs("resultIds"),
            resultGrades = ints("resultGrades"),
            retried = strs("retried"),
            pendingRetry = strs("pendingRetry"),
            baseCount = int("baseCount"),
            date = str("date").ifBlank { TodayTtsStore.todayKey() },
            finished = bool("finished"),
            updatedAt = num("updatedAt")
        )
        return if (snap.queueIds.isEmpty() && snap.resultIds.isEmpty()) null else snap
    }

    private fun packHitMap(snap: RecallSessionSnap): Map<String, Any> = hashMapOf(
        "subject" to snap.subject,
        "period" to snap.period,
        "caseMode" to snap.caseMode,
        "unseen" to snap.unseen,
        "bodyWrite" to snap.bodyWrite,
        "retrain" to snap.retrain,
        "forcedIds" to snap.forcedIds,
        "queueIds" to snap.queueIds,
        "index" to snap.index,
        "resultIds" to snap.resultIds,
        "resultGrades" to snap.resultGrades,
        "retried" to snap.retried,
        "pendingRetry" to snap.pendingRetry,
        "baseCount" to snap.baseCount,
        "date" to snap.date,
        "finished" to snap.finished,
        "updatedAt" to snap.updatedAt
    )

    fun isHollowJson(json: String): Boolean {
        if (json.isBlank()) return true
        val snap = parse(json) ?: return true
        return !snap.finished && snap.queueIds.isEmpty()
    }

    fun scopeLabel(snap: RecallSessionSnap): String {
        val who = when {
            snap.caseMode -> "사례"
            snap.unseen -> "이번 달 미학습"
            snap.retrain -> "다시 인출"
            snap.bodyWrite -> "본문 쓰기 인출"
            snap.forcedIds.isNotEmpty() -> "퀘스트"
            snap.period == 1 -> "1차 과목"
            snap.period == 2 -> "2차 과목"
            snap.subject.isNotBlank() && snap.subject != ALL_SUBJECTS_KEY -> snap.subject
            else -> "전체"
        }
        val base = if (snap.baseCount > 0) snap.baseCount else snap.queueIds.size
        return "$who  ·  ${snap.index} / $base"
    }

    fun progressNow(snap: RecallSessionSnap): String {
        val base = if (snap.baseCount > 0) snap.baseCount else snap.queueIds.size
        val at = (snap.index + 1).coerceAtMost(base.coerceAtLeast(1))
        return "$at / $base"
    }

    /** 인출 선택 타일. 퀘스트·다시 인출 등은 null. */
    fun pickTile(snap: RecallSessionSnap): String? {
        if (snap.finished || snap.forcedIds.isNotEmpty() || snap.retrain || snap.bodyWrite) return null
        return when {
            snap.caseMode -> "case"
            snap.unseen -> "unseen"
            snap.period == 1 -> "p1"
            snap.period == 2 -> "p2"
            snap.subject.isNotBlank() && snap.subject != ALL_SUBJECTS_KEY -> "one"
            else -> "all"
        }
    }
}
