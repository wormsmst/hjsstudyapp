package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class MockExamItem(
    val id: String,
    val subject: String,
    val title: String,
    val question: String,
    val explanation: String,
    val issues: List<String> = emptyList()
)

object MockExamRepository {
    private var cache: List<MockExamItem>? = null

    fun loadMockExams(context: Context): List<MockExamItem> {
        cache?.let { return it }
        return try {
            val json = context.assets.open("mock_exams.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val type = object : TypeToken<List<MockExamItem>>() {}.type
            val list: List<MockExamItem> = Gson().fromJson(json, type) ?: emptyList()
            cache = list
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
