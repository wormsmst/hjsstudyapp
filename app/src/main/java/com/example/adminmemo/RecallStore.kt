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

    fun sessionSize(context: Context, now: Long = System.currentTimeMillis()): Int =
        StudyLoadStore.todayCards(context, now)

    private const val FILE = "recall.json"
    private val gson = Gson()
    @Volatile private var cached: RecallBook? = null

    fun invalidateCache() {
        cached = null
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): RecallBook {
        cached?.let { return it }
        val f = file(context)
        val book = if (!f.exists()) {
            RecallBook()
        } else {
            try {
                val parsed = gson.fromJson(f.readText(Charsets.UTF_8), RecallBook::class.java) ?: RecallBook()
                parsed.items = parsed.items ?: mutableMapOf()
                parsed
            } catch (_: Exception) {
                RecallBook()
            }
        }
        cached = book
        return book
    }

    private fun write(context: Context, book: RecallBook) {
        cached = book
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

    fun lastWeakAt(context: Context, cardId: String): Long =
        read(context).items[cardId]?.lastWeakAt ?: 0L

    fun weakAgoLabel(context: Context, cardId: String): String {
        val at = lastWeakAt(context, cardId)
        if (at <= 0L) return ""
        val days = calendarDaysBetween(at, System.currentTimeMillis())
        return when {
            days <= 0 -> "오늘"
            days == 1 -> "어제"
            else -> "${days}일 전"
        }
    }

    fun lastGradeMarks(context: Context): List<Triple<String, String, Int>> =
        read(context).items.mapNotNull { (id, item) ->
            val cardId = item.cardId.ifBlank { id }
            val day = TodayTtsStore.dayKey(item.lastGradeAt)
            if (cardId.isBlank() || day.isBlank()) return@mapNotNull null
            val n = if (item.recallCount > 0) item.recallCount else item.reps + item.lapses
            Triple(cardId, day, n)
        }

    fun warmupCards(context: Context, limit: Int = 3): List<Card> {
        val now = System.currentTimeMillis()
        val window = TimeUnit.DAYS.toMillis(14)
        val book = read(context)
        val byId = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() }
            .associateBy { it.id }
        val weak = book.items.mapNotNull { (id, item) ->
            if (item.lastWeakAt !in (now - window)..now) return@mapNotNull null
            byId[item.cardId.ifBlank { id }]
        }.sortedByDescending { book.items[it.id]?.lastWeakAt ?: 0L }
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

    fun weakStudyCards(context: Context, limit: Int = 40, mixSubjects: Boolean = true): List<Card> {
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
        book.items.entries
            .map { (id, item) -> item.cardId.ifBlank { id } to item }
            .filter { it.second.lastWeakAt in (now - window)..now }
            .sortedWith(
                compareByDescending<Pair<String, RecallItem>> { it.second.lastWeakAt }
                    .thenByDescending { it.second.lastFailKind == FAIL_STRUCTURE }
            )
            .forEach { add(it.first) }
        DailyQuestStore.todayQuests(context).forEach { add(it.cardId) }
        DailyQuestStore.retrainIds(context).forEach(::add)
        DailyQuestStore.writeCardIds(context).forEach(::add)
        byId.values
            .filter { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) <= 2 }
            .filter { recallCount(context, it.id) > 0 }
            .sortedByDescending { book.items[it.id]?.lastWeakAt ?: 0L }
            .forEach { add(it.id) }
        val ordered = seen.mapNotNull { byId[it] }
        return if (mixSubjects) interleave(ordered, limit) else ordered.take(limit)
    }

    fun boostSubject(context: Context): String? {
        val todayIds = todayGradedIds(context)
        val todaySubs = CardStore.getAllCards(context)
            .filter { it.id in todayIds }
            .map { canonicalizeSubject(it.subject) }
            .toSet()
        val cover = coverageBySubject(context)
        val rest = cover.filter { it.subject !in todaySubs && it.total > 0 }
        return recommendSubject(if (rest.isNotEmpty()) rest else cover)
    }

    fun boostQueue(context: Context, size: Int): List<Card> {
        val want = size.coerceAtLeast(1)
        val subject = boostSubject(context) ?: return emptyList()
        val book = read(context)
        val recalledIds = StudyWeekStore.everRecalledIds(context)
        val blocked = todayGradedIds(context).toMutableSet()
        blocked.addAll(DailyQuestStore.openQuestCardIds(context))
        val scoped = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
            .filter { canonicalizeSubject(it.subject) == subject }
            .filter { it.id !in blocked }
        val unseen = sortLikeStudyList(
            context,
            scoped.filter { recallCount(context, it.id) <= 0 && it.id !in recalledIds }
        )
        val seenIds = unseen.map { it.id }.toSet()
        val weak = scoped.filter { it.id !in seenIds }
            .sortedWith(
                compareBy(
                    { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) },
                    { recallCount(context, it.id) },
                    { -(book.items[it.id]?.lastWeakAt ?: 0L) }
                )
            )
        return (unseen + weak).distinctBy { it.id }.take(want)
    }

    private fun todayGradedIds(context: Context): Set<String> {
        val today = TodayTtsStore.todayKey()
        val out = mutableSetOf<String>()
        read(context).items.forEach { (id, item) ->
            if (TodayTtsStore.dayKey(item.lastGradeAt) == today) {
                out.add(item.cardId.ifBlank { id })
            }
        }
        return out
    }

    fun fadeQueue(context: Context, limit: Int = 8): List<Card> {
        fun hasTree(card: Card) = hasOutlineTree(card.back)
        val weak = weakStudyCards(context, 40).filter(::hasTree)
        if (weak.size >= limit) return weak.take(limit)
        val taken = weak.map { it.id }.toSet()
        val extra = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
            .filter { it.id !in taken }
            .filter(::hasTree)
            .sortedBy { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) }
        return (weak + extra).distinctBy { it.id }.take(limit)
    }

    fun dueCardIds(context: Context): List<String> {
        val now = System.currentTimeMillis()
        return read(context).items.values.filter { it.dueAt <= now }.map { it.cardId }
    }

    fun dueCount(context: Context): Int {
        val n = dueCardIds(context).size
        val size = sessionSize(context)
        return if (n > 0) n.coerceAtMost(size) else size
    }

    fun remainingNow(
        context: Context,
        subject: String = ALL_SUBJECTS_KEY,
        period: Int = 0,
        caseMode: Boolean = false,
        unseen: Boolean = false
    ): Int {
        val snap = RecallSessionStore.load(context)
        if (
            RecallSessionStore.unfinished(snap) &&
            snap != null &&
            RecallSessionStore.isToday(snap) &&
            RecallSessionStore.matches(
                snap, subject, period, caseMode, unseen, false, false, emptyList<String>()
            )
        ) {
            return (snap.queueIds.size - snap.index).coerceAtLeast(0)
        }
        if (caseMode) {
            val size = if (isWeekend()) 6 else 4
            return MockExamRepository.pickCaseQueue(context, subject, period, size).size
        }
        return pickQueue(context, subject, period, unseen).size
    }

    fun remainingOpen(context: Context): Int {
        val snap = RecallSessionStore.load(context)
        if (RecallSessionStore.unfinished(snap) && snap != null && RecallSessionStore.isToday(snap)) {
            return (snap.queueIds.size - snap.index).coerceAtLeast(0)
        }
        return dueCount(context)
    }

    fun pickQueue(
        context: Context,
        subject: String = ALL_SUBJECTS_KEY,
        period: Int = 0,
        unseenOnly: Boolean = false
    ): List<Card> {
        val size = sessionSize(context)
        if (unseenOnly) {
            return interleave(unseenThisMonth(context).filter { inScope(context, it, subject, period) }.take(size), size)
        }
        val now = System.currentTimeMillis()
        val book = read(context)
        val cards = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
            .filter { inScope(context, it, subject, period) }
        val byId = cards.associateBy { it.id }
        val recalledIds = StudyWeekStore.everRecalledIds(context)
        fun isNew(card: Card): Boolean {
            if (card.id in recalledIds) return false
            val item = book.items[card.id] ?: return true
            return countOf(item) <= 0 && item.lastGradeAt <= 0L
        }
        val unseen = sortLikeStudyList(context, cards.filter(::isNew))
        val due = book.items.values
            .filter { it.dueAt <= now && it.cardId in byId && !isNew(byId.getValue(it.cardId)) }
            .sortedWith(
                compareByDescending<RecallItem> { it.lapses >= 3 }
                    .thenBy { it.dueAt }
            )
            .mapNotNull { byId[it.cardId] }
        val weakFill = cards
            .filter { !isNew(it) }
            .sortedWith(
                compareBy(
                    { book.items[it.id]?.dueAt ?: Long.MAX_VALUE },
                    { -(book.items[it.id]?.lapses ?: 0) },
                    { CardStore.getMemoryLevel(context, it.subject, it.topicTitle) }
                )
            )
        val newToday = book.items.values.count { item ->
            TodayTtsStore.dayKey(item.lastGradeAt) == TodayTtsStore.todayKey() &&
                countOf(item) == 1
        }
        val newCap = StudyLoadStore.newPerDay(context)
        val mustNew = (newCap - newToday)
            .coerceAtMost(unseen.size)
            .coerceAtMost(size)
            .coerceAtLeast(0)
        val pickedNew = unseen.take(mustNew)
        val pickedIds = pickedNew.map { it.id }.toSet()
        val room = (size - pickedNew.size).coerceAtLeast(0)
        val dueTake = due.filter { it.id !in pickedIds }.take(room)
        val used = pickedIds + dueTake.map { it.id }
        val pad = weakFill.filter { it.id !in used }.take((size - used.size).coerceAtLeast(0))
        return interleave(pickedNew + dueTake + pad, size)
    }

    private fun inScope(context: Context, card: Card, subject: String, period: Int): Boolean {
        val sub = canonicalizeSubject(card.subject)
        if (period == 1) return sub in StudyProgressStore.periodSubjects(context, 1)
        if (period == 2) return sub in StudyProgressStore.periodSubjects(context, 2)
        if (subject.isNotBlank() && subject != ALL_SUBJECTS_KEY) {
            return sub == canonicalizeSubject(subject)
        }
        return true
    }

    fun examSubjects(context: Context): List<String> {
        val fromCards = CardStore.getSubjects(context)
        return fromCards.ifEmpty {
            StudyProgressStore.EXAM1_SUBJECTS + StudyProgressStore.EXAM2_SUBJECTS
        }
    }

    data class SubjectCoverage(
        val subject: String,
        val unseen: Int,
        val total: Int,
        val weak: Int = 0,
        val dueSoon: Int = 0,
        val belowTarget: Int = 0
    ) {
        val unseenRatio: Float get() = if (total <= 0) 0f else unseen.toFloat() / total
        val weakRatio: Float get() = if (total <= 0) 0f else weak.toFloat() / total
    }

    fun coverageBySubject(context: Context): List<SubjectCoverage> {
        val book = read(context)
        val now = System.currentTimeMillis()
        val soon = now + TimeUnit.HOURS.toMillis(36)
        val cards = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
        val bySub = linkedMapOf<String, MutableList<Card>>()
        cards.forEach { bySub.getOrPut(canonicalizeSubject(it.subject)) { mutableListOf() }.add(it) }
        return bySub.map { (sub, list) ->
            var unseen = 0
            var weak = 0
            var dueSoon = 0
            var belowTarget = 0
            list.forEach { card ->
                val item = book.items[card.id]
                val n = if (item == null) 0
                else if (item.recallCount > 0) item.recallCount else item.reps + item.lapses
                val mem = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
                if (n <= 0) unseen++
                else {
                    if (mem <= 2) weak++
                    if (n < StudyProgressStore.recallTarget(n, mem)) belowTarget++
                }
                val due = item?.dueAt ?: 0L
                if (due in 1..soon) dueSoon++
            }
            SubjectCoverage(sub, unseen, list.size, weak, dueSoon, belowTarget)
        }
    }

    fun recommendSubject(cover: List<SubjectCoverage>): String? {
        val pool = cover.filter { it.total > 0 }
        if (pool.isEmpty()) return null
        val unseenPool = pool.filter { it.unseen > 0 }
        if (unseenPool.isNotEmpty()) {
            return unseenPool.maxWith(
                compareBy<SubjectCoverage> { it.unseenRatio }.thenBy { it.unseen }
            ).subject
        }
        val order = cover.map { it.subject }
        return pool.maxWith(
            compareBy<SubjectCoverage> { it.weakRatio }
                .thenBy { it.weak }
                .thenBy { it.dueSoon }
                .thenBy { it.belowTarget }
                .thenBy { i ->
                    val idx = order.indexOf(i.subject)
                    if (idx < 0) -99 else -idx
                }
        ).subject
    }

    fun peekOneSubject(context: Context): String {
        val tickets = oneSubjectTickets(context)
        if (tickets.isEmpty()) return examSubjects(context).first()
        val book = read(context)
        return tickets[book.oneSubjectCursor.mod(tickets.size)]
    }

    fun takeOneSubject(context: Context): String {
        val tickets = oneSubjectTickets(context)
        val book = read(context)
        if (tickets.isEmpty()) return examSubjects(context).first()
        val i = book.oneSubjectCursor.mod(tickets.size)
        val picked = tickets[i]
        book.oneSubjectCursor = i + 1
        write(context, book)
        return picked
    }

    private fun oneSubjectTickets(context: Context): List<String> {
        val cards = CardStore.getAllCards(context)
            .filter { it.type == "concept" && it.topicTitle.isNotBlank() && it.back.isNotBlank() }
        return examSubjects(context).filter { sub ->
            cards.any { canonicalizeSubject(it.subject) == sub }
        }
    }

    private fun sortLikeStudyList(context: Context, cards: List<Card>): List<Card> {
        if (cards.size <= 1) return cards
        return cards.groupBy { canonicalizeSubject(it.subject) }
            .flatMap { (sub, group) -> sortConceptCards(context, group, sub) }
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

    /** 반쯤: 한 칸만 되돌린다. 30→14→7→3→1. 몇 시간 단위는 완전 실패만. */
    private fun stepBackInterval(days: Int): Int = when {
        days >= 30 -> 14
        days >= 14 -> 7
        days >= 7 -> 3
        else -> 1
    }

    private fun stepUpInterval(days: Int): Int = when {
        days >= 14 -> 30
        days >= 7 -> 14
        days >= 3 -> 7
        else -> 3
    }

    fun record(context: Context, card: Card, grade: Int, failKind: Int = FAIL_NONE) {
        val book = read(context)
        val item = book.items[card.id] ?: RecallItem(cardId = card.id)
        val now = System.currentTimeMillis()
        val day = TimeUnit.DAYS.toMillis(1)
        val prior = if (item.recallCount > 0) item.recallCount else item.reps + item.lapses
        val wasNew = prior <= 0
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
                item.intervalDays = stepBackInterval(item.intervalDays)
                item.dueAt = now + item.intervalDays * day
                item.lastWeakAt = now
                item.goodStreak = 0
                item.lastFailKind = if (failKind == FAIL_NONE) FAIL_TIP else failKind
            }
            else -> {
                item.reps++
                item.intervalDays = stepUpInterval(item.intervalDays)
                if (item.lapses >= 3) item.intervalDays = item.intervalDays.coerceAtMost(7)
                item.dueAt = now + item.intervalDays * day
                item.goodStreak++
                item.lastFailKind = FAIL_NONE
                CardStore.removeWrong(context, card.id)
            }
        }
        if (grade == GRADE_GOOD) {
            DailyQuestStore.dismissOpenQuestsForCard(context, card.id)
        }
        val beforeMem = CardStore.getMemoryLevel(context, card.subject, card.topicTitle)
        CardStore.applyRecallMemory(context, card, grade, item.goodStreak)
        if (CardStore.getMemoryLevel(context, card.subject, card.topicTitle) > beforeMem) {
            item.goodStreak = 0
        }
        book.items[card.id] = item
        write(context, book)
        DailyQuestStore.noteGraded(context, card.id)
        StudyProgressStore.markTopic(
            context,
            card.subject,
            card.topicTitle,
            StudyProgressStore.KIND_RECALL
        )
        StudyWeekStore.noteRecall(context, card.id, card.id.startsWith("case_"), wasNew)
    }

    fun todayVolume(json: String): Int {
        if (json.isBlank()) return 0
        return try {
            val today = TodayTtsStore.todayKey()
            parse(json).items.values.count { TodayTtsStore.dayKey(it.lastGradeAt) == today }
        } catch (_: Exception) {
            0
        }
    }

    fun exportTodayMarks(context: Context): List<TodayRecallMark> =
        exportMarksOnDay(context, TodayTtsStore.todayKey())

    fun exportMarksOnDay(context: Context, date: String): List<TodayRecallMark> {
        if (date.isBlank()) return emptyList()
        return read(context).items.mapNotNull { (id, item) ->
            if (TodayTtsStore.dayKey(item.lastGradeAt) != date) return@mapNotNull null
            val cardId = item.cardId.ifBlank { id }
            if (cardId.isBlank()) return@mapNotNull null
            TodayRecallMark(
                cardId = cardId,
                recallCount = countOf(item),
                reps = item.reps,
                lapses = item.lapses,
                lastGradeAt = item.lastGradeAt
            )
        }
    }

    fun overlayMarks(context: Context, marks: List<TodayRecallMark>) {
        if (marks.isEmpty()) return
        val book = read(context)
        var changed = false
        marks.forEach { m ->
            if (m.cardId.isBlank()) return@forEach
            val item = book.items.getOrPut(m.cardId) { RecallItem(cardId = m.cardId) }
            val nextCount = maxOf(countOf(item), m.recallCount)
            if (nextCount > countOf(item)) {
                item.recallCount = nextCount
                changed = true
            }
            if (m.reps > item.reps) {
                item.reps = m.reps
                changed = true
            }
            if (m.lapses > item.lapses) {
                item.lapses = m.lapses
                changed = true
            }
            if (m.lastGradeAt > item.lastGradeAt) {
                item.lastGradeAt = m.lastGradeAt
                changed = true
            }
        }
        if (!changed) return
        cached = book
        file(context).writeText(gson.toJson(book), Charsets.UTF_8)
    }

    fun isHollowJson(json: String): Boolean {
        if (json.isBlank()) return true
        return try {
            parse(json).items.isEmpty()
        } catch (_: Exception) {
            true
        }
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
                else -> {
                    val newer = if (la.lastGradeAt >= lb.lastGradeAt) la else lb
                    val weakAt = maxOf(la.lastWeakAt, lb.lastWeakAt)
                    RecallItem(
                        cardId = id,
                        dueAt = newer.dueAt,
                        intervalDays = newer.intervalDays,
                        reps = maxOf(la.reps, lb.reps),
                        lapses = maxOf(la.lapses, lb.lapses),
                        recallCount = maxOf(countOf(la), countOf(lb)),
                        goodStreak = newer.goodStreak,
                        lastFailKind = when {
                            la.lastWeakAt > lb.lastWeakAt -> la.lastFailKind
                            lb.lastWeakAt > la.lastWeakAt -> lb.lastFailKind
                            else -> newer.lastFailKind
                        },
                        lastGradeAt = newer.lastGradeAt,
                        lastWeakAt = weakAt
                    )
                }
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
            .filter { it.lastFailKind != FAIL_NONE || it.lastGradeAt <= it.lastWeakAt }
            .sortedByDescending { it.lapses }
            .mapNotNull { item ->
                val days = calendarDaysBetween(item.lastWeakAt, now)
                val label = curveLabel(days) ?: return@mapNotNull null
                val card = byId[item.cardId] ?: return@mapNotNull null
                if (CardStore.getMemoryLevel(context, card.subject, card.topicTitle) >= 4) {
                    return@mapNotNull null
                }
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

    private fun curveLabel(days: Int): String? = when {
        days < 1 -> null
        days <= 1 -> "어제 미흡"
        days <= 4 -> "3일 전 미흡"
        days <= 10 -> "일주일 전 미흡"
        days <= 20 -> "2주 전 미흡"
        days <= 40 -> "한 달 전 미흡"
        else -> null
    }

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
