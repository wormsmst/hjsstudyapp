package com.example.adminmemo

import android.content.Context

/**
 * 비슷한 열거 목록이 있는 장을 묶어, 겹치는 항목만 짧게 대비한다.
 * 예: 비공개「진행중인 재판」≠ 민원 예외「수사·재판·형집행 / 감사착수」
 */
object SimilarTopics {
    private val ALIAS_GROUPS = listOf(
        listOf("비공개대상정보", "민원처리의 예외", "민원처리를 거부할 수 있는 사항"),
    )

    /** 목차 토큰이 달라도 시험에 자주 섞이는 항목. 왼쪽이 A키 카드, 오른쪽이 B키 카드. */
    private val SEEDED_CONTRASTS = listOf(
        SeededPair(
            aKeys = listOf("비공개대상정보"),
            bKeys = listOf("민원처리의 예외", "민원처리를 거부할 수 있는 사항"),
            lines = listOf(
                "진행중인 재판" to "수사·재판·형집행 또는 감사착수",
                "사생활 침해 우려" to "사생활에 관한 사항",
                "국가의 중대한 이익" to "국가기밀·공무상 비밀",
            ),
        ),
    )

    private val STOP = setOf(
        "관한", "사항", "정보", "경우", "또는", "있는", "대한", "및", "등",
        "할", "수", "그", "이", "해당", "다음", "각", "호",
    )

    @Volatile
    private var byId: Map<String, List<String>>? = null

    fun confuseLine(context: Context, card: Card, focusHeading: String? = null): String {
        val custom = card.confuse
        if (custom != null) return if (custom.isBlank()) "" else formatCustom(custom)
        return autoConfuseLine(context, card, focusHeading)
    }

    fun editorText(context: Context, card: Card): String =
        card.confuse ?: autoConfuseLine(context, card)

    private fun autoConfuseLine(context: Context, card: Card, focusHeading: String? = null): String {
        val titles = confuseTitles(context, card)
        if (titles.isEmpty() && seededFor(card).isEmpty()) return ""
        val other = titles.firstOrNull()
            ?: seededFor(card).firstOrNull()?.otherLabel()
            ?: return ""
        val contrasts = contrastLines(context, card, other, focusHeading)
        if (contrasts.isEmpty()) {
            return if (focusHeading != null) "" else "헷갈림  ·  ${withParticle(clipTitle(other))} 다른 목록"
        }
        return buildString {
            append("헷갈림  ·  ").append(clipTitle(other, 28))
            contrasts.forEach { (mine, theirs) ->
                append('\n')
                append(clipTitle(mine, 18))
                append("  ≠  ")
                append(clipTitle(theirs, 22))
            }
        }
    }

    private fun formatCustom(text: String): String {
        val t = text.trim()
        return if (t.startsWith("헷갈림")) t else "헷갈림  ·  $t"
    }

    fun confuseTitles(context: Context, card: Card): List<String> =
        cached(context)[card.id].orEmpty()

    fun invalidate() {
        byId = null
    }

    private fun cached(context: Context): Map<String, List<String>> {
        byId?.let { return it }
        val built = build(CardStore.getAllCards(context).filter { it.type == "concept" })
        byId = built
        return built
    }

    private fun contrastLines(
        context: Context,
        card: Card,
        otherTitle: String,
        focusHeading: String?,
    ): List<Pair<String, String>> {
        val seeded = seededFor(card).flatMap { it.oriented(card) }
        val other = CardStore.getAllCards(context).firstOrNull {
            it.type == "concept" &&
                (it.id != card.id) &&
                (displayTitle(it) == otherTitle || normTitle(displayTitle(it)) == normTitle(otherTitle))
        }
        val auto = if (other != null) autoContrasts(card, other) else emptyList()
        val merged = (seeded + auto).distinctBy { pair ->
            val share = keyTokens(pair.first).intersect(keyTokens(pair.second)).sorted().joinToString()
            share.ifBlank { normTitle(pair.first) + "|" + normTitle(pair.second) }
        }
        val focused = if (focusHeading.isNullOrBlank()) {
            merged
        } else {
            val focusTok = keyTokens(outlineHeadingText(focusHeading))
            merged.filter { (a, b) ->
                keyTokens(a).any { it in focusTok } || keyTokens(b).any { it in focusTok }
            }
        }
        return focused.take(3)
    }

    private fun autoContrasts(mine: Card, other: Card): List<Pair<String, String>> {
        val a = listLeaves(mine)
        val b = listLeaves(other)
        val hits = mutableListOf<Triple<Double, String, String>>()
        for (ha in a) {
            val ta = keyTokens(ha)
            if (ta.isEmpty()) continue
            for (hb in b) {
                val tb = keyTokens(hb)
                val share = ta.intersect(tb)
                if (share.isEmpty()) continue
                val jac = jaccard(ta, tb)
                if (jac < 0.12 && share.none { it.length >= 2 }) continue
                hits.add(Triple(share.size + jac, shortHead(ha), shortHead(hb)))
            }
        }
        return hits.sortedByDescending { it.first }
            .map { it.second to it.third }
            .distinctBy { it.first }
            .take(3)
    }

    private fun listLeaves(card: Card): List<String> {
        fun walk(n: OutlineNode): List<String> {
            if (n.children.isNotEmpty()) return n.children.flatMap { walk(it) }
            val t = outlineHeadingText(n.label).trim()
            return if (t.length >= 6) listOf(t) else emptyList()
        }
        return try {
            parseOutline(card.back).filter { it.level >= 0 }.flatMap { walk(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun shortHead(s: String): String {
        val cut = s.replace(Regex("\\s+"), " ").trim()
        val comma = cut.indexOfFirst { it == ',' || it == '·' || it == '(' }
        val core = if (comma in 8..24) cut.take(comma) else cut
        return clipTitle(core, 22)
    }

    private fun build(cards: List<Card>): Map<String, List<String>> {
        val bags = cards.associate { it.id to headingBag(it) }
        val scores = mutableMapOf<String, MutableList<Pair<String, Double>>>()

        fun consider(a: Card, b: Card, score: Double) {
            if (a.id == b.id || score < 0.28) return
            val ta = displayTitle(a)
            val tb = displayTitle(b)
            if (normTitle(ta) == normTitle(tb)) return
            scores.getOrPut(a.id) { mutableListOf() }.add(tb to score)
            scores.getOrPut(b.id) { mutableListOf() }.add(ta to score)
        }

        for (group in ALIAS_GROUPS) {
            val hits = group.flatMap { key ->
                val nk = normTitle(key)
                cards.filter {
                    val n = normTitle(displayTitle(it))
                    n == nk || n.contains(nk)
                }
            }.distinctBy { it.id }
            for (i in hits.indices) {
                for (j in i + 1 until hits.size) consider(hits[i], hits[j], 1.0)
            }
            for (card in hits) {
                val others = hits.filter { it.id != card.id }
                if (others.isNotEmpty()) continue
                val mine = normTitle(displayTitle(card))
                for (label in group) {
                    if (normTitle(label) == mine || mine.contains(normTitle(label))) continue
                    scores.getOrPut(card.id) { mutableListOf() }.add(label to 1.0)
                }
            }
        }

        val bySubj = cards.groupBy { canonicalizeSubject(it.subject) }
        for ((_, group) in bySubj) {
            val listLike = group.filter { (bags[it.id]?.size ?: 0) >= 5 }
            for (i in listLike.indices) {
                val a = listLike[i]
                val ba = bags[a.id] ?: continue
                for (j in i + 1 until listLike.size) {
                    val b = listLike[j]
                    val bb = bags[b.id] ?: continue
                    if (ba.intersect(bb).size < 3) continue
                    consider(a, b, jaccard(ba, bb))
                }
            }
        }

        return scores.mapValues { (_, list) ->
            list.sortedByDescending { it.second }.map { it.first }.distinct().take(2)
        }
    }

    private fun seededFor(card: Card): List<SeededPair> {
        val n = normTitle(displayTitle(card))
        return SEEDED_CONTRASTS.filter { it.matches(n) }
    }

    private fun displayTitle(card: Card): String =
        card.topicTitle.ifBlank { card.title }.trim()

    private fun headingBag(card: Card): Set<String> =
        outlineRecallLines(card)
            .map { outlineHeadingText(it) }
            .flatMap { tokenize(it) }
            .toSet()

    private fun keyTokens(s: String): Set<String> =
        tokenize(s).filter { it !in STOP }.toSet()

    private fun tokenize(s: String): List<String> =
        s.split(Regex("[\\s·,，、/()（）「」\\[\\]\\d.\\-]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val uni = a.union(b).size.toDouble()
        return if (uni == 0.0) 0.0 else a.intersect(b).size / uni
    }

    private fun normTitle(s: String): String =
        s.replace(Regex("[\\s()（）·.,/\\-「」\\d기출제목]+"), "").lowercase()

    private fun clipTitle(s: String, max: Int = 22): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    private fun withParticle(noun: String): String {
        val last = noun.lastOrNull() ?: return "${noun}와"
        val code = last.code
        if (code !in 0xAC00..0xD7A3) return "${noun}와"
        val hasBatchim = (code - 0xAC00) % 28 != 0
        return if (hasBatchim) "${noun}과" else "${noun}와"
    }

    private data class SeededPair(
        val aKeys: List<String>,
        val bKeys: List<String>,
        val lines: List<Pair<String, String>>,
    ) {
        fun matches(norm: String): Boolean =
            aKeys.any { norm.contains(normTitle(it)) } || bKeys.any { norm.contains(normTitle(it)) }

        fun onA(norm: String): Boolean = aKeys.any { norm.contains(normTitle(it)) }

        fun otherLabel(): String = bKeys.first()

        fun oriented(card: Card): List<Pair<String, String>> {
            val n = normTitle(displayTitle(card))
            return if (onA(n)) lines else lines.map { it.second to it.first }
        }
    }
}
