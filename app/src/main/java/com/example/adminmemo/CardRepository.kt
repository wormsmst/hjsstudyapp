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
        val list: List<Card> = Gson().fromJson(json, type)
        cache = list
        return list
    }
}
