package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * 카드 데이터 저장/관리 레이어.
 * - 기본 카드: assets/cards.json (읽기 전용, CardRepository가 로드)
 * - 사용자 추가 카드: filesDir/user_cards.json
 * - 기존 카드 수정본: filesDir/edited_cards.json (id -> 수정된 Card)
 * - 삭제된 기본 카드 id: filesDir/deleted_ids.json
 *
 * 위 세 파일을 기본 카드와 합쳐서 최종 카드 목록을 만들어준다.
 * 이렇게 하면 나중에 assets/cards.json(새 과목 추가 등)이 업데이트 되어도
 * 사용자가 추가/수정한 내용은 그대로 유지된다.
 */
object CardStore {

    private val gson = Gson()

    private fun userCardsFile(context: Context) = File(context.filesDir, "user_cards.json")
    private fun editedCardsFile(context: Context) = File(context.filesDir, "edited_cards.json")
    private fun deletedIdsFile(context: Context) = File(context.filesDir, "deleted_ids.json")
    private fun wrongIdsFile(context: Context) = File(context.filesDir, "wrong_ids.json")
    private fun memosFile(context: Context) = File(context.filesDir, "memos.json")
    private fun orderFile(context: Context) = File(context.filesDir, "custom_order.json")
    private fun memoryFile(context: Context) = File(context.filesDir, "memory_levels.json")

    private fun readUserCards(context: Context): MutableList<Card> {
        val f = userCardsFile(context)
        if (!f.exists()) return mutableListOf()
        val type = object : TypeToken<MutableList<Card>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeUserCards(context: Context, cards: List<Card>) {
        userCardsFile(context).writeText(gson.toJson(cards), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
    }

    private fun readEditedCards(context: Context): MutableMap<String, Card> {
        val f = editedCardsFile(context)
        if (!f.exists()) return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, Card>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun writeEditedCards(context: Context, map: Map<String, Card>) {
        editedCardsFile(context).writeText(gson.toJson(map), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
    }

    private fun readDeletedIds(context: Context): MutableSet<String> {
        val f = deletedIdsFile(context)
        if (!f.exists()) return mutableSetOf()
        val type = object : TypeToken<MutableSet<String>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun writeDeletedIds(context: Context, ids: Set<String>) {
        deletedIdsFile(context).writeText(gson.toJson(ids), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
    }

    /** 기본 카드 + 사용자 추가 카드를 합치고, 수정본을 반영하고, 삭제된 것을 뺀 최종 목록 */
    fun getAllCards(context: Context): List<Card> {
        val base = CardRepository.loadCards(context)
        val userAdded = readUserCards(context)
        val edited = readEditedCards(context)
        val deleted = readDeletedIds(context)

        val merged = (base + userAdded)
            .filter { it.id !in deleted }
            .map { edited[it.id] ?: it }
            .map { card ->
                val subject = canonicalizeSubject(card.subject)
                if (card.subject == subject) card else card.copy(subject = subject)
            }

        return merged
    }

    fun getAllMnemonicCards(context: Context): List<Card> =
        getAllCards(context).filter { it.type == "mnemonic" && it.mnemonic.isNotBlank() }

    /** 새 두문자 카드 추가 */
    fun addMnemonicCard(
        context: Context,
        topicTitle: String,
        mnemonic: String,
        contextText: String,
        subject: String = "",
        num: String = ""
    ) {
        val id = "user_" + UUID.randomUUID().toString().take(8)
        val card = Card(
            id = id,
            type = "mnemonic",
            subject = canonicalizeSubject(subject),
            num = num,
            title = "두문자: $mnemonic",
            topicTitle = topicTitle,
            mnemonic = mnemonic,
            grade = "",
            front = "[$topicTitle]\n두문자 '$mnemonic' 은(는) 무엇의 앞글자일까?",
            back = contextText,
            mnemonics = listOf(mnemonic)
        )
        val list = readUserCards(context)
        list.add(card)
        writeUserCards(context, list)
    }

    /** 새 주제(개념카드) 추가. 맨 마지막 순서로 붙인다. */
    fun addConceptCard(context: Context, subject: String, topicTitle: String, body: String): Card {
        val subjectName = canonicalizeSubject(subject)
        val id = "user_" + UUID.randomUUID().toString().take(8)
        val card = Card(
            id = id,
            type = "concept",
            subject = subjectName,
            num = "",
            title = topicTitle,
            topicTitle = topicTitle,
            mnemonic = "",
            grade = "",
            front = topicTitle,
            back = body,
            mnemonics = emptyList()
        )
        val list = readUserCards(context)
        list.add(card)
        writeUserCards(context, list)

        // 순서 목록 맨 끝에 추가 (아직 순서 목록이 없으면 기존 카드들로 먼저 초기화)
        val order = readOrder(context, subjectName).toMutableList()
        if (order.isEmpty()) {
            val existing = getAllCards(context)
                .filter { it.subject == subjectName && it.type == "concept" && it.id != id }
                .sortedWith(compareBy({ numSortKey(it.num).first }, { numSortKey(it.num).second }))
            order.addAll(existing.map { it.id })
        }
        order.add(id)
        setCustomOrder(context, subjectName, order)
        return card
    }

    /** 이 기기에서 본문을 고쳤거나, 사용자가 새로 만든 카드인지. */
    fun isLocallyEdited(context: Context, id: String): Boolean =
        id.startsWith("user_") || readEditedCards(context).containsKey(id)

    /** 카드 수정 (사용자 추가 카드든 기본 카드든 동일하게 처리) */
    fun updateCard(context: Context, updated: Card) {
        val card = updated.copy(subject = canonicalizeSubject(updated.subject))
        if (card.id.startsWith("user_")) {
            val list = readUserCards(context)
            val idx = list.indexOfFirst { it.id == card.id }
            if (idx >= 0) {
                list[idx] = card
                writeUserCards(context, list)
            }
        } else {
            val map = readEditedCards(context)
            map[card.id] = card
            writeEditedCards(context, map)
        }
    }

    /** 카드 삭제 */
    fun deleteCard(context: Context, id: String) {
        if (id.startsWith("user_")) {
            val list = readUserCards(context)
            list.removeAll { it.id == id }
            writeUserCards(context, list)
        } else {
            val deleted = readDeletedIds(context)
            deleted.add(id)
            writeDeletedIds(context, deleted)
        }
        // 순서 목록에도 남아있다면 같이 정리
        val orders = readAllOrders(context)
        var changed = false
        for ((subject, ids) in orders) {
            if (ids.remove(id)) changed = true
        }
        if (changed) writeAllOrders(context, orders)
    }

    fun isCaseStudyCard(card: Card): Boolean {
        val topic = card.topicTitle.trim()
        val title = card.title.trim()
        return topic.startsWith("사례)") || title.startsWith("사례)")
    }

    fun purgeCaseStudyCards(context: Context) {
        val ids = getAllCards(context).filter { isCaseStudyCard(it) }.map { it.id }
        ids.forEach { deleteCard(context, it) }
        val edited = readEditedCards(context)
        val drop = edited.filter { isCaseStudyCard(it.value) }.keys.toList()
        if (drop.isNotEmpty()) {
            drop.forEach { edited.remove(it) }
            writeEditedCards(context, edited)
        }
    }

    // ---- 오답 복습(review) 지원 ----

    private fun readWrongIds(context: Context): MutableSet<String> {
        val f = wrongIdsFile(context)
        if (!f.exists()) return mutableSetOf()
        val type = object : TypeToken<MutableSet<String>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun writeWrongIds(context: Context, ids: Set<String>) {
        wrongIdsFile(context).writeText(gson.toJson(ids), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun getWrongIds(context: Context): Set<String> = WrongNoteStore.cardIds(context)

    fun addWrong(context: Context, id: String) {
        WrongNoteStore.recordWrongCard(context, id)
    }

    fun removeWrong(context: Context, id: String) {
        WrongNoteStore.recordCorrectCard(context, id)
    }

    // ---- 메모 ----

    private fun readMemos(context: Context): MutableMap<String, String> {
        val f = memosFile(context)
        if (!f.exists()) return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, String>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun writeMemos(context: Context, map: Map<String, String>) {
        memosFile(context).writeText(gson.toJson(map), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun getMemo(context: Context, cardId: String): String {
        return readMemos(context)[cardId] ?: ""
    }

    fun setMemo(context: Context, cardId: String, text: String) {
        val map = readMemos(context)
        if (text.isBlank()) {
            map.remove(cardId)
        } else {
            map[cardId] = text
        }
        writeMemos(context, map)
    }

    // ---- 과목 ----

    fun getSubjects(context: Context): List<String> {
        return orderedSubjects(getAllCards(context).map { it.subject })
    }

    // ---- 주제(개념카드) 순서 ----

    private fun readAllOrders(context: Context): MutableMap<String, MutableList<String>> {
        val f = orderFile(context)
        if (!f.exists()) return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, MutableList<String>>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun writeAllOrders(context: Context, map: Map<String, List<String>>) {
        orderFile(context).writeText(gson.toJson(map), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
    }

    private fun subjectOrderAliases(subject: String): List<String> {
        val key = canonicalizeSubject(subject)
        return if (key == "민법") {
            listOf("민법", "민법-계약법", "민법(계약)", "계약법")
        } else {
            listOf(key, subject)
        }
    }

    private fun readOrder(context: Context, subject: String): List<String> {
        val all = readAllOrders(context)
        val merged = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (key in subjectOrderAliases(subject)) {
            for (id in all[key].orEmpty()) {
                if (seen.add(id)) merged.add(id)
            }
        }
        return merged
    }

    /** 과목 내 주제(개념카드) id 순서를 저장한다. 본문학습/학습내용관리 목록 정렬에 쓰인다. */
    fun setCustomOrder(context: Context, subject: String, orderedIds: List<String>) {
        val all = readAllOrders(context)
        val key = canonicalizeSubject(subject)
        for (alias in subjectOrderAliases(key)) {
            if (alias != key) all.remove(alias)
        }
        all[key] = orderedIds.toMutableList()
        writeAllOrders(context, all)
    }

    fun getCustomOrder(context: Context, subject: String): List<String> = readOrder(context, subject)

    // ---- 암기정도 (주제 단위로 저장: 과목+주제제목 기준) ----

    private fun memoryKey(subject: String, topicTitle: String) =
        "${canonicalizeSubject(subject)}|$topicTitle"

    private fun memoryKeyAliases(subject: String, topicTitle: String): List<String> {
        val canon = memoryKey(subject, topicTitle)
        return listOf(
            canon,
            "민법-계약법|$topicTitle",
            "민법(계약)|$topicTitle",
            "$subject|$topicTitle"
        ).distinct()
    }

    private fun readMemoryLevels(context: Context): MutableMap<String, Int> {
        val f = memoryFile(context)
        if (!f.exists()) return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, Int>>() {}.type
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun writeMemoryLevels(context: Context, map: Map<String, Int>) {
        memoryFile(context).writeText(gson.toJson(map), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    /** 1(거의 모름) ~ 5(완벽히 암기) 사이 값. 아직 설정 안 했으면 1. */
    fun getMemoryLevel(context: Context, subject: String, topicTitle: String): Int {
        val map = readMemoryLevels(context)
        for (key in memoryKeyAliases(subject, topicTitle)) {
            map[key]?.let { return it }
        }
        return 1
    }

    fun memoryStarsLabel(level: Int): String {
        val n = level.coerceIn(1, 5)
        return "★".repeat(n) + "☆".repeat(5 - n)
    }

    fun resetAllMemoryLevels(context: Context, level: Int) {
        val lv = level.coerceIn(1, 5)
        val map = LinkedHashMap<String, Int>()
        getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() }
            .forEach { card ->
                map[memoryKey(card.subject, card.topicTitle)] = lv
            }
        writeMemoryLevels(context, map)
    }

    fun setMemoryLevel(context: Context, subject: String, topicTitle: String, level: Int) {
        val map = readMemoryLevels(context)
        val canon = memoryKey(subject, topicTitle)
        for (key in memoryKeyAliases(subject, topicTitle)) {
            if (key != canon) map.remove(key)
        }
        map[canon] = level.coerceIn(1, 5)
        writeMemoryLevels(context, map)
    }

    /** 위젯 등에서 다음 레벨로 순환시킬 때 사용 (1→2→3→4→5→1) */
    fun cycleMemoryLevel(context: Context, subject: String, topicTitle: String): Int {
        val cur = getMemoryLevel(context, subject, topicTitle)
        val next = if (cur >= 5) 1 else cur + 1
        setMemoryLevel(context, subject, topicTitle, next)
        return next
    }

    fun mergeMemoryJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableMap<String, Int>>() {}.type
        fun map(json: String): Map<String, Int> = try {
            if (json.isBlank()) emptyMap() else gson.fromJson<MutableMap<String, Int>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val out = map(cloudJson).toMutableMap()
        map(localJson).forEach { (k, v) ->
            out[k] = maxOf(out[k] ?: 1, v)
        }
        return gson.toJson(out)
    }

    fun mergeMemosJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableMap<String, String>>() {}.type
        fun map(json: String): Map<String, String> = try {
            if (json.isBlank()) emptyMap() else gson.fromJson<MutableMap<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val out = map(cloudJson).toMutableMap()
        map(localJson).forEach { (k, v) ->
            val other = out[k].orEmpty()
            out[k] = when {
                v.isBlank() -> other
                other.isBlank() -> v
                v.length >= other.length -> v
                else -> other
            }
        }
        return gson.toJson(out.filterValues { it.isNotBlank() })
    }

    fun mergeIdSetJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableSet<String>>() {}.type
        fun ids(json: String): Set<String> = try {
            if (json.isBlank()) emptySet() else gson.fromJson<MutableSet<String>>(json, type) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        return gson.toJson(ids(localJson) + ids(cloudJson))
    }

    fun mergeUserCardsJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableList<Card>>() {}.type
        fun list(json: String): List<Card> = try {
            if (json.isBlank()) emptyList() else gson.fromJson<MutableList<Card>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val byId = linkedMapOf<String, Card>()
        (list(cloudJson) + list(localJson)).forEach { card ->
            val old = byId[card.id]
            byId[card.id] = if (old == null) card else richerCard(old, card)
        }
        return gson.toJson(byId.values.toList())
    }

    fun mergeEditedCardsJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableMap<String, Card>>() {}.type
        fun map(json: String): Map<String, Card> = try {
            if (json.isBlank()) emptyMap() else gson.fromJson<MutableMap<String, Card>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val out = map(cloudJson).toMutableMap()
        map(localJson).forEach { (id, card) ->
            val old = out[id]
            out[id] = if (old == null) card else richerCard(old, card)
        }
        return gson.toJson(out)
    }

    fun mergeOrderJson(localJson: String, cloudJson: String): String {
        val type = object : TypeToken<MutableMap<String, MutableList<String>>>() {}.type
        fun map(json: String): Map<String, List<String>> = try {
            if (json.isBlank()) emptyMap()
            else gson.fromJson<MutableMap<String, MutableList<String>>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val out = map(cloudJson).mapValues { it.value.toMutableList() }.toMutableMap()
        map(localJson).forEach { (sub, ids) ->
            val cur = out.getOrPut(sub) { mutableListOf() }
            val seen = cur.toMutableSet()
            ids.forEach { if (seen.add(it)) cur.add(it) }
        }
        return gson.toJson(out)
    }

    private fun richerCard(a: Card, b: Card): Card {
        val aLen = a.back.length + a.front.length + a.mnemonic.length
        val bLen = b.back.length + b.front.length + b.mnemonic.length
        return if (bLen > aLen) b else a
    }
}
