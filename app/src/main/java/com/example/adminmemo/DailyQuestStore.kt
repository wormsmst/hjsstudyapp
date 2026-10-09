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

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): QuestBook {
        val f = file(context)
        if (!f.exists()) return QuestBook(TodayTtsStore.todayKey())
        return try {
            val book = gson.fromJson(f.readText(Charsets.UTF_8), QuestBook::class.java) ?: QuestBook()
            normalize(book)
            val originalDate = book.date
            val rolled = roll(book)
            if (rolled.date != originalDate) writeFile(context, rolled, notify = false)
            rolled
        } catch (_: Exception) {
            QuestBook(TodayTtsStore.todayKey())
        }
    }

    private fun normalize(book: QuestBook) {
        book.gradedIds = book.gradedIds ?: mutableListOf()
        book.quests = book.quests ?: mutableListOf()
        book.days = book.days ?: mutableMapOf()
        book.backlog = book.backlog ?: mutableListOf()
        book.retrainIds = book.retrainIds ?: mutableListOf()
        book.quests.removeAll { it.type == TYPE_LISTEN }
        book.backlog.removeAll { it.type == TYPE_LISTEN }
        book.days.values.forEach { snap ->
            snap.quests = snap.quests ?: mutableListOf()
            snap.gradedIds = snap.gradedIds ?: mutableListOf()
            snap.quests.removeAll { it.type == TYPE_LISTEN }
        }
    }

    private fun roll(book: QuestBook): QuestBook {
        val today = TodayTtsStore.todayKey()
        snapshot(book)
        if (book.date == today) return book
        val undone = book.quests.filter { !it.done && it.type != TYPE_LISTEN }
        return QuestBook(
            date = today,
            days = book.days,
            backlog = compactBacklog(undone + book.backlog),
            retrainIds = mutableListOf()
        )
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
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
        if (notify) {
            AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
            FirebaseSyncManager.notifyProgressChanged(context)
        }
    }

    fun recallDoneToday(context: Context): Boolean = read(context).recallDone

    fun everRecallDone(context: Context): Boolean {
        val book = read(context)
        return book.recallDone || book.days.values.any { it.recallDone }
    }

    fun todayQuests(context: Context): List<DailyQuest> = read(context).quests

    fun openQuests(context: Context): List<DailyQuest> = todayQuests(context).filter { !it.done }

    fun backlog(context: Context): List<DailyQuest> = read(context).backlog.filter { !it.done }

    fun daySnap(context: Context, date: String): QuestDaySnap? {
        val book = read(context)
        if (date == book.date) return todaySnap(book)
        return book.days[date]
    }

    fun monthSnaps(context: Context, year: Int, month1: Int): List<QuestDaySnap> {
        val prefix = "$year-$month1-"
        val book = read(context)
        val out = mutableListOf<QuestDaySnap>()
        book.days.forEach { (k, v) -> if (k.startsWith(prefix) || monthKeyMatch(k, year, month1)) out.add(v) }
        if (book.date.let { monthKeyMatch(it, year, month1) }) {
            out.removeAll { it.date == book.date }
            out.add(todaySnap(book))
        }
        return out.distinctBy { it.date }
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

    fun clearRetrain(context: Context) {
        val book = read(context)
        if (book.retrainIds.isEmpty()) return
        book.retrainIds.clear()
        write(context, book)
    }

    fun failHint(kind: Int): String = when (kind) {
        RecallStore.FAIL_TIP -> "단어만 안 나옴 · 제목만 보고 막힌 문장"
        RecallStore.FAIL_STRUCTURE -> "목차부터 다시 · 제목만 보고 전부 쓰기"
        else -> "제목만 보고 목차·문장"
    }

    fun addDueCurveQuests(context: Context) {
        val book = read(context)
        if (!book.recallDone) return
        val used = book.quests.count { !it.done }
        val room = (todayQuestBudget(context) - used)
            .coerceAtMost(curveCap(context))
            .coerceAtLeast(0)
        if (room <= 0) return
        val have = (book.quests + book.backlog).map { it.cardId }.toMutableSet()
        val pairs = RecallStore.curveReviewCards(context)
        val labelBy = pairs.associate { it.first.id to it.second }
        var added = 0
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
        if (added > 0) write(context, book)
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
        val have = book.quests.map { it.cardId }.toMutableSet()
        fun addFor(card: Card) {
            if (card.id in have) return
            val hint = "보강 · ${failHint(kindOf(card))}"
            book.quests.add(
                if (isSeriousCard(context, card, missCount = 1, selfMiss = selfMissByCard[card.id] ?: 0))
                    bodyQuest(book.date, card, hint = "보강 · $HINT_BODY")
                else writeQuest(book.date, card, target = 1, hint = hint)
            )
            have.add(card.id)
        }
        miss.forEach(::addFor)
        half.forEach(::addFor)
        val extraIds = (miss + half).map { it.id }
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
        book.recallDone = true
        val lastByCard = results
            .groupBy { it.first.id }
            .map { (_, rows) -> rows.last() }
        val missAll = lastByCard.filter { it.second == RecallStore.GRADE_MISS }.map { it.first }
            .distinctBy { it.id }
        val miss = pickByChance(context, missAll, missWriteCap(context))
        val half = pickByChance(
            context,
            lastByCard.filter { it.second == RecallStore.GRADE_HALF }.map { it.first }
                .distinctBy { it.id }
                .filter { h -> miss.none { it.id == h.id } },
            halfWriteCap(context)
        )
        fun kindOf(card: Card) = failKindByCard[card.id]
            ?: RecallStore.lastFailKind(context, card.id)
        val rankedSerious = pickByChance(
            context,
            missAll.sortedWith(
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
        },
            bodyCap(context)
        )
        val seriousIds = rankedSerious.map { it.id }.toSet()
        val quests = mutableListOf<DailyQuest>()
        rankedSerious.forEach { card ->
            quests.add(bodyQuest(book.date, card, hint = "목차부터 다시 · $HINT_BODY"))
        }
        miss.forEach { card ->
            if (card.id in seriousIds) return@forEach
            quests.add(writeQuest(book.date, card, target = 1, hint = failHint(kindOf(card))))
        }
        half.forEach { card ->
            if (card.id in seriousIds) return@forEach
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
            if (quests.size >= budget) return@forEach
            quests.add(q)
            have.add(q.cardId)
        }
        book.quests = quests
        book.retrainIds = (miss + half + rankedSerious).map { it.id }
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

    /** 오늘 인출 장수의 약 1/3. 휴식일은 2장. */
    fun todayQuestBudget(context: Context): Int {
        val n = StudyLoadStore.todayCards(context)
        if (n <= 0) return 2
        return (n / 3).coerceIn(3, 8)
    }

    private fun missWriteCap(context: Context): Int =
        ((todayQuestBudget(context) + 1) / 2).coerceAtLeast(1)

    private fun halfWriteCap(context: Context): Int =
        todayQuestBudget(context) / 4

    private fun bodyCap(context: Context): Int =
        (todayQuestBudget(context) / 4).coerceAtLeast(1)

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
            if (old == null || q.progress > old.progress) quests[key] = q
        }
        fun absorbToday(book: QuestBook) {
            if (book.date == today) {
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
        (da.quests + db.quests).forEach { q ->
            val key = q.type + "|" + q.cardId
            val old = quests[key]
            if (old == null || q.progress > old.progress) quests[key] = q
        }
        return QuestDaySnap(
            date = da.date.ifBlank { db.date },
            recallDone = da.recallDone || db.recallDone,
            quests = quests.values.map { it.copy() }.toMutableList(),
            gradedIds = (da.gradedIds + db.gradedIds).distinct().toMutableList()
        )
    }
}
