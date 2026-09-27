package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object CardRepository {

    private var cache: List<Card>? = null

    fun loadCards(context: Context): List<Card> {
        cache?.let { return it }
        val json = context.assets.open("cards.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val type = object : TypeToken<List<Card>>() {}.type
        val list: List<Card> = Gson().fromJson<List<Card>>(json, type) ?: emptyList()
        val filtered = list.filter { 
            it.type != "mnemonic" && 
            !it.title.startsWith("두문자") && 
            !it.front.contains("두문자 '")
        }
        cache = filtered
        return filtered
    }

    fun clearCache() {
        cache = null
    }
}
