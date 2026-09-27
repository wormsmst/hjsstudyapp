package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

object RubricRepository {
    private var cachedMap: Map<String, List<String>>? = null

    fun getKeywords(context: Context, topicTitle: String): List<String> {
        val map = cachedMap ?: run {
            try {
                val stream = context.assets.open("rubric_keywords.json")
                val reader = InputStreamReader(stream, Charsets.UTF_8)
                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                val loaded = Gson().fromJson<Map<String, List<String>>>(reader, type) ?: emptyMap()
                cachedMap = loaded
                loaded
            } catch (e: Exception) {
                emptyMap()
            }
        }
        return map[topicTitle] ?: listOf("의의 및 취지", "요건 및 절차", "법적 효과", "판례의 태도")
    }
}
