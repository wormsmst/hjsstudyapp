package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File

data class ContentStudySnap(
    val queueIds: List<String> = emptyList(),
    val index: Int = 0,
    val date: String = "",
    val finished: Boolean = false,
    val updatedAt: Long = 0L
)

object ContentStudySessionStore {
    const val FILE = "content_study_session.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun parse(json: String): ContentStudySnap? {
        if (json.isBlank()) return null
        return try {
            gson.fromJson(json, ContentStudySnap::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun load(context: Context): ContentStudySnap? {
        val f = file(context)
        if (!f.exists()) return null
        return parse(f.readText(Charsets.UTF_8))
    }

    fun save(context: Context, snap: ContentStudySnap) {
        val out = snap.copy(
            date = snap.date.ifBlank { TodayTtsStore.todayKey() },
            updatedAt = System.currentTimeMillis()
        )
        file(context).writeText(gson.toJson(out), Charsets.UTF_8)
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun unfinished(snap: ContentStudySnap?): Boolean {
        if (snap == null || snap.finished) return false
        if (snap.queueIds.isEmpty()) return false
        return snap.index in snap.queueIds.indices
    }

    fun isToday(snap: ContentStudySnap): Boolean =
        snap.date == TodayTtsStore.todayKey()

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val today = TodayTtsStore.todayKey()
        fun todayOf(json: String): ContentStudySnap? {
            val snap = parse(json) ?: return null
            return if (snap.date == today) snap else null
        }
        val a = todayOf(localJson)
        val b = todayOf(cloudJson)
        val pick = when {
            a == null -> b
            b == null -> a
            a.finished && !b.finished && a.index >= b.index -> a
            b.finished && !a.finished && b.index >= a.index -> b
            a.index != b.index -> if (a.index >= b.index) a else b
            a.updatedAt != b.updatedAt -> if (a.updatedAt >= b.updatedAt) a else b
            else -> a
        }
        return if (pick == null) "" else gson.toJson(pick)
    }

    fun isHollowJson(json: String): Boolean {
        if (json.isBlank()) return true
        val snap = parse(json) ?: return true
        return !snap.finished && snap.queueIds.isEmpty()
    }
}
