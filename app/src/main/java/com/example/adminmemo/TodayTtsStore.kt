package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Calendar
import kotlin.random.Random

data class TodayTtsPick(
    val date: String = "",
    val ids: List<String> = emptyList(),
    val reason: String = ""
)

private data class TodayTtsArchive(
    var days: MutableMap<String, TodayTtsPick> = mutableMapOf()
)

object TodayTtsStore {
    private const val FILE = "today_tts.json"
    private const val KEEP_DAYS = 90
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun todayKey(): String {
        val c = Calendar.getInstance()
        return keyOf(c)
    }

    fun keyOf(c: Calendar): String =
        "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)}"

    fun displayDate(key: String, today: String = todayKey()): String {
        val p = key.split("-")
        if (p.size < 3) return key
        val y = p[0]
        val m = p[1]
        val d = p[2]
        return if (key == today) "${m}월 ${d}일 (오늘)" else "${y}년 ${m}월 ${d}일"
    }

    fun setTodayFromIds(context: Context, ids: List<String>, reason: String) {
        setIdsForDate(context, todayKey(), ids, reason)
    }

    /** 저녁 인출에서 막힌 주제를 다음 날 출근길 TTS로 미리 넣는다. */
    fun setNextMorningFromIds(context: Context, ids: List<String>, reason: String) {
        setIdsForDate(context, nextKey(), ids, reason)
    }

    private fun nextKey(): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, 1)
        return keyOf(c)
    }

    private fun setIdsForDate(context: Context, date: String, ids: List<String>, reason: String) {
        val n = AppPrefs.getTodayTtsCount(context).coerceAtLeast(1)
        val filled = fillToCount(context, ids.filter { it.isNotBlank() }.distinct(), n)
        saveDay(context, TodayTtsPick(date, filled, reason))
    }

    fun current(context: Context, forceNew: Boolean = false): TodayTtsPick {
        val n = AppPrefs.getTodayTtsCount(context)
        val key = todayKey()
        val archive = readArchive(context)
        val saved = archive.days[key]
        val cards = CardStore.getAllCards(context).associateBy { it.id }
        val valid = saved?.ids?.filter { it in cards }.orEmpty()
        val pick = when {
            !forceNew && saved != null && valid.size == n -> saved.copy(ids = valid)
            !forceNew && saved != null && valid.isNotEmpty() -> {
                val filled = fillToCount(context, valid, n)
                TodayTtsPick(key, filled, saved.reason.ifBlank { reasonLine(context, filled) })
            }
            else -> {
                val ids = pickIds(context, n)
                TodayTtsPick(key, ids, reasonLine(context, ids))
            }
        }
        saveDay(context, pick)
        return pick
    }

    fun get(context: Context, date: String): TodayTtsPick? {
        if (date == todayKey()) return current(context)
        val cards = CardStore.getAllCards(context).associateBy { it.id }
        val saved = readArchive(context).days[date] ?: return null
        val valid = saved.ids.filter { it in cards }
        return if (valid.isEmpty()) null else saved.copy(ids = valid)
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parseArchive(localJson)
        val b = parseArchive(cloudJson)
        val days = mutableMapOf<String, TodayTtsPick>()
        (a.days.keys + b.days.keys).forEach { key ->
            val pa = a.days[key]
            val pb = b.days[key]
            days[key] = when {
                pa == null -> pb!!
                pb == null -> pa
                pa.ids.size >= pb.ids.size -> pa
                else -> pb
            }
        }
        return gson.toJson(prune(TodayTtsArchive(days)))
    }

    private fun parseArchive(json: String): TodayTtsArchive {
        if (json.isBlank()) return TodayTtsArchive()
        return try {
            val typed = object : TypeToken<TodayTtsArchive>() {}.type
            gson.fromJson<TodayTtsArchive>(json, typed) ?: TodayTtsArchive()
        } catch (_: Exception) {
            TodayTtsArchive()
        }
    }

    fun dates(context: Context): List<String> {
        current(context)
        val today = todayKey()
        val keys = readArchive(context).days.keys.toMutableSet()
        keys.add(today)
        return keys.sortedWith(compareByDescending { parseKey(it) })
    }

    private fun parseKey(key: String): Long {
        val p = key.split("-")
        if (p.size < 3) return 0L
        val y = p[0].toIntOrNull() ?: return 0L
        val m = p[1].toIntOrNull() ?: return 0L
        val d = p[2].toIntOrNull() ?: return 0L
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d)
        return c.timeInMillis
    }

    private fun readArchive(context: Context): TodayTtsArchive {
        val f = file(context)
        if (!f.exists()) return TodayTtsArchive()
        val text = f.readText(Charsets.UTF_8)
        try {
            val typed = object : TypeToken<TodayTtsArchive>() {}.type
            val archive = gson.fromJson<TodayTtsArchive>(text, typed)
            if (archive != null) {
                if (archive.days == null) archive.days = mutableMapOf()
                if (archive.days.isNotEmpty()) return prune(archive)
            }
        } catch (_: Exception) {
        }
        try {
            val old = gson.fromJson(text, TodayTtsPick::class.java)
            if (old != null && old.date.isNotBlank()) {
                return TodayTtsArchive(mutableMapOf(old.date to old))
            }
        } catch (_: Exception) {
        }
        return TodayTtsArchive()
    }

    private fun saveDay(context: Context, pick: TodayTtsPick) {
        val archive = readArchive(context)
        archive.days[pick.date] = pick
        val pruned = prune(archive)
        file(context).writeText(gson.toJson(pruned), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    private fun prune(archive: TodayTtsArchive): TodayTtsArchive {
        val keepFrom = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -KEEP_DAYS) }.timeInMillis
        val kept = archive.days.filter { parseKey(it.key) >= keepFrom }.toMutableMap()
        return TodayTtsArchive(kept)
    }

    private fun fillToCount(context: Context, have: List<String>, n: Int): List<String> {
        if (have.size >= n) return have.take(n)
        val extra = pickIds(context, n, exclude = have.toSet())
        return (have + extra).distinct().take(n)
    }

    private fun pickIds(context: Context, n: Int, exclude: Set<String> = emptySet()): List<String> {
        val prefer = (RecallStore.dueCardIds(context) + DailyQuestStore.listenIds(context) + DailyQuestStore.writeCardIds(context))
            .distinct()
            .filter { it !in exclude }
        if (prefer.size >= n) return prefer.take(n)
        val rest = pickRanked(context, n - prefer.size, exclude + prefer.toSet())
        return (prefer + rest).distinct().take(n)
    }

    private fun pickRanked(context: Context, n: Int, exclude: Set<String>): List<String> {
        val concepts = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.id !in exclude }
            .distinctBy { "${canonicalizeSubject(it.subject)}|${it.topicTitle}" }
        if (concepts.isEmpty()) return emptyList()
        val wrongs = WrongNoteStore.all(context).filter { it.kind == "card" }.associateBy { it.targetId }
        val todayKeys = StudyProgressStore.todayTopicKeys(context)
        val ranked = concepts
            .map { it to score(context, it, wrongs, todayKeys) }
            .sortedByDescending { it.second }
            .map { it.first }
        val picked = mutableListOf<Card>()
        val usedSubject = mutableSetOf<String>()
        for (card in ranked) {
            if (picked.size >= n) break
            val sub = canonicalizeSubject(card.subject)
            if (sub in usedSubject) continue
            picked.add(card)
            usedSubject.add(sub)
        }
        for (card in ranked) {
            if (picked.size >= n) break
            if (picked.none { it.id == card.id }) picked.add(card)
        }
        return picked.take(n).map { it.id }
    }

    private fun score(
        context: Context,
        card: Card,
        wrongs: Map<String, WrongEntry>,
        todayKeys: Set<String>
    ): Int {
        var s = Random.nextInt(0, 7)
        val wrong = wrongs[card.id]
        if (wrong != null) s += 30 + wrong.wrongCount * 14
        val mem = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
        s += when (mem) {
            1 -> 28
            2 -> 18
            3 -> 6
            else -> 0
        }
        val key = "${canonicalizeSubject(card.subject)}|${card.topicTitle}"
        if (key !in todayKeys) s += 10
        return s
    }

    fun reasonLine(context: Context, ids: List<String>): String {
        val cards = CardStore.getAllCards(context).associateBy { it.id }
        val wrongs = WrongNoteStore.all(context).filter { it.kind == "card" }.map { it.targetId }.toSet()
        var wrong = 0
        var weak = 0
        ids.forEach { id ->
            val card = cards[id] ?: return@forEach
            if (id in wrongs) wrong++
            if (CardStore.getMemoryLevel(context, card.subject, card.topicTitle) <= 2) weak++
        }
        return when {
            wrong + weak == 0 -> "아직 오답·약점 기록이 적어서, 과목을 섞어 골고루 뽑았어요"
            else -> "오답 ${wrong}장 · 약점 ${weak}장 위주로 뽑았어요. 기록이 쌓이면 더 맞춰져요"
        }
    }
}
