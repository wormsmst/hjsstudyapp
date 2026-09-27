package com.example.adminmemo

import android.app.Activity
import android.content.Context
import android.content.Intent

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

val GRADE_ORDER = listOf("A++", "A+", "A", "B", "C", "기타")

fun sortedGradeTiers(tiers: Collection<String>): List<String> {
    return tiers.sortedBy { GRADE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
}

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
    if (cards.isEmpty()) {
        return Card(id = "dummy", type = "concept", subject = "기본", title = "기본", topicTitle = "기본 주제", grade = "A+", front = "내용", back = "내용", mnemonics = emptyList())
    }
    val idx = weightedRandomIndex(context, cards)
    if (idx !in cards.indices) return cards.first()
    return cards[idx]
}

/**
 * 모의고사용 논점(topic) count개를 뽑는다.
 * 사무관리론을 제외한 과목:
 * - count >= 5인 경우: 1번(인덱스 0)과 5번(인덱스 4)은 무조건 사례 문제.
 * - count < 5인 경우: 1번(인덱스 0)은 무조건 사례 문제.
 * 그 외의 문제들은 다 약술(서술) 문제.
 */
fun pickExamTopics(context: Context, subject: String, count: Int): List<Card> {
    val all = CardStore.getAllCards(context)
    val mockRaw = MockExamRepository.loadMockExams(context)
    val caseExamsRaw = mockRaw.filter { 
        it.issues.isNotEmpty() || 
        it.title.contains("사례", true) || 
        it.question.contains("사례", true) || 
        it.question.contains("물음", true) || 
        it.question.contains("설문", true) ||
        it.question.contains("문제", true)
    }

    val casePool = caseExamsRaw.map {
        Card(
            id = it.id,
            type = "case",
            subject = it.subject,
            title = it.title,
            topicTitle = it.title,
            front = it.question,
            back = it.explanation,
            grade = "A+",
            mnemonics = emptyList()
        )
    }.shuffled().toMutableList()

    val generalExams = mockRaw.filter { m -> caseExamsRaw.none { c -> c.id == m.id } }.map {
        Card(
            id = it.id,
            type = "exam",
            subject = it.subject,
            title = it.title,
            topicTitle = it.title,
            front = it.question,
            back = it.explanation,
            grade = "A+",
            mnemonics = emptyList()
        )
    }.shuffled().toMutableList()

    val isSamu = subject.contains("사무관리")
    val needsCases = !isSamu && count > 0
    val caseCountNeeded = if (needsCases) {
        if (count >= 5) 2 else 1
    } else {
        0
    }

    val list = arrayOfNulls<Card>(count)

    // 1번 문제 (인덱스 0) 무조건 사례
    if (caseCountNeeded >= 1 && casePool.isNotEmpty()) {
        list[0] = casePool.removeAt(0)
    }

    // 5번 문제 (인덱스 4) 무조건 사례 (count >= 5일 때)
    if (caseCountNeeded >= 2 && count >= 5 && casePool.isNotEmpty()) {
        list[4] = casePool.removeAt(0)
    }

    // 나머지 슬롯은 약술형(exam) 문제로 채우기
    for (i in 0 until count) {
        if (list[i] == null) {
            if (generalExams.isNotEmpty()) {
                list[i] = generalExams.removeAt(0)
            } else if (casePool.isNotEmpty()) {
                list[i] = casePool.removeAt(0)
            } else if (all.isNotEmpty()) {
                list[i] = all.random()
            } else {
                list[i] = Card(id = "dummy_$i", type = "exam", subject = subject, title = "기본 문제", topicTitle = "기본 문제", front = "문제 내용", back = "모범 답안", grade = "A+", mnemonics = emptyList())
            }
        }
    }

    return list.filterNotNull()
}

object QuizPrefs {
    private const val PREFS = "quiz_prefs"
    private const val KEY_GRADES = "selected_grades"

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
const val PURPOSE_HONESTY = "honesty"
const val ALL_SUBJECTS_KEY = "__ALL__"

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

fun getFormattedCardText(card: Card): String {
    val raw = card.back.ifBlank { card.front }
    val reflowed = reflowBody(raw)
    val headingRegex = Regex("^\\d+\\.\\s")
    val lines = reflowed.split("\n")
    val out = mutableListOf<String>()
    for ((i, line) in lines.withIndex()) {
        val isHeading = headingRegex.containsMatchIn(line.trimStart())
        if (i > 0 && isHeading) {
            val prevBlank = out.isNotEmpty() && out.last().isBlank()
            if (!prevBlank) out.add("")
        }
        out.add(line)
    }
    return out.joinToString("\n")
}

fun launchQuizSession(activity: Activity, subject: String, reviewOnly: Boolean) {
    val intent = Intent(activity, QuizSessionActivity::class.java)
    intent.putExtra(EXTRA_REVIEW_ONLY, reviewOnly)
    intent.putExtra(EXTRA_SUBJECT, subject)
    activity.startActivity(intent)
}
