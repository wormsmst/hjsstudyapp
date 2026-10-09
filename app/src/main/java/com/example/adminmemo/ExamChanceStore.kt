package com.example.adminmemo

import android.app.AlertDialog
import android.content.Context
import android.widget.TextView
import com.google.gson.Gson
import java.io.File

private data class ExamChanceBook(
    var items: MutableMap<String, String> = mutableMapOf(),
    var updatedAt: Long = 0L
)

/** 출제가능성. assets 등급은 쓰지 않고, 사용자가 단 값만 보관한다. */
object ExamChanceStore {
    const val HIGH = "높음"
    const val MID = "보통"
    const val LOW = "낮음"
    val CHOICES = listOf(HIGH, MID, LOW)

    private const val FILE = "exam_chance.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun parse(json: String): ExamChanceBook {
        if (json.isBlank()) return ExamChanceBook()
        return try {
            val book = gson.fromJson(json, ExamChanceBook::class.java) ?: ExamChanceBook()
            book.items = book.items ?: mutableMapOf()
            book
        } catch (_: Exception) {
            ExamChanceBook()
        }
    }

    private fun read(context: Context): ExamChanceBook {
        val f = file(context)
        if (!f.exists()) return ExamChanceBook()
        return parse(f.readText(Charsets.UTF_8))
    }

    private fun write(context: Context, book: ExamChanceBook) {
        book.updatedAt = System.currentTimeMillis()
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun get(context: Context, cardId: String): String =
        read(context).items[cardId].orEmpty().trim()

    fun all(context: Context): Map<String, String> =
        read(context).items.filterValues { it.isNotBlank() }

    fun set(context: Context, cardId: String, value: String) {
        if (cardId.isBlank()) return
        val book = read(context)
        val v = value.trim()
        if (v.isEmpty()) book.items.remove(cardId) else book.items[cardId] = v
        write(context, book)
    }

    fun line(value: String): String =
        if (value.isBlank()) "" else "출제가능성  $value"

    fun label(value: String): String =
        if (value.isBlank()) "출제가능성  설정" else "출제가능성  $value"

    fun attachPicker(tv: TextView, cardId: String, onChanged: () -> Unit = {}) {
        fun paint() {
            tv.text = label(get(tv.context, cardId))
        }
        paint()
        tv.setOnClickListener {
            pick(tv.context, cardId) {
                paint()
                onChanged()
            }
        }
    }

    fun isHollowJson(json: String): Boolean {
        if (json.isBlank()) return true
        return parse(json).items.values.none { it.isNotBlank() }
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parse(localJson)
        val b = parse(cloudJson)
        if (isHollowJson(localJson) && !isHollowJson(cloudJson)) return gson.toJson(b)
        if (isHollowJson(cloudJson) && !isHollowJson(localJson)) return gson.toJson(a)
        val newer = if (a.updatedAt >= b.updatedAt) a else b
        val older = if (a.updatedAt >= b.updatedAt) b else a
        val out = ExamChanceBook(updatedAt = newer.updatedAt)
        out.items.putAll(older.items.filter { it.value.isNotBlank() })
        out.items.putAll(newer.items.filter { it.value.isNotBlank() })
        newer.items.filter { it.value.isBlank() }.keys.forEach { out.items.remove(it) }
        return gson.toJson(out)
    }

    fun pick(context: Context, cardId: String, onChanged: () -> Unit) {
        val cur = get(context, cardId)
        val labels = arrayOf("표시 안 함", HIGH, MID, LOW)
        val values = arrayOf("", HIGH, MID, LOW)
        val checked = values.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("출제가능성")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                set(context, cardId, values[which])
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
