package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File

private data class ReadPassItem(
    var count: Int = 0,
    var lastDay: String = ""
)

private data class ReadPassBook(
    var items: MutableMap<String, ReadPassItem> = mutableMapOf(),
    var updatedAt: Long = 0L
)

/** 본문학습에서 장을 한 바퀴 본 횟수. 공부일당 장마다 최대 1회. */
object ReadPassStore {
    private const val FILE = "read_pass.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun parse(json: String): ReadPassBook {
        if (json.isBlank()) return ReadPassBook()
        return try {
            val book = gson.fromJson(json, ReadPassBook::class.java) ?: ReadPassBook()
            book.items = book.items ?: mutableMapOf()
            book
        } catch (_: Exception) {
            ReadPassBook()
        }
    }

    private fun read(context: Context): ReadPassBook {
        val f = file(context)
        if (!f.exists()) return ReadPassBook()
        return parse(f.readText(Charsets.UTF_8))
    }

    private fun write(context: Context, book: ReadPassBook) {
        book.updatedAt = System.currentTimeMillis()
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun count(context: Context, cardId: String): Int =
        read(context).items[cardId]?.count?.coerceAtLeast(0) ?: 0

    fun countedToday(context: Context, cardId: String): Boolean {
        val item = read(context).items[cardId] ?: return false
        return item.lastDay == TodayTtsStore.todayKey() && item.count > 0
    }

    fun label(context: Context, cardId: String): String = "${count(context, cardId)}회"

    fun tryMark(context: Context, cardId: String): Boolean {
        if (cardId.isBlank()) return false
        val day = TodayTtsStore.todayKey()
        val book = read(context)
        val item = book.items.getOrPut(cardId) { ReadPassItem() }
        if (item.lastDay == day) return false
        item.count = (item.count + 1).coerceAtLeast(1)
        item.lastDay = day
        write(context, book)
        return true
    }

    fun undoToday(context: Context, cardId: String): Boolean {
        if (cardId.isBlank()) return false
        val day = TodayTtsStore.todayKey()
        val book = read(context)
        val item = book.items[cardId] ?: return false
        if (item.lastDay != day || item.count <= 0) return false
        item.count -= 1
        item.lastDay = ""
        if (item.count <= 0) book.items.remove(cardId)
        write(context, book)
        return true
    }

    fun averageLabel(context: Context, cards: List<Card>): String {
        if (cards.isEmpty()) return "평균 0회"
        val book = read(context)
        val sum = cards.sumOf { book.items[it.id]?.count?.coerceAtLeast(0) ?: 0 }
        val avg = sum.toDouble() / cards.size
        return if (avg == 0.0 || avg == avg.toInt().toDouble()) {
            "평균 ${avg.toInt()}회"
        } else {
            "평균 ${"%.1f".format(avg)}회"
        }
    }

    fun isHollowJson(json: String): Boolean {
        if (json.isBlank()) return true
        val book = parse(json)
        return book.items.values.none { it.count > 0 }
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parse(localJson)
        val b = parse(cloudJson)
        if (isHollowJson(localJson) && !isHollowJson(cloudJson)) return gson.toJson(b)
        if (isHollowJson(cloudJson) && !isHollowJson(localJson)) return gson.toJson(a)
        val ids = (a.items.keys + b.items.keys).toSet()
        val out = ReadPassBook(updatedAt = maxOf(a.updatedAt, b.updatedAt))
        for (id in ids) {
            val la = a.items[id]
            val lb = b.items[id]
            if (la == null) {
                out.items[id] = lb ?: continue
                continue
            }
            if (lb == null) {
                out.items[id] = la
                continue
            }
            val count = maxOf(la.count, lb.count)
            val lastDay = when {
                la.lastDay == lb.lastDay -> la.lastDay
                la.lastDay.isBlank() -> lb.lastDay
                lb.lastDay.isBlank() -> la.lastDay
                dayRank(la.lastDay) >= dayRank(lb.lastDay) -> la.lastDay
                else -> lb.lastDay
            }
            out.items[id] = ReadPassItem(count, lastDay)
        }
        return gson.toJson(out)
    }

    private fun dayRank(key: String): Int {
        val p = key.split("-")
        if (p.size < 3) return 0
        val y = p[0].toIntOrNull() ?: 0
        val m = p[1].toIntOrNull() ?: 0
        val d = p[2].toIntOrNull() ?: 0
        return y * 400 + m * 32 + d
    }
}
