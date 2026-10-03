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
    val failKindByCard: Map<String, Int> = emptyMap()
)

object RecallSessionStore {
    const val CHECKPOINT = 3
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
}
