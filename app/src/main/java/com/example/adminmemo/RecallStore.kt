package com.example.adminmemo

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit

data class RecallItem(
    val cardId: String = "",
    var dueAt: Long = 0L,
    var intervalDays: Int = 0,
    var reps: Int = 0,
    var lapses: Int = 0,
    var recallCount: Int = 0,
    var goodStreak: Int = 0,
    var lastFailKind: Int = 0,
    var lastGradeAt: Long = 0L,
    var lastWeakAt: Long = 0L
)

private data class RecallBook(
    var items: MutableMap<String, RecallItem> = mutableMapOf(),
    var oneSubjectCursor: Int = 0
)

object RecallStore {
    const val GRADE_MISS = 0
    const val GRADE_HALF = 1
    const val GRADE_GOOD = 2
    const val FAIL_NONE = 0
    const val FAIL_STRUCTURE = 1
    const val FAIL_TIP = 2
    const val WEEKDAY_SESSION = 12
    const val WEEKEND_SESSION = 20

    fun isWeekend(now: Long = System.currentTimeMillis()): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val d = cal.get(java.util.Calendar.DAY_OF_WEEK)
        return d == java.util.Calendar.SATURDAY || d == java.util.Calendar.SUNDAY
    }

    fun sessionSize(now: Long = System.currentTimeMillis()): Int =
        if (isWeekend(now)) WEEKEND_SESSION else WEEKDAY_SESSION

    private fun newCardCap(size: Int): Int = if (size >= WEEKEND_SESSION) 6 else 3

    private const val FILE = "recall.json"
    private val gson = Gson()

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): RecallBook {
        val f = file(context)
        if (!f.exists()) return RecallBook()
        return try {
            val book = gson.fromJson(f.readText(Charsets.UTF_8), RecallBook::class.java) ?: RecallBook()
            book.items = book.items ?: mutableMapOf()
            book
        } catch (_: Exception) {
            RecallBook()
        }
    }

    private fun write(context: Context, book: RecallBook) {
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
        AppPrefs.setLocalSyncTimestamp(context, System.currentTimeMillis())
        FirebaseSyncManager.notifyProgressChanged(context)
    }

    fun lapses(context: Context, cardId: String): Int =
        read(context).items[cardId]?.lapses ?: 0

    fun recallCount(context: Context, cardId: String): Int {
        val item = read(context).items[cardId] ?: return 0
        return if (item.recallCount > 0) item.recallCount else item.reps + item.lapses
    }

    fun allRecallCounts(context: Context): Map<String, Int> {
        return read(context).items.mapValues { (_, item) ->
            if (item.recallCount > 0) item.recallCount else item.reps + item.lapses
        }
    }

    fun lastFailKind(context: Context, cardId: String): Int =
        read(context).items[cardId]?.lastFailKind ?: FAIL_NONE

    fun warmupCards(context: Context, limit: Int = 3): List<Card> {
        val now = System.currentTimeMillis()
        val window = TimeUnit.DAYS.toMillis(14)
        val book = read(context)
        val byId = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() }
            .associateBy { it.id }
        val weak = book.items.values
            .filter { it.lastWeakAt in (now - window)..now }
            .sortedByDescending { it.lastWeakAt }
            .mapNotNull { byId[it.cardId] }
            .distinctBy { it.id }
        if (weak.size >= limit) return weak.take(limit)
        val taken = weak.map { it.id }.toSet()
        val extra = byId.values
            .filter { it.id !in taken }
            .filter { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) <= 2 }
            .filter { recallCount(context, it.id) > 0 }
            .sortedBy { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) }
        return (weak + extra).distinctBy { it.id }.take(limit)
    }

    fun weakStudyCards(context: Context, limit: Int = 40): List<Card> {
        val now = System.currentTimeMillis()
        val window = TimeUnit.DAYS.toMillis(14)
        val book = read(context)
        val byId = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
            .associateBy { it.id }
        val seen = linkedSetOf<String>()
        fun add(id: String) {
            if (id.isNotBlank() && id in byId) seen.add(id)
        }
        DailyQuestStore.retrainIds(context).forEach(::add)
        DailyQuestStore.writeCardIds(context).forEach(::add)
        DailyQuestStore.todayQuests(context).forEach { add(it.cardId) }
        book.items.values
            .filter { it.lastWeakAt in (now - window)..now }
            .sortedWith(
                compareByDescending<RecallItem> { it.lastFailKind == FAIL_STRUCTURE }
                    .thenByDescending { it.lastWeakAt }
            )
            .forEach { add(it.cardId) }
        byId.values
            .filter { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) <= 2 }
            .filter { recallCount(context, it.id) > 0 }
            .sortedBy { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) }
            .forEach { add(it.id) }
        val ordered = seen.mapNotNull { byId[it] }
        return interleave(ordered, limit)
    }

    fun dueCardIds(context: Context): List<String> {
        val now = System.currentTimeMillis()
        return read(context).items.values.filter { it.dueAt <= now }.map { it.cardId }
    }

    fun dueCount(context: Context): Int {
        val n = dueCardIds(context).size
        val size = sessionSize()
        return if (n > 0) n.coerceAtMost(size) else pickQueue(context).size
    }

    fun pickQueue(
        context: Context,
        subject: String = ALL_SUBJECTS_KEY,
        period: Int = 0,
        unseenOnly: Boolean = false
    ): List<Card> {
        val size = sessionSize()
        if (unseenOnly) {
            return interleave(unseenThisMonth(context).filter { inScope(it, subject, period) }.take(size), size)
        }
        val now = System.currentTimeMillis()
        val book = read(context)
        val cards = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
            .filter { inScope(it, subject, period) }
        val byId = cards.associateBy { it.id }
        val wrong = WrongNoteStore.cardIds(context)
        val due = book.items.values
            .filter { it.dueAt <= now && it.cardId in byId }
            .sortedWith(
                compareByDescending<RecallItem> { it.lapses >= 3 }
                    .thenBy { it.dueAt }
            )
            .mapNotNull { byId[it.cardId] }
        val seen = due.map { it.id }.toMutableSet()
        val fresh = cards
            .filter { it.id !in seen }
            .sortedWith(
                compareBy(
                    { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) },
                    { if (it.id in wrong) 0 else 1 }
                )
            )
        val needNew = if (due.isEmpty()) size else (size - due.size).coerceIn(0, newCardCap(size))
        return interleave(due.take(size) + fresh.take(needNew), size)
    }

    private fun inScope(card: Card, subject: String, period: Int): Boolean {
        val sub = canonicalizeSubject(card.subject)
        if (period == 1) return sub in StudyProgressStore.EXAM1_SUBJECTS
        if (period == 2) return sub in StudyProgressStore.EXAM2_SUBJECTS
        if (subject.isNotBlank() && subject != ALL_SUBJECTS_KEY) {
            return sub == canonicalizeSubject(subject)
        }
        return true
    }

    fun examSubjects(): List<String> =
        StudyProgressStore.EXAM1_SUBJECTS + StudyProgressStore.EXAM2_SUBJECTS

    fun peekOneSubject(context: Context): String {
        val tickets = oneSubjectTickets(context)
        if (tickets.isEmpty()) return examSubjects().first()
        val book = read(context)
        return tickets[book.oneSubjectCursor.mod(tickets.size)]
    }

    fun takeOneSubject(context: Context): String {
        val tickets = oneSubjectTickets(context)
        val book = read(context)
        if (tickets.isEmpty()) return examSubjects().first()
        val i = book.oneSubjectCursor.mod(tickets.size)
        val picked = tickets[i]
        book.oneSubjectCursor = i + 1
        write(context, book)
        return picked
    }

    private fun oneSubjectTickets(context: Context): List<String> {
        val book = read(context)
        val wrongBySub = WrongNoteStore.all(context)
            .groupingBy { canonicalizeSubject(it.subject) }
            .eachCount()
        val cards = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
        val monthAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        return examSubjects().flatMap { sub ->
            val pool = cards.filter { canonicalizeSubject(it.subject) == sub }
            if (pool.isEmpty()) return@flatMap emptyList()
            var miss = 0
            pool.forEach { card ->
                val item = book.items[card.id] ?: return@forEach
                miss += item.lapses
                if (item.lastWeakAt >= monthAgo) miss += 2
            }
            miss += wrongBySub[sub] ?: 0
            val copies = when {
                miss >= 12 -> 3
                miss >= 5 -> 2
                else -> 1
            }
            List(copies) { sub }
        }
    }

    private fun interleave(cards: List<Card>, size: Int): List<Card> {
        if (cards.size <= 1) return cards
        val buckets = linkedMapOf<String, MutableList<Card>>()
        cards.forEach { buckets.getOrPut(canonicalizeSubject(it.subject)) { mutableListOf() }.add(it) }
        val keys = buckets.keys.toList()
        val out = mutableListOf<Card>()
        var i = 0
        while (out.size < size && buckets.values.any { it.isNotEmpty() }) {
            val b = buckets[keys[i % keys.size]]!!
            if (b.isNotEmpty()) out.add(b.removeAt(0))
            i++
        }
        return out
    }

    fun record(context: Context, card: Card, grade: Int, failKind: Int = FAIL_NONE) {
        val book = read(context)
        val item = book.items[card.id] ?: RecallItem(cardId = card.id)
        val now = System.currentTimeMillis()
        val day = TimeUnit.DAYS.toMillis(1)
        if (item.recallCount <= 0) item.recallCount = item.reps + item.lapses
        item.lastGradeAt = now
        item.recallCount++
        when (grade) {
            GRADE_MISS -> {
                item.lapses++
                item.intervalDays = 1
                item.dueAt = now + TimeUnit.HOURS.toMillis(4)
                item.lastWeakAt = now
                item.goodStreak = 0
                item.lastFailKind = if (failKind == FAIL_NONE) FAIL_STRUCTURE else failKind
                CardStore.addWrong(context, card.id)
            }
            GRADE_HALF -> {
                item.reps++
                item.intervalDays = 3
                item.dueAt = now + 3 * day
                item.lastWeakAt = now
                item.goodStreak = 0
                item.lastFailKind = if (failKind == FAIL_NONE) FAIL_TIP else failKind
            }
            else -> {
                item.reps++
                item.intervalDays = when {
                    item.intervalDays >= 14 -> 30
                    item.intervalDays >= 7 -> 14
                    item.intervalDays >= 3 -> 7
                    else -> 3
                }
                item.dueAt = now + item.intervalDays * day
                item.lastWeakAt = 0L
                item.goodStreak++
                item.lastFailKind = FAIL_NONE
                CardStore.removeWrong(context, card.id)
            }
        }
        val beforeMem = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
        CardStore.applyRecallMemory(context, card, grade, item.goodStreak)
        if (CardStore.getMemoryLevel(context, card.subject, card.topicTitle) > beforeMem) {
            item.goodStreak = 0
        }
        book.items[card.id] = item
        write(context, book)
        DailyQuestStore.noteGraded(context, card.id)
        StudyProgressStore.markTopic(context, card.subject, card.topicTitle)
    }

    fun mergeCloudJson(localJson: String, cloudJson: String): String {
        val a = parse(localJson)
        val b = parse(cloudJson)
        val out = RecallBook()
        (a.items.keys + b.items.keys).forEach { id ->
            val la = a.items[id]
            val lb = b.items[id]
            out.items[id] = when {
                la == null -> lb!!
                lb == null -> la
                else -> RecallItem(
                    cardId = id,
                    dueAt = minOf(la.dueAt, lb.dueAt),
                    intervalDays = minOf(la.intervalDays, lb.intervalDays),
                    reps = maxOf(la.reps, lb.reps),
                    lapses = maxOf(la.lapses, lb.lapses),
                    recallCount = maxOf(countOf(la), countOf(lb)),
                    goodStreak = if (la.lastGradeAt >= lb.lastGradeAt) la.goodStreak else lb.goodStreak,
                    lastFailKind = if (la.lastGradeAt >= lb.lastGradeAt) la.lastFailKind else lb.lastFailKind,
                    lastGradeAt = maxOf(la.lastGradeAt, lb.lastGradeAt),
                    lastWeakAt = maxOf(la.lastWeakAt, lb.lastWeakAt)
                )
            }
        }
        out.oneSubjectCursor = maxOf(a.oneSubjectCursor, b.oneSubjectCursor)
        return gson.toJson(out)
    }

    private fun countOf(item: RecallItem): Int =
        if (item.recallCount > 0) item.recallCount else item.reps + item.lapses

    fun bumpDueNow(context: Context, cardId: String) {
        val book = read(context)
        val item = book.items[cardId] ?: RecallItem(cardId = cardId)
        item.dueAt = System.currentTimeMillis()
        book.items[cardId] = item
        write(context, book)
    }

    fun unseenThisMonth(context: Context): List<Card> {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val book = read(context)
        return CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
            .filter { card ->
                val at = book.items[card.id]?.lastGradeAt ?: 0L
                at < start
            }
            .sortedWith(
                compareBy(
                    { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) },
                    { canonicalizeSubject(it.subject) }
                )
            )
    }

    fun curveReviewCards(context: Context): List<Pair<Card, String>> {
        val now = System.currentTimeMillis()
        val byId = CardStore.getAllCards(context)
            .filter { it.type == "concept" }
            .associateBy { it.id }
        val book = read(context)
        return book.items.values
            .filter { it.lastWeakAt > 0L }
            .sortedByDescending { it.lapses }
            .mapNotNull { item ->
                val days = calendarDaysBetween(item.lastWeakAt, now)
                val label = CURVE_DAYS[days] ?: return@mapNotNull null
                val card = byId[item.cardId] ?: return@mapNotNull null
                card to label
            }
    }

    private fun calendarDaysBetween(from: Long, to: Long): Int {
        val a = java.util.Calendar.getInstance().apply {
            timeInMillis = from
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val b = java.util.Calendar.getInstance().apply {
            timeInMillis = to
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return ((b.timeInMillis - a.timeInMillis) / TimeUnit.DAYS.toMillis(1)).toInt()
    }

    private val CURVE_DAYS = mapOf(
        1 to "어제 미흡",
        3 to "3일 전 미흡",
        7 to "일주일 전 미흡",
        14 to "2주 전 미흡",
        30 to "한 달 전 미흡"
    )

    private fun parse(json: String): RecallBook {
        if (json.isBlank()) return RecallBook()
        return try {
            val book = gson.fromJson(json, RecallBook::class.java) ?: RecallBook()
            book.items = book.items ?: mutableMapOf()
            book
        } catch (_: Exception) {
            RecallBook()
        }
    }
}
