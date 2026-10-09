package com.example.adminmemo

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * PDF에서 자동 추출한 grade 문자열(예: "A+,미기출", "약술형,B")에서
 * 중요도 등급(A++/A+/A/B/C)만 뽑아낸다. 못 찾으면 "기타".
 */
/** "03-2", "03(02)" 같은 PDF 주제 번호를 정렬 가능한 (major, minor) 쌍으로 변환 */
fun numSortKey(num: String): Pair<Int, Int> {
    val m = Regex("^(\\d+)(?:[(\\-](\\d+)\\)?)?").find(num) ?: return 9999 to 0
    val major = m.groupValues[1].toIntOrNull() ?: 9999
    val minor = m.groupValues[2].toIntOrNull() ?: 0
    return major to minor
}

/**
 * 민법1, 민법2, 민법10 처럼 사람이 읽는 숫자 순.
 * 숫자 구간은 자릿수가 달라도 값으로 비교한다 (1 < 2 < 10 < 100).
 */
fun naturalSortKey(s: String): String = buildString {
    val token = Regex("\\d+|\\D+")
    for (m in token.findAll(s.trim())) {
        val t = m.value
        val n = t.toLongOrNull()
        if (n != null) append(n.toString().padStart(20, '0'))
        else append(t.lowercase())
    }
}

fun compareNatural(a: String, b: String): Int = naturalSortKey(a).compareTo(naturalSortKey(b))

/** 같은 과목이 다른 이름(민법 / 민법-계약법)으로 나뉘지 않게 하나로 맞춘다. */
fun canonicalizeSubject(raw: String): String {
    val s = raw.trim()
        return when {
            s.contains("민법") || s == "계약법" -> "민법"
        s.contains("사무관리") -> "사무관리론"
        s.contains("행정사실무") -> "행정사실무법"
        s.contains("행정절차") -> "행정절차론"
        else -> s
    }
}

/** 과목 타일 2×2: 민법·행정절차론 / 사무관리론·행정사실무법 */
private val SUBJECT_TILE_ORDER = listOf("민법", "행정절차론", "사무관리론", "행정사실무법")

fun orderedSubjects(raw: Iterable<String>): List<String> {
    val names = raw.map { canonicalizeSubject(it) }.filter { it.isNotBlank() }.distinct()
    val ranked = SUBJECT_TILE_ORDER.filter { it in names }
    val rest = names.filter { it !in SUBJECT_TILE_ORDER }.sortedWith { a, b -> compareNatural(a, b) }
    return ranked + rest
}

fun gradeTier(grade: String): String {
    val regex = Regex("A\\+\\+|A\\+|A(?![A-Za-z])|B(?![A-Za-z])|C(?![A-Za-z])")
    val m = regex.find(grade)
    return m?.value ?: "기타"
}

/** 등급 표시 순서 정렬용 */
val GRADE_ORDER = listOf("A++", "A+", "A", "B", "C", "기타")

fun sortedGradeTiers(tiers: Collection<String>): List<String> {
    return tiers.sortedBy { GRADE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
}

/** 개념카드(주제) 목록을 정렬한다: 사용자가 지정한 순서가 있으면 그것을, 없으면 PDF 번호순을 따른다. */
fun sortConceptCards(context: Context, cards: List<Card>, subject: String): List<Card> {
    val order = CardStore.getCustomOrder(context, subject)
    if (order.isEmpty()) {
        return cards.sortedWith(
            compareBy<Card>(
                { numSortKey(it.num).first },
                { numSortKey(it.num).second }
            ).thenComparator { a, b -> compareNatural(a.topicTitle, b.topicTitle) }
        )
    }
    val indexMap = order.withIndex().associate { (i, id) -> id to i }
    return cards.sortedWith(compareBy(
        { indexMap[it.id] ?: Int.MAX_VALUE },
        { numSortKey(it.num).first },
        { numSortKey(it.num).second }
    ))
}

fun resolveConceptCard(context: Context, cardId: String): Card? {
    val all = CardStore.getAllCards(context)
    val card = all.firstOrNull { it.id == cardId } ?: return null
    if (card.type == "concept") return card
    return all.firstOrNull {
        it.type == "concept" &&
            it.topicTitle == card.topicTitle &&
            canonicalizeSubject(it.subject) == canonicalizeSubject(card.subject)
    }
}

fun launchConceptDetail(activity: Activity, concept: Card, questId: String = "") {
    val subject = canonicalizeSubject(concept.subject)
    val ids = sortConceptCards(
        activity,
        CardStore.getAllCards(activity).filter {
            it.type == "concept" && canonicalizeSubject(it.subject) == subject
        },
        subject
    ).map { it.id }
    if (ids.isEmpty()) return
    val idx = ids.indexOf(concept.id).let { if (it >= 0) it else 0 }
    val intent = Intent(activity, ContentDetailActivity::class.java)
    intent.putStringArrayListExtra(ContentDetailActivity.EXTRA_IDS, ArrayList(ids))
    intent.putExtra(ContentDetailActivity.EXTRA_INDEX, idx)
    if (questId.isNotBlank()) intent.putExtra(ContentDetailActivity.EXTRA_QUEST_ID, questId)
    activity.startActivity(intent)
}

/** 암기정도가 낮은 주제일수록 더 자주 뽑히도록 가중치를 준 랜덤 선택. (1=거의모름 -> 가중치 5, 5=완벽 -> 가중치 1) */
fun weightedRandomIndex(context: Context, cards: List<Card>): Int {
    if (cards.isEmpty()) return -1
    val weights = cards.map { (6 - CardStore.getMemoryLevel(context, it.subject, it.topicTitle)).coerceIn(1, 5) }
    val total = weights.sum()
    if (total <= 0) return cards.indices.random()
    var r = (0 until total).random()
    for (i in cards.indices) {
        r -= weights[i]
        if (r < 0) return i
    }
    return cards.size - 1
}

fun weightedRandomCard(context: Context, cards: List<Card>): Card {
    val idx = weightedRandomIndex(context, cards).coerceIn(0, cards.lastIndex)
    return cards[idx]
}

/**
 * 모의고사용 논점(topic) count개를 뽑는다. 실제 기출문제(type=="exam")가 과목에
 * 충분히 있으면 그것을 우선 쓰고, 없으면 개념카드(주제) 제목을 논점으로 쓴다.
 * 암기정도가 낮은 주제가 더 잘 뽑히도록 가중치를 준다. 같은 세션에서는 중복 없이 뽑는다.
 */
fun pickExamTopics(context: Context, subject: String, count: Int): List<Card> {
    val all = CardStore.getAllCards(context)
    val scoped = if (subject == ALL_SUBJECTS_KEY) all else all.filter { it.subject == subject }

    val examPool = scoped.filter { it.type == "exam" }
    val conceptPool = scoped.filter { it.type == "concept" }
    val sourcePool = if (examPool.size >= count) examPool else conceptPool

    if (sourcePool.isEmpty()) return emptyList()

    val remaining = sourcePool.toMutableList()
    val picked = mutableListOf<Card>()
    repeat(minOf(count, remaining.size)) {
        val idx = weightedRandomIndex(context, remaining)
        picked.add(remaining.removeAt(idx))
    }
    return picked
}

/** 퀴즈 등급 필터 / 오답 복습 여부 등 사용자 설정을 저장하는 SharedPreferences 래퍼 */
object QuizPrefs {
    private const val PREFS = "quiz_prefs"
    private const val KEY_GRADES = "selected_grades"

    /** 비어있으면 "전체(필터 없음)"을 의미 */
    fun getSelectedGrades(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_GRADES, emptySet()) ?: emptySet()
    }

    fun setSelectedGrades(context: Context, grades: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_GRADES, grades).apply()
    }
}

const val EXTRA_REVIEW_ONLY = "extra_review_only"
const val EXTRA_SUBJECT = "extra_subject"
const val EXTRA_PURPOSE = "extra_purpose"
const val PURPOSE_STUDY = "study"
const val PURPOSE_OUTLINE = "outline"
const val PURPOSE_QUIZ = "quiz"
const val PURPOSE_MANAGE = "manage"
const val PURPOSE_MANAGE_CASE = "manage_case"
const val PURPOSE_EXAM = "exam"
const val PURPOSE_RECALL = "recall"
const val EXTRA_RECALL_PERIOD = "extra_recall_period"
const val EXTRA_RECALL_CASE = "extra_recall_case"
const val EXTRA_RECALL_UNSEEN = "extra_recall_unseen"
const val EXTRA_RECALL_CARD_IDS = "extra_recall_card_ids"
const val EXTRA_RECALL_BODY = "extra_recall_body"
const val EXTRA_RECALL_RETRAIN = "extra_recall_retrain"
const val EXTRA_RECALL_BOOST = "extra_recall_boost"
const val EXTRA_EXAM_PERIOD = "extra_exam_period"
const val ALL_SUBJECTS_KEY = "__ALL__"
const val WEAK_STUDY_KEY = "__WEAK_STUDY__"

/**
 * 퀴즈 풀(pool)에 등급 필터 + 오답 복습 필터를 적용한다.
 * reviewOnly가 true면 CardStore에 기록된 오답 카드로만 좁힌다.
 */
fun applyQuizFilters(context: Context, base: List<Card>, reviewOnly: Boolean, subject: String? = null): List<Card> {
    var list = base
    if (subject != null) {
        val want = canonicalizeSubject(subject)
        list = list.filter { canonicalizeSubject(it.subject) == want }
    }
    if (reviewOnly) {
        val wrong = CardStore.getWrongIds(context)
        list = list.filter { it.id in wrong }
    }
    val grades = QuizPrefs.getSelectedGrades(context)
    if (grades.isNotEmpty()) {
        val filtered = list.filter { gradeTier(it.grade) in grades }
        if (filtered.isNotEmpty()) list = filtered
    }
    return list
}

fun mnemonicTokens(raw: String): List<String> =
    raw.split(Regex("[.\\s·]+")).map { it.trim() }.filter { it.isNotEmpty() }

fun isJunkMnemonic(raw: String): Boolean {
    val m = raw.trim()
    if (m.isEmpty()) return true
    val tokens = mnemonicTokens(m)
    if (tokens.size < 2) return true
    if (m.contains("기출")) return true
    val digitish = tokens.count { t -> t.all { it.isDigit() } || Regex("^\\d+[가-힣]?$").matches(t) }
    if (digitish >= tokens.size - 1) return true
    if (tokens.any { it.equals("x", true) || it == "00" || it == "0" }) return true
    return false
}

fun mnemonicAlignsWithOutline(mnemonic: String, concept: Card): Boolean {
    if (isJunkMnemonic(mnemonic)) return false
    val tokens = mnemonicTokens(mnemonic).filter { t -> !t.all { it.isDigit() } }
    if (tokens.size < 2) return false
    val labels = outlineRecallLines(concept)
        .filter { !it.startsWith("두문자") }
        .map { outlineHeadingText(it) }
        .filter { it.isNotBlank() }
    if (labels.size < 2) return false
    var idx = 0
    var hits = 0
    for (tok in tokens) {
        val pos = labels.drop(idx).indexOfFirst { label -> mnemonicTokenHits(tok, label) }
        if (pos >= 0) {
            hits++
            idx += pos + 1
        }
    }
    val need = maxOf(2, (tokens.size + 1) / 2)
    return hits >= need
}

private fun mnemonicTokenHits(token: String, label: String): Boolean {
    if (token.isEmpty() || label.isEmpty()) return false
    if (label.startsWith(token)) return true
    val first = label.firstOrNull { it in '가'..'힣' } ?: return false
    return token.length == 1 && token[0] == first
}

fun filterMnemonicQuizPool(
    context: Context,
    mnemonicCards: List<Card>,
    concepts: List<Card>
): List<Card> {
    val byTopic = concepts.groupBy {
        canonicalizeSubject(it.subject) + "\t" + it.topicTitle.trim()
    }
    return mnemonicCards.filter { card ->
        if (isJunkMnemonic(card.mnemonic)) return@filter false
        if (CardStore.isLocallyEdited(context, card.id)) return@filter true
        val mates = byTopic[canonicalizeSubject(card.subject) + "\t" + card.topicTitle.trim()].orEmpty()
        mates.any { concept -> mnemonicAlignsWithOutline(card.mnemonic, concept) }
    }
}

fun conceptMnemonicQuizPool(context: Context, concepts: List<Card>): List<Card> =
    concepts.filter { card ->
        card.mnemonic.isNotBlank() &&
            !isJunkMnemonic(card.mnemonic) &&
            (CardStore.isLocallyEdited(context, card.id) || mnemonicAlignsWithOutline(card.mnemonic, card))
    }

/** 퀴즈 대신 해당 과목(또는 오답)을 인출로 연다. */
fun launchQuizSession(activity: Activity, subject: String, reviewOnly: Boolean) {
    val intent = Intent(activity, RecallActivity::class.java)
        .putExtra(EXTRA_SUBJECT, if (reviewOnly) ALL_SUBJECTS_KEY else subject)
        .putExtra(EXTRA_RECALL_PERIOD, 0)
    if (reviewOnly) {
        val ids = WrongNoteStore.cardIds(activity)
        if (ids.isNotEmpty()) intent.putStringArrayListExtra(EXTRA_RECALL_CARD_IDS, ArrayList(ids))
    }
    activity.startActivity(intent)
}
