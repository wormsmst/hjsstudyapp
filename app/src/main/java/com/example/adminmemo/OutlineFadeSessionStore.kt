package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File

data class OutlineFadeSnap(
    val subject: String = "",
    val queueIds: List<String> = emptyList(),
    val index: Int = 0,
    val throughLevel: Int = -1,
    val writeMode: Boolean = false,
    val date: String = "",
    val finished: Boolean = false,
    val updatedAt: Long = 0L
)

object OutlineFadeSessionStore {
    const val FILE = "outline_fade_session.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun parse(json: String): OutlineFadeSnap? {
        if (json.isBlank()) return null
        return try {
            gson.fromJson(json, OutlineFadeSnap::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun load(context: Context): OutlineFadeSnap? {
        val f = file(context)
        if (!f.exists()) return null
        return parse(f.readText(Charsets.UTF_8))
    }

    fun save(context: Context, snap: OutlineFadeSnap) {
        val out = snap.copy(
            date = snap.date.ifBlank { TodayTtsStore.todayKey() },
            updatedAt = System.currentTimeMillis()
        )
        file(context).writeText(gson.toJson(out), Charsets.UTF_8)
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun markFinished(context: Context) {
        val cur = load(context)
        save(
            context,
            (cur ?: OutlineFadeSnap(date = TodayTtsStore.todayKey())).copy(finished = true)
        )
    }

    fun unfinished(snap: OutlineFadeSnap?): Boolean {
        if (snap == null || snap.finished) return false
        if (snap.queueIds.isEmpty()) return false
        return snap.index < snap.queueIds.size
    }

    fun isToday(snap: OutlineFadeSnap): Boolean =
        snap.date == TodayTtsStore.todayKey()

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val today = TodayTtsStore.todayKey()
        fun todayOf(json: String): OutlineFadeSnap? {
            val snap = parse(json) ?: return null
            return if (snap.date == today) snap else null
        }
        val a = todayOf(localJson)
        val b = todayOf(cloudJson)
        val pick = when {
            a == null -> b
            b == null -> a
            a.updatedAt != b.updatedAt -> if (a.updatedAt >= b.updatedAt) a else b
            a.finished != b.finished -> if (a.finished) a else b
            a.index != b.index -> if (a.index >= b.index) a else b
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
