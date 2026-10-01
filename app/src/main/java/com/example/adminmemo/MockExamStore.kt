package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * 사례문제 저장.
 * - 기본: assets/mock_exams.json
 * - 사용자 추가: filesDir/user_cases.json
 * - 수정본: filesDir/edited_cases.json
 * - 삭제 id: filesDir/deleted_case_ids.json
 */
object MockExamStore {

    private val gson = Gson()
    private var baseCache: List<MockExamItem>? = null

    fun invalidate() {
        baseCache = null
    }

    private fun userFile(context: Context) = File(context.filesDir, "user_cases.json")
    private fun editedFile(context: Context) = File(context.filesDir, "edited_cases.json")
    private fun deletedFile(context: Context) = File(context.filesDir, "deleted_case_ids.json")

    private fun loadBase(context: Context): List<MockExamItem> {
        baseCache?.let { return it }
        return try {
            val json = context.assets.open("mock_exams.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val type = object : TypeToken<List<MockExamItem>>() {}.type
            val list: List<MockExamItem> = gson.fromJson(json, type) ?: emptyList()
            baseCache = list
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readUser(context: Context): MutableList<MockExamItem> {
        val f = userFile(context)
        if (!f.exists()) return mutableListOf()
        val type = object : TypeToken<MutableList<MockExamItem>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeUser(context: Context, list: List<MockExamItem>) {
        userFile(context).writeText(gson.toJson(list), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
    }

    private fun readEdited(context: Context): MutableMap<String, MockExamItem> {
        val f = editedFile(context)
        if (!f.exists()) return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, MockExamItem>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun writeEdited(context: Context, map: Map<String, MockExamItem>) {
        editedFile(context).writeText(gson.toJson(map), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
    }

    private fun readDeleted(context: Context): MutableSet<String> {
        val f = deletedFile(context)
        if (!f.exists()) return mutableSetOf()
        val type = object : TypeToken<MutableSet<String>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun writeDeleted(context: Context, ids: Set<String>) {
        deletedFile(context).writeText(gson.toJson(ids), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
    }

    fun getAll(context: Context): List<MockExamItem> {
        val deleted = readDeleted(context)
        val edited = readEdited(context)
        return (loadBase(context) + readUser(context))
            .filter { it.id !in deleted }
            .map { edited[it.id] ?: it }
            .map { item ->
                val subject = canonicalizeSubject(item.subject)
                if (item.subject == subject) item else item.copy(subject = subject)
            }
    }

    fun add(context: Context, item: MockExamItem): MockExamItem {
        val saved = item.copy(
            id = if (item.id.isBlank()) "user_case_" + UUID.randomUUID().toString().take(8) else item.id,
            subject = canonicalizeSubject(item.subject)
        )
        val list = readUser(context)
        list.add(saved)
        writeUser(context, list)
        return saved
    }

    fun update(context: Context, item: MockExamItem) {
        val saved = item.copy(subject = canonicalizeSubject(item.subject))
        val list = readUser(context)
        val idx = list.indexOfFirst { it.id == saved.id }
        if (idx >= 0) {
            list[idx] = saved
            writeUser(context, list)
            return
        }
        val map = readEdited(context)
        map[saved.id] = saved
        writeEdited(context, map)
    }

    fun delete(context: Context, id: String) {
        val user = readUser(context)
        val removed = user.removeAll { it.id == id }
        if (removed) {
            writeUser(context, user)
            return
        }
        val deleted = readDeleted(context)
        deleted.add(id)
        writeDeleted(context, deleted)
        val edited = readEdited(context)
        if (edited.remove(id) != null) writeEdited(context, edited)
    }

    fun mergeUserJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableList<MockExamItem>>() {}.type
        fun list(json: String): List<MockExamItem> = try {
            if (json.isBlank()) emptyList() else gson.fromJson<MutableList<MockExamItem>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val byId = linkedMapOf<String, MockExamItem>()
        (list(cloudJson) + list(localJson)).forEach { item ->
            val old = byId[item.id]
            byId[item.id] = if (old == null) item else richer(old, item)
        }
        return gson.toJson(byId.values.toList())
    }

    fun mergeEditedJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableMap<String, MockExamItem>>() {}.type
        fun map(json: String): Map<String, MockExamItem> = try {
            if (json.isBlank()) emptyMap() else gson.fromJson<MutableMap<String, MockExamItem>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val out = map(cloudJson).toMutableMap()
        map(localJson).forEach { (id, item) ->
            val old = out[id]
            out[id] = if (old == null) item else richer(old, item)
        }
        return gson.toJson(out)
    }

    fun mergeDeletedJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableSet<String>>() {}.type
        fun ids(json: String): Set<String> = try {
            if (json.isBlank()) emptySet() else gson.fromJson<MutableSet<String>>(json, type) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        return gson.toJson(ids(localJson) + ids(cloudJson))
    }

    private fun richer(a: MockExamItem, b: MockExamItem): MockExamItem {
        val aLen = a.question.length + a.explanation.length
        val bLen = b.question.length + b.explanation.length
        return if (bLen > aLen) b else a
    }
}
