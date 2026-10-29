package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID

data class DailyQuest(
    val id: String = "",
    val date: String = "",
    val type: String = DailyQuestStore.TYPE_WRITE,
    val cardId: String = "",
    val title: String = "",
    val subject: String = "",
    val hint: String = "",
    val target: Int = 1,
    var progress: Int = 0
) {
    val done: Boolean get() = progress >= target
}

data class QuestDaySnap(
    var date: String = "",
    var recallDone: Boolean = false,
    var quests: MutableList<DailyQuest> = mutableListOf(),
    var gradedIds: MutableList<String> = mutableListOf()
) {
    fun allDone(): Boolean = quests.isNotEmpty() && quests.all { it.done }
    fun anyDone(): Boolean = quests.any { it.done }
    fun gradedCount(): Int = gradedIds.distinct().size
    fun hasRecall(): Boolean = recallDone || gradedCount() > 0 || quests.isNotEmpty()
}

private data class QuestBook(
    var date: String = "",
    var gradedIds: MutableList<String> = mutableListOf(),
    var quests: MutableList<DailyQuest> = mutableListOf(),
    var recallDone: Boolean = false,
    var days: MutableMap<String, QuestDaySnap> = mutableMapOf(),
    var backlog: MutableList<DailyQuest> = mutableListOf(),
    var retrainIds: MutableList<String> = mutableListOf()
)

object DailyQuestStore {
    const val TYPE_WRITE = "write"
    const val TYPE_LISTEN = "listen"
    const val TYPE_BODY = "body"
    const val HINT_BODY = "제목만 보고 목차·문장 전부"
    private const val FILE = "recall_quests.json"
    private const val BACKLOG_CAP = 20
    private val gson = Gson()
    @Volatile private var cached: QuestBook? = null

    fun invalidateCache() {
        cached = null
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): QuestBook {
        val today = TodayTtsStore.todayKey()
        cached?.let { book ->
            if (TodayTtsStore.canonKey(book.date) == today) {
                if (reclaimTodayQuests(context, book, today)) writeFile(context, book, notify = false)
                return book
            }
            val rolled = roll(context, book)
            writeFile(context, rolled, notify = false)
            return rolled
        }
        val f = file(context)
        val book = if (!f.exists()) {
            QuestBook(today)
        } else {
            try {
                val parsed = gson.fromJson(f.readText(Charsets.UTF_8), QuestBook::class.java) ?: QuestBook()
                normalize(parsed)
                roll(context, parsed)
            } catch (_: Exception) {
                QuestBook(today)
            }
        }
        cached = book
        val ready = if (TodayTtsStore.canonKey(book.date) != today) roll(context, book) else book
        val moved = reclaimTodayQuests(context, ready, today)
        if (ready !== book || moved) writeFile(context, ready, notify = false)
        return ready
    }

    private fun normalize(book: QuestBook) {
        book.gradedIds = book.gradedIds ?: mutableListOf()
        book.quests = book.quests ?: mutableListOf()
        book.days = book.days ?: mutableMapOf()
        book.backlog = book.backlog ?: mutableListOf()
        book.retrainIds = book.retrainIds ?: mutableListOf()
        if (book.date.isNotBlank()) book.date = TodayTtsStore.canonKey(book.date)
        book.quests = canonQuestList(book.quests)
        book.backlog = canonQuestList(book.backlog)
        val days = mutableMapOf<String, QuestDaySnap>()
        book.days.forEach { (k, snap) ->
            snap.date = TodayTtsStore.canonKey(snap.date.ifBlank { k })
            snap.quests = canonQuestList(snap.quests ?: mutableListOf())
            snap.gradedIds = snap.gradedIds ?: mutableListOf()
            snap.quests.removeAll { it.type == TYPE_LISTEN }
            val key = TodayTtsStore.canonKey(k)
            days[key] = if (days[key] == null) snap else mergeDay(days[key], snap)
        }
        book.days = days
        book.quests.removeAll { it.type == TYPE_LISTEN }
        book.backlog.removeAll { it.type == TYPE_LISTEN }
    }

    private fun canonQuestList(list: MutableList<DailyQuest>): MutableList<DailyQuest> =
        list.map { q ->
            if (q.date.isBlank()) q else q.copy(date = TodayTtsStore.canonKey(q.date))
        }.toMutableList()

    private fun reclaimTodayQuests(context: Context, book: QuestBook, today: String): Boolean {
        val before = book.quests.map { it.id } to book.backlog.map { it.id }
        val have = book.quests.map { it.type + "|" + it.cardId }.toMutableSet()
        fun take(q: DailyQuest) {
            if (q.done || q.type == TYPE_LISTEN) return
            val key = q.type + "|" + q.cardId
            if (key in have) return
            book.quests.add(q.copy(date = today))
            have.add(key)
        }
        book.days[today]?.quests.orEmpty().forEach(::take)
        val prev = TodayTtsStore.previousKey(today)
        val recalledToday = book.recallDone ||
            book.days[today]?.recallDone == true ||
            book.gradedIds.isNotEmpty() ||
            RecallStore.exportTodayMarks(context).isNotEmpty() ||
            StudyWeekStore.dayRecalled(context, today).isNotEmpty()
        val recent = setOf(today, prev)
        val fromSnaps = (listOfNotNull(book.days[today], book.days[prev]) +
            book.days.values.filter { TodayTtsStore.canonKey(it.date) in recent })
            .flatMap { it.quests }
        val todayWasEmpty = book.quests.isEmpty()
        val keepBacklog = mutableListOf<DailyQuest>()
        book.backlog.forEach { q ->
            val dated = TodayTtsStore.canonKey(q.date)
            val fromToday = dated == today || (dated.isBlank() && recalledToday)
            val fromRecentSnap = recalledToday && todayWasEmpty &&
                fromSnaps.any { it.type == q.type && it.cardId == q.cardId }
            if (!q.done && (fromToday || fromRecentSnap)) take(q)
            else keepBacklog.add(q)
        }
        book.backlog = keepBacklog
        if (book.quests.isNotEmpty()) {
            book.recallDone = true
            book.date = today
        }
        return before != (book.quests.map { it.id } to book.backlog.map { it.id })
    }

    private fun roll(context: Context, book: QuestBook): QuestBook {
        val today = TodayTtsStore.todayKey()
        book.date = TodayTtsStore.canonKey(book.date)
        snapshot(book)
        if (book.date == today) {
            reclaimTodayQuests(context, book, today)
            return book
        }
        val undone = book.quests.filter { !it.done && it.type != TYPE_LISTEN }
        val stay = undone.filter { TodayTtsStore.canonKey(it.date.ifBlank { book.date }) == today }
        val aged = undone.filter { q -> stay.none { it.id == q.id } }
        val next = QuestBook(
            date = today,
            recallDone = stay.isNotEmpty() || book.days[today]?.recallDone == true,
            gradedIds = (book.days[today]?.gradedIds ?: mutableListOf()).toMutableList(),
            quests = stay.map { it.copy(date = today) }.toMutableList(),
            days = book.days,
            backlog = compactBacklog(aged + book.backlog),
            retrainIds = mutableListOf()
        )
        reclaimTodayQuests(context, next, today)
        return next
    }

    private fun compactBacklog(items: List<DailyQuest>): MutableList<DailyQuest> {
        val map = linkedMapOf<String, DailyQuest>()
        items.forEach { q ->
            if (q.type == TYPE_LISTEN) return@forEach
            val key = q.type + "|" + q.cardId
            val old = map[key]
            if (old == null || q.progress > old.progress) map[key] = q.copy()
        }
        val open = map.values.filter { !it.done }.take(BACKLOG_CAP)
        val closed = map.values.filter { it.done }.takeLast(40)
        return (open + closed).toMutableList()
    }

    private fun snapshot(book: QuestBook) {
        if (book.date.isBlank()) return
        book.days[book.date] = QuestDaySnap(
            date = book.date,
            recallDone = book.recallDone,
            quests = book.quests.map { it.copy() }.toMutableList(),
            gradedIds = book.gradedIds.distinct().toMutableList()
        )
        pruneDays(book)
    }

    private fun pruneDays(book: QuestBook) {
        if (book.days.size <= 90) return
        val keep = book.days.keys.sortedDescending().take(90).toSet()
        book.days.keys.filter { it !in keep }.forEach { book.days.remove(it) }
    }

    private fun write(context: Context, book: QuestBook) {
        writeFile(context, book, notify = true)
    }

    private fun writeFile(context: Context, book: QuestBook, notify: Boolean) {
        snapshot(book)
        cached = book
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
        if (notify) {
            AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
            FirebaseSyncManager.notifyProgressChanged(context)
        }
    }

    fun recallDoneToday(context: Context): Boolean {
        if (RecallSessionStore.openToday(context)) return false
        val book = read(context)
        if (book.quests.isNotEmpty()) return true
        if (hasTodayRecallEvidence(context)) return true
        val today = TodayTtsStore.todayKey()
        return book.recallDone || book.days[today]?.recallDone == true
    }

    private fun hasTodayRecallEvidence(context: Context): Boolean {
        if (todayGradedIds(context).isNotEmpty()) return true
        if (RecallStore.exportTodayMarks(context).isNotEmpty()) return true
        return StudyWeekStore.dayRecalled(context, TodayTtsStore.todayKey()).isNotEmpty()
    }

    fun everRecallDone(context: Context): Boolean {
        val book = read(context)
        return book.recallDone || book.days.values.any { it.recallDone }
    }

    fun todayQuests(context: Context): List<DailyQuest> = read(context).quests

    fun openQuests(context: Context): List<DailyQuest> = todayQuests(context).filter { !it.done }

    fun backlog(context: Context): List<DailyQuest> = read(context).backlog.filter { !it.done }

    fun daySnap(context: Context, date: String): QuestDaySnap? {
        val key = TodayTtsStore.canonKey(date)
        val book = read(context)
        if (key == TodayTtsStore.canonKey(book.date) || key == TodayTtsStore.todayKey()) {
            return todaySnap(book)
        }
        return book.days[key]
    }

    fun monthSnaps(context: Context, year: Int, month1: Int): List<QuestDaySnap> {
        val prefix = "$year-$month1-"
        val book = read(context)
        val out = mutableListOf<QuestDaySnap>()
        book.days.forEach { (k, v) ->
            val key = TodayTtsStore.canonKey(k)
            if (key.startsWith(prefix) || monthKeyMatch(key, year, month1)) {
                out.add(v.copy(date = key.ifBlank { v.date }))
            }
        }
        if (book.date.let { monthKeyMatch(it, year, month1) }) {
            out.removeAll { TodayTtsStore.canonKey(it.date) == TodayTtsStore.canonKey(book.date) }
            out.add(todaySnap(book))
        }
        return out.distinctBy { TodayTtsStore.canonKey(it.date) }
    }

    fun overlayHitsFromWeek(context: Context) {
        val hits = StudyWeekStore.recentHitKeys(context, 14)
        if (hits.isEmpty()) return
        val today = TodayTtsStore.todayKey()
        hits.forEach { key ->
            overlayToday(context, key, recallDone = true, gradedIds = emptyList(), quests = emptyList())
        }
        val book = read(context)
        if (today in hits && !RecallSessionStore.openToday(context) && !book.recallDone) {
            book.recallDone = true
            writeFile(context, book, notify = false)
        }
    }

    private fun todaySnap(book: QuestBook) = QuestDaySnap(
        date = book.date,
        recallDone = book.recallDone,
        quests = book.quests.map { it.copy() }.toMutableList(),
        gradedIds = book.gradedIds.distinct().toMutableList()
    )

    private fun monthKeyMatch(key: String, year: Int, month1: Int): Boolean {
        val p = key.split("-")
        if (p.size < 2) return false
        return p[0].toIntOrNull() == year && p[1].toIntOrNull() == month1
    }

    fun gradedToday(context: Context): Int = read(context).gradedIds.distinct().size

    fun gradedIdsByDay(context: Context): Map<String, List<String>> {
        val book = read(context)
        val out = mutableMapOf<String, MutableList<String>>()
        fun add(day: String, ids: List<String>?) {
            if (day.isBlank()) return
            val list = out.getOrPut(day) { mutableListOf() }
            ids.orEmpty().forEach { id ->
                if (id.isNotBlank() && id !in list) list.add(id)
            }
        }
        book.days.forEach { (k, v) ->
            add(k, v.gradedIds)
            add(v.date, v.gradedIds)
        }
        add(book.date, book.gradedIds)
        return out
    }

    fun todayGradedIds(context: Context): List<String> = read(context).gradedIds.distinct()

    fun mergeQuestLists(lists: List<List<DailyQuest>>, preferFresh: Boolean): MutableList<DailyQuest> {
        val map = linkedMapOf<String, DailyQuest>()
        lists.forEach { list ->
            list.forEach { q ->
                val key = q.type + "|" + q.cardId
                val old = map[key]
                map[key] = if (old == null) q.copy() else pickQuest(old, q, preferFresh)
            }
        }
        return map.values.toMutableList()
    }

    fun pickQuest(old: DailyQuest, incoming: DailyQuest, preferFresh: Boolean): DailyQuest {
        if (old.id == incoming.id) {
            return if (incoming.progress > old.progress) incoming.copy() else old
        }
        if (preferFresh && old.done != incoming.done) {
            return if (incoming.done) old.copy() else incoming.copy()
        }
        return if (incoming.progress > old.progress) incoming.copy() else old
    }

    fun overlayToday(
        context: Context,
        date: String,
        recallDone: Boolean,
        gradedIds: Collection<String>,
        quests: List<DailyQuest>
    ) {
        if (date.isBlank()) return
        val book = read(context)
        fun mergeQuests(into: MutableList<DailyQuest>, liveToday: Boolean) {
            val merged = mergeQuestLists(listOf(into, quests), preferFresh = liveToday)
            into.clear()
            into.addAll(merged)
        }
        if (TodayTtsStore.canonKey(date) == TodayTtsStore.todayKey()) {
            if (recallDone && !RecallSessionStore.openToday(context)) book.recallDone = true
            gradedIds.forEach { id -> if (id.isNotBlank() && id !in book.gradedIds) book.gradedIds.add(id) }
            mergeQuests(book.quests, liveToday = true)
            writeFile(context, book, notify = false)
            return
        }
        val snap = book.days[date] ?: QuestDaySnap(date = date)
        if (recallDone) snap.recallDone = true
        snap.gradedIds = (snap.gradedIds + gradedIds).distinct().toMutableList()
        mergeQuests(snap.quests, liveToday = false)
        book.days[date] = snap
        writeFile(context, book, notify = false)
    }

    fun noteGraded(context: Context, cardId: String) {
        val book = read(context)
        if (cardId !in book.gradedIds) book.gradedIds.add(cardId)
        write(context, book)
    }

    fun questForCard(context: Context, cardId: String, type: String = TYPE_WRITE): DailyQuest? =
        (todayQuests(context) + backlog(context)).firstOrNull { it.cardId == cardId && it.type == type && !it.done }

    fun openQuestForCard(context: Context, cardId: String): DailyQuest? {
        val open = (todayQuests(context) + backlog(context)).filter { it.cardId == cardId && !it.done }
        return open.firstOrNull { it.type == TYPE_BODY } ?: open.firstOrNull()
    }

    fun kindLabel(q: DailyQuest): String = when (q.type) {
        TYPE_BODY -> "제목만 보고 목차·문장 전부"
        TYPE_LISTEN -> "듣기"
        else -> "제목만 보고 목차·문장"
    }

    fun bump(context: Context, questId: String, by: Int = 1): DailyQuest? {
        val book = read(context)
        val q = book.quests.firstOrNull { it.id == questId }
            ?: book.backlog.firstOrNull { it.id == questId }
            ?: return null
        q.progress = (q.progress + by).coerceAtMost(q.target)
        write(context, book)
        return q
    }

    fun setDone(context: Context, questId: String, done: Boolean) {
        val book = read(context)
        val q = book.quests.firstOrNull { it.id == questId }
            ?: book.backlog.firstOrNull { it.id == questId }
            ?: return
        q.progress = if (done) q.target else 0
        write(context, book)
    }

    fun markListenDone(context: Context) {
        val book = read(context)
        book.quests.filter { it.type == TYPE_LISTEN }.forEach { it.progress = it.target }
        book.backlog.filter { it.type == TYPE_LISTEN }.forEach { it.progress = it.target }
        book.backlog.removeAll { it.type == TYPE_LISTEN && it.done }
        write(context, book)
    }

    fun listenIds(context: Context): List<String> =
        (todayQuests(context) + backlog(context))
            .filter { it.type == TYPE_LISTEN && !it.done }
            .map { it.cardId }
            .filter { it.isNotBlank() }

    fun writeCardIds(context: Context): List<String> =
        (todayQuests(context) + backlog(context))
            .filter { it.type == TYPE_WRITE || it.type == TYPE_BODY }
            .filter { !it.done }
            .map { it.cardId }

    fun openQuestCardIds(context: Context): Set<String> =
        (todayQuests(context) + backlog(context))
            .filter { !it.done }
            .map { it.cardId }
            .filter { it.isNotBlank() }
            .toSet()

    fun retrainIds(context: Context): List<String> = read(context).retrainIds.filter { it.isNotBlank() }

    fun writesDoneToday(context: Context): Boolean {
        val qs = todayQuests(context).filter { it.type == TYPE_WRITE || it.type == TYPE_BODY }
        return qs.isNotEmpty() && qs.all { it.done }
    }

    fun questsCompleteToday(context: Context): Boolean {
        if (!recallDoneToday(context)) return false
        return todayQuests(context).all { it.done }
    }

    fun clearRetrain(context: Context) {
        val book = read(context)
        if (book.retrainIds.isEmpty()) return
        book.retrainIds.clear()
        write(context, book)
    }

    fun dismissOpenQuestsForCard(context: Context, cardId: String) {
        if (cardId.isBlank()) return
        val book = read(context)
        var changed = false
        fun close(list: MutableList<DailyQuest>) {
            list.forEach { q ->
                if (q.cardId == cardId && !q.done) {
                    q.progress = q.target
                    changed = true
                }
            }
        }
        close(book.quests)
        close(book.backlog)
        if (book.retrainIds.removeAll { it == cardId }) changed = true
        if (changed) write(context, book)
    }

    fun failHint(kind: Int): String = when (kind) {
        RecallStore.FAIL_TIP -> "단어만 안 나옴 · 제목만 보고 막힌 문장"
        RecallStore.FAIL_STRUCTURE -> "목차부터 다시 · 제목만 보고 전부 쓰기"
        else -> "제목만 보고 목차·문장"
    }

    fun addDueCurveQuests(context: Context) {
        val book = read(context)
        if (!book.recallDone) return
        if (book.quests.isNotEmpty() && book.quests.all { it.done }) return
        val before = book.quests.map { it.id }
        enforceQuestBudget(book, context)
        val budget = todayQuestBudget(context)
        val curveUsed = book.quests.count { isCurveQuest(it) }
        val assigned = book.quests.size
        val room = (budget - assigned)
            .coerceAtMost(curveCap(context) - curveUsed)
            .coerceAtLeast(0)
        var added = 0
        if (room > 0) {
            val have = (book.quests + book.backlog).map { it.cardId }.toMutableSet()
            val pairs = RecallStore.curveReviewCards(context)
            val labelBy = pairs.associate { it.first.id to it.second }
            for (card in pickByChance(context, pairs.map { it.first }.filter { it.id !in have }, room)) {
                val label = labelBy[card.id] ?: continue
                val hint = "$label · 복습 기한"
                book.quests.add(
                    if (isSeriousCard(context, card, missCount = 0, selfMiss = 0))
                        bodyQuest(book.date, card, hint = "$hint · $HINT_BODY")
                    else writeQuest(book.date, card, target = 1, hint = hint)
                )
                have.add(card.id)
                added++
            }
        }
        if (added > 0 || book.quests.map { it.id } != before) write(context, book)
    }

    /** 복습 기한 퀘스트는 호출마다가 아니라 하루 curveCap까지만. 완료분은 자르지 않는다. */
    private fun enforceQuestBudget(book: QuestBook, context: Context) {
        val budget = todayQuestBudget(context)
        val cap = curveCap(context)
        fun dropLastOpen(predicate: (DailyQuest) -> Boolean, n: Int) {
            if (n <= 0) return
            val idx = book.quests.withIndex()
                .filter { !it.value.done && predicate(it.value) }
                .map { it.index }
                .takeLast(n)
                .toSet()
            if (idx.isEmpty()) return
            book.quests = book.quests.filterIndexed { i, _ -> i !in idx }.toMutableList()
        }
        val curveOpen = book.quests.count { !it.done && isCurveQuest(it) }
        if (curveOpen > cap) dropLastOpen(::isCurveQuest, curveOpen - cap)
        val open = book.quests.count { !it.done }
        if (open > budget) {
            val extra = open - budget
            dropLastOpen(::isCurveQuest, extra)
            val still = book.quests.count { !it.done } - budget
            val newToday = StudyWeekStore.todayNewIds(context)
            if (still > 0) {
                dropLastOpen(
                    { isBoostQuest(it) && it.cardId !in newToday && !it.hint.contains("신규") },
                    still
                )
            }
        }
    }

    private fun isCurveQuest(q: DailyQuest): Boolean = q.hint.contains("복습 기한")

    fun isBoostQuest(q: DailyQuest): Boolean =
        q.hint.contains("보강") || q.hint.contains("보충")

    fun openBoostCount(context: Context): Int =
        todayQuests(context).count { isBoostQuest(it) && !it.done }

    fun appendBoostFromSession(
        context: Context,
        results: List<Pair<Card, Int>>,
        selfMissByCard: Map<String, Int> = emptyMap(),
        failKindByCard: Map<String, Int> = emptyMap()
    ) {
        val book = read(context)
        val lastByCard = results
            .groupBy { it.first.id }
            .map { (_, rows) -> rows.last() }
        val miss = lastByCard.filter { it.second == RecallStore.GRADE_MISS }.map { it.first }
            .distinctBy { it.id }
        val half = lastByCard.filter { it.second == RecallStore.GRADE_HALF }.map { it.first }
            .distinctBy { it.id }
            .filter { h -> miss.none { it.id == h.id } }
        fun kindOf(card: Card) = failKindByCard[card.id]
            ?: RecallStore.lastFailKind(context, card.id)
        val have = book.quests.filter { !it.done }.map { it.cardId }.toMutableSet()
        val newToday = StudyWeekStore.todayNewIds(context)
        val boostNew = lastByCard.map { it.first }
            .distinctBy { it.id }
            .filter { !it.id.startsWith("case_") && it.id in newToday }
        fun addFor(card: Card, newCard: Boolean) {
            if (card.id in have) return
            book.quests.removeAll { it.cardId == card.id && it.done }
            val tag = if (newCard) "보강 · 신규" else "보강"
            val hint = "$tag · ${failHint(kindOf(card))}"
            book.quests.add(
                if (isSeriousCard(context, card, missCount = 1, selfMiss = selfMissByCard[card.id] ?: 0))
                    bodyQuest(book.date, card, hint = "$tag · $HINT_BODY")
                else writeQuest(book.date, card, target = 1, hint = hint)
            )
            have.add(card.id)
        }
        boostNew.forEach { addFor(it, true) }
        miss.forEach { addFor(it, it.id in newToday) }
        half.forEach { addFor(it, it.id in newToday) }
        val extraIds = (boostNew + miss + half).map { it.id }
        extraIds.forEach { id ->
            if (id !in book.retrainIds) book.retrainIds.add(id)
        }
        write(context, book)
    }

    fun addExamWeak(context: Context, cards: List<Card>) {
        if (cards.isEmpty()) return
        val book = read(context)
        val have = (book.quests + book.backlog).map { it.cardId }.toMutableSet()
        pickByChance(context, cards, 4).forEach { card ->
            if (card.id in have) return@forEach
            book.quests.add(
                if (isSeriousCard(context, card, missCount = 0, selfMiss = 0))
                    bodyQuest(book.date, card, hint = "모의고사 · $HINT_BODY")
                else writeQuest(book.date, card, target = 1, hint = "모의고사")
            )
            have.add(card.id)
            RecallStore.bumpDueNow(context, card.id)
        }
        write(context, book)
    }

    private fun writeQuest(date: String, card: Card, target: Int, hint: String = ""): DailyQuest =
        DailyQuest(
            id = "w_" + UUID.randomUUID().toString().take(8),
            date = date,
            type = TYPE_WRITE,
            cardId = card.id,
            title = card.topicTitle.ifBlank { card.title },
            subject = canonicalizeSubject(card.subject),
            hint = hint,
            target = target
        )

    private fun bodyQuest(date: String, card: Card, hint: String = HINT_BODY): DailyQuest =
        DailyQuest(
            id = "b_" + UUID.randomUUID().toString().take(8),
            date = date,
            type = TYPE_BODY,
            cardId = card.id,
            title = card.topicTitle.ifBlank { card.title },
            subject = canonicalizeSubject(card.subject),
            hint = hint,
            target = 1
        )

    /** 반복 실패, 재시도도 실패, 목차·문장이 둘 다 막힌 장만 본문 전체 쓰기로 올린다. */
    fun isSeriousCard(
        context: Context,
        card: Card,
        missCount: Int,
        selfMiss: Int
    ): Boolean {
        val lapses = RecallStore.lapses(context, card.id)
        return missCount >= 2 || selfMiss >= 2 || lapses >= 3
    }

    fun rebuildFromSession(
        context: Context,
        results: List<Pair<Card, Int>>,
        selfMissByCard: Map<String, Int> = emptyMap(),
        failKindByCard: Map<String, Int> = emptyMap()
    ) {
        val book = read(context)
        book.date = TodayTtsStore.todayKey()
        book.recallDone = true
        results.forEach { (card, _) ->
            if (!card.id.startsWith("case_") && card.id.isNotBlank() && card.id !in book.gradedIds) {
                book.gradedIds.add(card.id)
            }
        }
        val lastByCard = results
            .groupBy { it.first.id }
            .map { (_, rows) -> rows.last() }
        val missAll = lastByCard.filter { it.second == RecallStore.GRADE_MISS }.map { it.first }
            .distinctBy { it.id }
        val newIds = StudyWeekStore.todayNewIds(context)
        val newCards = lastByCard.map { it.first }
            .distinctBy { it.id }
            .filter { !it.id.startsWith("case_") && it.id in newIds }
        val miss = missAll
        val half = lastByCard.filter { it.second == RecallStore.GRADE_HALF }.map { it.first }
            .distinctBy { it.id }
            .filter { h -> miss.none { it.id == h.id } }
        fun kindOf(card: Card) = failKindByCard[card.id]
            ?: RecallStore.lastFailKind(context, card.id)
        val rankedSerious = missAll.sortedWith(
            compareByDescending<Card> { if (kindOf(it) == RecallStore.FAIL_STRUCTURE) 1 else 0 }
                .thenByDescending { RecallStore.lapses(context, it.id) }
                .thenByDescending { selfMissByCard[it.id] ?: 0 }
                .thenByDescending { results.count { r -> r.first.id == it.id && r.second == RecallStore.GRADE_MISS } }
        ).filter { card ->
            kindOf(card) == RecallStore.FAIL_STRUCTURE || isSeriousCard(
                context,
                card,
                missCount = results.count { it.first.id == card.id && it.second == RecallStore.GRADE_MISS },
                selfMiss = selfMissByCard[card.id] ?: 0
            )
        }
        val seriousIds = rankedSerious.map { it.id }.toSet()
        val quests = mutableListOf<DailyQuest>()
        rankedSerious.forEach { card ->
            quests.add(bodyQuest(book.date, card, hint = "목차부터 다시 · $HINT_BODY"))
        }
        val newIdSet = newCards.map { it.id }.toSet()
        newCards.forEach { card ->
            if (card.id in seriousIds) return@forEach
            quests.add(writeQuest(book.date, card, target = 1, hint = "신규 · ${failHint(kindOf(card))}"))
        }
        miss.forEach { card ->
            if (card.id in seriousIds || card.id in newIdSet) return@forEach
            quests.add(writeQuest(book.date, card, target = 1, hint = failHint(kindOf(card))))
        }
        half.forEach { card ->
            if (card.id in seriousIds || card.id in newIdSet) return@forEach
            quests.add(writeQuest(book.date, card, target = 1, hint = failHint(kindOf(card))))
        }
        val budget = todayQuestBudget(context)
        val keepExam = book.quests.filter { !it.done && it.hint.contains("모의고사") }
        val keepBoost = book.quests.filter { !it.done && isBoostQuest(it) }
        val have = quests.map { it.cardId }.toMutableSet()
        keepExam.forEach { q ->
            if (q.cardId !in have) {
                quests.add(q)
                have.add(q.cardId)
            }
        }
        keepBoost.forEach { q ->
            if (q.cardId in have) return@forEach
            val keepNew = q.cardId in newIds || q.hint.contains("신규")
            if (!keepNew && quests.count { !it.done } >= budget) return@forEach
            quests.add(q)
            have.add(q.cardId)
        }
        book.quests = quests
        val todayIds = quests.map { it.cardId }.toSet()
        book.backlog.removeAll { it.cardId in todayIds }
        book.retrainIds = (newCards + miss + half + rankedSerious).map { it.id }
            .plus(keepExam.map { it.cardId })
            .plus(keepBoost.map { it.cardId })
            .distinct()
            .toMutableList()
        write(context, book)
        addDueCurveQuests(context)
        val listenCards = pickByChance(context, (miss + half).distinctBy { it.id }, listenCap(context))
        if (listenCards.isNotEmpty()) {
            TodayTtsStore.setNextMorningFromIds(
                context,
                listenCards.map { it.id },
                "인출에서 막힌 주제를 다음 출근길에 듣도록 묶어 두었어요"
            )
        }
    }

    /** 인출의 약 1/3과 오늘 신규 인출 중 큰 값. 신규가 없으면 1/3만. */
    fun todayQuestBudget(context: Context): Int {
        val n = StudyLoadStore.todayCards(context)
        val third = if (n <= 0) 2 else (n / 3).coerceAtLeast(1)
        return maxOf(third, newQuestFloor(context))
    }

    private fun newQuestFloor(context: Context): Int {
        val want = StudyLoadStore.newPerDay(context)
        val newToday = StudyWeekStore.todayNewIds(context).size
        if (want <= 0) return newToday
        val unseen = unseenConceptCount(context)
        return maxOf(newToday, minOf(want, unseen + newToday))
    }

    private fun unseenConceptCount(context: Context): Int {
        val counts = RecallStore.allRecallCounts(context)
        return CardStore.getAllCards(context).count {
            it.type == "concept" && it.topicTitle.isNotBlank() && (counts[it.id] ?: 0) <= 0
        }
    }

    private fun curveCap(context: Context): Int =
        (todayQuestBudget(context) / 3).coerceAtLeast(0)

    private fun listenCap(context: Context): Int =
        (todayQuestBudget(context) + 1).coerceIn(2, 6)

    private fun chanceRank(context: Context, cardId: String): Int =
        when (ExamChanceStore.get(context, cardId)) {
            ExamChanceStore.HIGH -> 3
            ExamChanceStore.MID -> 2
            ExamChanceStore.LOW -> 0
            else -> 1
        }

    private fun pickByChance(context: Context, cards: List<Card>, cap: Int): List<Card> {
        if (cap <= 0) return emptyList()
        return cards.distinctBy { it.id }
            .sortedWith(
                compareByDescending<Card> { chanceRank(context, it.id) }
                    .thenByDescending { RecallStore.lapses(context, it.id) }
            )
            .take(cap)
    }

    fun todayVolume(json: String): Int {
        if (json.isBlank()) return 0
        val today = TodayTtsStore.todayKey()
        return try {
            val b = gson.fromJson(json, QuestBook::class.java) ?: return 0
            normalize(b)
            val snap = if (b.date == today) todaySnap(b) else b.days[today]
            if (snap == null) return 0
            var n = 0
            if (snap.recallDone) n += 20
            n += snap.gradedIds.distinct().size
            n += snap.quests.sumOf { it.progress.coerceAtLeast(0) }
            n += snap.quests.count { it.done }
            n
        } catch (_: Exception) {
            0
        }
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val today = TodayTtsStore.todayKey()
        fun parse(json: String): QuestBook {
            if (json.isBlank()) return QuestBook(today)
            return try {
                val b = gson.fromJson(json, QuestBook::class.java) ?: QuestBook(today)
                normalize(b)
                b
            } catch (_: Exception) {
                QuestBook(today)
            }
        }
        val a = parse(localJson)
        val b = parse(cloudJson)
        snapshot(a)
        snapshot(b)
        val days = mutableMapOf<String, QuestDaySnap>()
        (a.days.keys + b.days.keys).forEach { k ->
            days[k] = mergeDay(a.days[k], b.days[k])
        }
        val graded = linkedSetOf<String>()
        val quests = linkedMapOf<String, DailyQuest>()
        var recallDone = false
        fun takeQuest(q: DailyQuest) {
            val key = q.type + "|" + q.cardId
            val old = quests[key]
            quests[key] = if (old == null) q.copy() else pickQuest(old, q, preferFresh = true)
        }
        fun absorbToday(book: QuestBook) {
            if (TodayTtsStore.canonKey(book.date) == today) {
                book.quests.forEach(::takeQuest)
                graded.addAll(book.gradedIds)
                if (book.recallDone) recallDone = true
            }
            book.days[today]?.let { snap ->
                snap.quests.forEach(::takeQuest)
                graded.addAll(snap.gradedIds)
                if (snap.recallDone) recallDone = true
            }
        }
        absorbToday(a)
        absorbToday(b)
        days[today] = mergeDay(
            days[today],
            QuestDaySnap(
                date = today,
                recallDone = recallDone,
                quests = quests.values.map { it.copy() }.toMutableList(),
                gradedIds = graded.toMutableList()
            )
        )
        val backlog = compactBacklog(a.backlog + b.backlog)
        return gson.toJson(
            QuestBook(
                date = today,
                gradedIds = graded.toMutableList(),
                quests = quests.values.toMutableList(),
                recallDone = recallDone,
                days = days,
                backlog = backlog.toMutableList(),
                retrainIds = (a.retrainIds + b.retrainIds).distinct().toMutableList()
            )
        )
    }

    private fun mergeDay(da: QuestDaySnap?, db: QuestDaySnap?): QuestDaySnap {
        if (da == null) return db!!
        if (db == null) return da
        val quests = linkedMapOf<String, DailyQuest>()
        val live = da.date == TodayTtsStore.todayKey() || db.date == TodayTtsStore.todayKey()
        (da.quests + db.quests).forEach { q ->
            val key = q.type + "|" + q.cardId
            val old = quests[key]
            quests[key] = if (old == null) q.copy() else pickQuest(old, q, preferFresh = live)
        }
        return QuestDaySnap(
            date = da.date.ifBlank { db.date },
            recallDone = da.recallDone || db.recallDone,
            quests = quests.values.map { it.copy() }.toMutableList(),
            gradedIds = (da.gradedIds + db.gradedIds).distinct().toMutableList()
        )
    }
}
