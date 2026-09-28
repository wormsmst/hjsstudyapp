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
        return cards.sortedWith(compareBy({ numSortKey(it.num).first }, { numSortKey(it.num).second }))
    }
    val indexMap = order.withIndex().associate { (i, id) -> id to i }
    return cards.sortedWith(compareBy(
        { indexMap[it.id] ?: Int.MAX_VALUE },
        { numSortKey(it.num).first },
        { numSortKey(it.num).second }
    ))
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
    val idx = weightedRandomIndex(context, cards)
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
const val PURPOSE_EXAM = "exam"
const val ALL_SUBJECTS_KEY = "__ALL__"

/**
 * 퀴즈 풀(pool)에 등급 필터 + 오답 복습 필터를 적용한다.
 * reviewOnly가 true면 CardStore에 기록된 오답 카드로만 좁힌다.
 */
fun applyQuizFilters(context: Context, base: List<Card>, reviewOnly: Boolean, subject: String? = null): List<Card> {
    var list = base
    if (subject != null) {
        list = list.filter { it.subject == subject }
    }
    if (reviewOnly) {
        val wrong = CardStore.getWrongIds(context)
        list = list.filter { it.id in wrong }
    }
    val grades = QuizPrefs.getSelectedGrades(context)
    if (grades.isNotEmpty()) {
        list = list.filter { gradeTier(it.grade) in grades }
    }
    return list
}

/** 통합 퀴즈 세션 화면을 실행한다. (문제마다 유형이 바뀌는 방식) */
fun launchQuizSession(activity: Activity, subject: String, reviewOnly: Boolean) {
    val intent = Intent(activity, QuizSessionActivity::class.java)
    intent.putExtra(EXTRA_REVIEW_ONLY, reviewOnly)
    intent.putExtra(EXTRA_SUBJECT, subject)
    activity.startActivity(intent)
}
