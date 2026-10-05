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
    val date: String = ""
)

object RecallSessionStore {
    const val CHECKPOINT = 1
    private const val FILE = "recall_session.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): RecallSessionSnap? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), RecallSessionSnap::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, snap: RecallSessionSnap) {
        file(context).writeText(gson.toJson(snap), Charsets.UTF_8)
    }

    fun clear(context: Context) {
        val f = file(context)
        if (f.exists()) f.delete()
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

    fun unfinished(snap: RecallSessionSnap?): Boolean =
        snap != null && snap.queueIds.isNotEmpty() && snap.index > 0

    fun isToday(snap: RecallSessionSnap): Boolean =
        snap.date == TodayTtsStore.todayKey()

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
}
