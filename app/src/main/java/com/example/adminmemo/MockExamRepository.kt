package com.example.adminmemo

import android.content.Context

data class MockExamItem(
    val id: String,
    val subject: String,
    val title: String,
    val question: String,
    val explanation: String,
    val keywords: List<String> = emptyList(),
    val issues: List<String> = emptyList(),
    val topic: String = ""
) {
    fun keywordList(): List<String> =
        (if (keywords.isNotEmpty()) keywords else issues).distinct()
}

object MockExamRepository {
    private const val PREFS = "mock_exam_used"

    fun loadMockExams(context: Context): List<MockExamItem> = MockExamStore.getAll(context)

    /**
     * 과목에 맞는 사례문제를 하나 고른다.
     * 이미 나온 문제는 해당 과목 풀을 다 돌기 전까지 다시 나오지 않는다.
     * cards.json의 "민법-계약법"과 mock_exams.json의 "민법"처럼 이름이 달라도 맞춘다.
     */
    fun toRecallCard(item: MockExamItem): Card = Card(
        id = "case_${item.id}",
        type = "concept",
        subject = item.subject,
        title = item.title.ifBlank { "사례문제" },
        topicTitle = item.title.ifBlank { "사례문제" },
        grade = "",
        front = item.question,
        back = item.explanation,
        mnemonics = item.keywordList()
    )

    fun pickCaseQueue(context: Context, subject: String, period: Int, size: Int): List<Card> {
        val all = loadMockExams(context).filter {
            it.question.isNotBlank() && it.explanation.isNotBlank()
        }
        val scoped = all.filter { item ->
            val sub = canonicalizeSubject(item.subject)
            when (period) {
                1 -> sub in StudyProgressStore.EXAM1_SUBJECTS
                2 -> sub in StudyProgressStore.EXAM2_SUBJECTS
                else -> subject == ALL_SUBJECTS_KEY || sub == canonicalizeSubject(subject)
            }
        }
        val pool = (if (scoped.isEmpty()) all else scoped).shuffled()
        return pool.take(size.coerceAtLeast(1)).map { toRecallCard(it) }
    }

    fun pickCase(context: Context, subject: String): MockExamItem? {
        val all = loadMockExams(context).filter {
            subjectsMatch(subject, it.subject) && it.question.isNotBlank()
        }
        if (all.isEmpty()) return null

        val key = "used_${normalizeSubject(subject)}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val used = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        var pool = all.filter { it.id !in used }
        if (pool.isEmpty()) {
            used.clear()
            pool = all
        }
        val picked = pool.random()
        used.add(picked.id)
        prefs.edit().putStringSet(key, used).apply()
        return picked
    }

    private fun subjectsMatch(a: String, b: String): Boolean =
        canonicalizeSubject(a) == canonicalizeSubject(b)

    private fun normalizeSubject(raw: String): String = canonicalizeSubject(raw)
}
