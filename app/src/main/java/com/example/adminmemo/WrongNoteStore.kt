package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Calendar

data class WrongEntry(
    val id: String,
    val kind: String,
    val targetId: String,
    val subject: String,
    val title: String,
    val typeLabel: String,
    val wrongCount: Int = 1,
    val correctStreak: Int = 0,
    val lastWrongAt: Long = 0L
)

object WrongNoteStore {
    private const val FILE = "wrong_notes.json"
    private const val CLEAR_AFTER = 2
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): MutableList<WrongEntry> {
        migrateLegacy(context)
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        val type = object : TypeToken<MutableList<WrongEntry>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun write(context: Context, list: List<WrongEntry>) {
        file(context).writeText(gson.toJson(list), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
        FirebaseSyncManager.notifyProgressChanged(context)
        val cardIds = list.filter { it.kind == "card" }.map { it.targetId }.toSet()
        File(context.filesDir, "wrong_ids.json").writeText(gson.toJson(cardIds), Charsets.UTF_8)
    }

    private var migrated = false

    private fun migrateLegacy(context: Context) {
        if (migrated) return
        migrated = true
        val f = file(context)
        if (f.exists()) return
        val legacy = File(context.filesDir, "wrong_ids.json")
        if (!legacy.exists()) return
        val type = object : TypeToken<MutableSet<String>>() {}.type
        val ids: Set<String> = try {
            gson.fromJson(legacy.readText(Charsets.UTF_8), type) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        if (ids.isEmpty()) return
        val cards = CardStore.getAllCards(context).associateBy { it.id }
        val now = System.currentTimeMillis()
        val list = ids.mapNotNull { id ->
            val card = cards[id] ?: return@mapNotNull null
            WrongEntry(
                id = "card:$id",
                kind = "card",
                targetId = id,
                subject = canonicalizeSubject(card.subject),
                title = card.topicTitle.ifBlank { card.title },
                typeLabel = if (card.type == "mnemonic") "두문자" else "본문",
                lastWrongAt = now
            )
        }.toMutableList()
        if (list.isNotEmpty()) write(context, list)
    }

    fun all(context: Context): List<WrongEntry> =
        synced(context).sortedByDescending { it.lastWrongAt }

    fun cardIds(context: Context): Set<String> =
        synced(context).filter { it.kind == "card" }.map { it.targetId }.toSet()

    /** 본문·사례를 고치거나 지운 뒤 오답노트 제목/목록을 맞춘다. */
    private fun synced(context: Context): MutableList<WrongEntry> {
        val list = read(context)
        if (list.isEmpty()) return list
        val cards = CardStore.getAllCards(context).associateBy { it.id }
        val cases = MockExamStore.getAll(context).associateBy { it.id }
        var dirty = false
        val out = ArrayList<WrongEntry>(list.size)
        for (e in list) {
            when (e.kind) {
                "card" -> {
                    val card = cards[e.targetId]
                    if (card == null) {
                        dirty = true
                        continue
                    }
                    val title = card.topicTitle.ifBlank { card.title }
                    val subject = canonicalizeSubject(card.subject)
                    val typeLabel = if (card.type == "mnemonic") "두문자" else "본문"
                    if (title != e.title || subject != e.subject || typeLabel != e.typeLabel) {
                        dirty = true
                        out.add(e.copy(title = title, subject = subject, typeLabel = typeLabel))
                    } else {
                        out.add(e)
                    }
                }
                "case" -> {
                    val item = cases[e.targetId]
                    if (item == null) {
                        dirty = true
                        continue
                    }
                    val title = item.title.ifBlank { "사례문제" }
                    val subject = canonicalizeSubject(item.subject)
                    if (title != e.title || subject != e.subject) {
                        dirty = true
                        out.add(e.copy(title = title, subject = subject, typeLabel = "사례"))
                    } else {
                        out.add(e)
                    }
                }
                else -> out.add(e)
            }
        }
        if (dirty) write(context, out)
        return out
    }

    fun today(context: Context): List<WrongEntry> {
        val start = startOfToday()
        return all(context).filter { it.lastWrongAt >= start }
    }

    fun recordWrong(
        context: Context,
        kind: String,
        targetId: String,
        subject: String,
        title: String,
        typeLabel: String
    ) {
        val list = read(context)
        val key = "$kind:$targetId"
        val i = list.indexOfFirst { it.id == key }
        val now = System.currentTimeMillis()
        if (i >= 0) {
            val old = list[i]
            list[i] = old.copy(
                wrongCount = old.wrongCount + 1,
                correctStreak = 0,
                lastWrongAt = now,
                title = title.ifBlank { old.title },
                typeLabel = typeLabel.ifBlank { old.typeLabel }
            )
        } else {
            list.add(
                WrongEntry(
                    id = key,
                    kind = kind,
                    targetId = targetId,
                    subject = canonicalizeSubject(subject),
                    title = title,
                    typeLabel = typeLabel,
                    lastWrongAt = now
                )
            )
        }
        write(context, list)
    }

    fun recordWrongCard(context: Context, cardId: String) {
        val card = CardStore.getAllCards(context).firstOrNull { it.id == cardId } ?: return
        recordWrong(
            context,
            "card",
            cardId,
            card.subject,
            card.topicTitle.ifBlank { card.title },
            if (card.type == "mnemonic") "두문자" else "본문"
        )
    }

    fun recordCorrectCard(context: Context, cardId: String) {
        val list = read(context)
        val i = list.indexOfFirst { it.kind == "card" && it.targetId == cardId }
        if (i < 0) return
        val next = list[i].copy(correctStreak = list[i].correctStreak + 1)
        if (next.correctStreak >= CLEAR_AFTER) list.removeAt(i) else list[i] = next
        write(context, list)
    }

    fun remove(context: Context, id: String) {
        val list = read(context)
        if (list.removeAll { it.id == id }) write(context, list)
    }

    fun subjects(context: Context): List<String> =
        orderedSubjects(all(context).map { it.subject })

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val byId = linkedMapOf<String, WrongEntry>()
        (parseList(cloudJson) + parseList(localJson)).forEach { entry ->
            val old = byId[entry.id]
            byId[entry.id] = if (old == null) entry else mergeEntry(old, entry)
        }
        return gson.toJson(byId.values.toList())
    }

    fun mergeWrongIdsJson(localJson: String, cloudJson: String, notesJson: String): String {
        val type = object : TypeToken<MutableSet<String>>() {}.type
        fun ids(json: String): Set<String> = try {
            if (json.isBlank()) emptySet() else gson.fromJson<MutableSet<String>>(json, type) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        val fromNotes = parseList(notesJson).filter { it.kind == "card" }.map { it.targetId }
        return gson.toJson((ids(localJson) + ids(cloudJson) + fromNotes).toSet())
    }

    private fun parseList(json: String): List<WrongEntry> {
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<MutableList<WrongEntry>>() {}.type
        return try {
            gson.fromJson<MutableList<WrongEntry>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergeEntry(a: WrongEntry, b: WrongEntry): WrongEntry {
        return a.copy(
            wrongCount = maxOf(a.wrongCount, b.wrongCount),
            correctStreak = minOf(a.correctStreak, b.correctStreak),
            lastWrongAt = maxOf(a.lastWrongAt, b.lastWrongAt),
            title = if (a.title.length >= b.title.length) a.title else b.title,
            typeLabel = a.typeLabel.ifBlank { b.typeLabel },
            subject = a.subject.ifBlank { b.subject }
        )
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
