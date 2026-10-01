package com.example.adminmemo

/** 목차 트리의 한 노드. level: 0=대목차(1.) 1=중목차(1)) 2=소목차((1)) 3=세부(①/-/·) */
data class OutlineNode(
    val level: Int,
    val label: String,          // 이 줄 전체 텍스트 (마커 포함)
    var bodyText: String = "",  // 이 노드 바로 아래, 하위 목차가 나오기 전의 설명 문장
    val children: MutableList<OutlineNode> = mutableListOf()
)

private val OUTLINE_MAJOR_RE = Regex("^(\\d+\\.\\s)(.*)")
private val OUTLINE_SUB1_RE = Regex("^(\\d+\\)\\s)(.*)")
private val OUTLINE_SUB2_RE = Regex("^(\\(\\d+\\)\\s)(.*)")
private val OUTLINE_SUB3_RE = Regex("^([①②③④⑤⑥⑦⑧⑨⑩]|[-·])\\s?(.*)")
private val OUTLINE_ROMAN_RE = Regex("^([ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[\\.．]\\s)(.*)")

private fun outlineHeadingContent(line: String): String? {
    for (re in listOf(OUTLINE_MAJOR_RE, OUTLINE_SUB1_RE, OUTLINE_SUB2_RE, OUTLINE_SUB3_RE, OUTLINE_ROMAN_RE)) {
        val m = re.find(line)
        if (m != null) return m.groupValues.last().trim()
    }
    return null
}

/** 목차 줄에서 번호 마커를 뺀 제목. */
fun outlineHeadingText(line: String): String =
    outlineHeadingContent(line.trim()) ?: line.trim()

/** 목차 줄에서 숫자 마커만 뽑는다. 예: "1. 의의" → "1." */
fun outlineMarkerOf(line: String): String {
    for (re in listOf(OUTLINE_MAJOR_RE, OUTLINE_ROMAN_RE, OUTLINE_SUB1_RE, OUTLINE_SUB2_RE, OUTLINE_SUB3_RE)) {
        val m = re.find(line.trim())
        if (m != null) return m.groupValues[1].trim()
    }
    return "□"
}

private fun outlineLevelOf(line: String): Int? = when {
    OUTLINE_MAJOR_RE.containsMatchIn(line) || OUTLINE_ROMAN_RE.containsMatchIn(line) -> 0
    OUTLINE_SUB1_RE.containsMatchIn(line) -> 1
    OUTLINE_SUB2_RE.containsMatchIn(line) -> 2
    OUTLINE_SUB3_RE.containsMatchIn(line) -> 3
    else -> null
}

/**
 * PDF에서 추출한 본문은 화면 폭 기준으로 강제 줄바꿈되어 있어 문장이 어색하게
 * 끊겨 보인다. **빈 줄(Enter 두 번)** 은 항상 문단 경계로 두고, 문단 안에서는
 * 목차 줄만 나누고 나머지는 이어 붙인다.
 */
fun normalizeNewlines(text: String): String =
    text.replace("\r\n", "\n").replace("\r", "\n")

fun reflowBody(text: String): String {
    val normalized = normalizeNewlines(text)
    return normalized.split(Regex("\n{2,}"))
        .joinToString("\n\n") { reflowParagraph(it) }
}

/** 직접 고친 본문은 입력한 줄바꿈을 그대로 보여 준다. */
fun displayStudyBody(raw: String, keepTypedBreaks: Boolean): String {
    val text = raw.ifBlank { "본문 내용이 없어요" }
    return if (keepTypedBreaks) normalizeNewlines(text) else unfoldOutlineText(text)
}

/**
 * 사례·추출 본문처럼 목차 마커와 설명이 한 줄에 붙어 있는 글을
 * 목차 줄 / 설명 줄로 나눠 읽기 쉽게 만든다.
 */
fun unfoldOutlineText(text: String): String {
    var s = normalizeNewlines(text)
    s = s.replace(Regex("(?<=제\\d{1,4})\\n+(조)"), "조")
    s = s.replace(Regex("(\\d{4}다\\d+)\\n+(\\d+)"), "$1$2")
    s = s.replace(Regex("(?<=[가-힣.])(?=\\d+\\.\\s[가-힣])"), "\n")
    s = s.replace(Regex("(?<=[가-힣.])(?=\\d+\\)\\s)"), "\n")
    s = s.replace(Regex("(?<=[가-힣.])(?=\\(\\d+\\)\\s)"), "\n")
    s = s.replace(Regex("(?<=[가-힣.])(?=[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮])"), "\n")
    return reflowBody(s)
        .lineSequence()
        .flatMap { splitJammedHeadingLine(it.trim()) }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private val HEADING_TAIL = listOf(
    "논점의 제기", "사안의 검토", "존재할 것", "있을 것", "없을 것", "발생할 것",
    "할 것", "불성립", "이행거절", "요건", "효과", "의의", "성립", "소멸", "검토", "제기"
).sortedByDescending { it.length }

private fun splitJammedHeadingLine(line: String): List<String> {
    if (line.isBlank() || outlineLevelOf(line) == null) return listOf(line)
    val rest = outlineHeadingContent(line) ?: return listOf(line)
    val marker = outlineMarkerOf(line)
    for (tail in HEADING_TAIL) {
        val i = rest.indexOf(tail)
        if (i < 0) continue
        val after = i + tail.length
        if (after < rest.length && rest[after] in '가'..'힣') {
            val title = rest.substring(0, after).trim()
            val body = rest.substring(after).trim()
            return listOf("$marker $title", body)
        }
    }
    val glued = Regex("^(.{2,28}?)([가-힣]{2,}(?:이란|라고|이다|이다\\.|은 |는 |을 |를 ))").find(rest)
    if (glued != null && !rest.take(glued.groupValues[1].length).contains(' ')) {
        val title = glued.groupValues[1].trim()
        val body = rest.substring(title.length).trim()
        if (title.length in 2..20 && body.length >= 6) {
            return listOf("$marker $title", body)
        }
    }
    return listOf(line)
}

private fun reflowParagraph(para: String): String {
    val out = mutableListOf<String>()
    var buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(buf.toString())
            buf = StringBuilder()
        }
    }

    for (raw in para.split("\n")) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val content = outlineHeadingContent(line)
        if (content != null) {
            flush()
            out.add(line)
            continue
        }
        if (buf.isEmpty()) {
            buf.append(line)
        } else {
            buf.append(" ").append(line)
        }
    }
    flush()
    return out.joinToString("\n")
}

/** 문장→목차 퀴즈에서 쓰는 문제 하나: 어떤 문장이 어느 목차(형제들 중 하나)에 속하는지 */
data class SentenceQuizItem(
    val topicTitle: String,
    val cardId: String,
    val parentLabel: String,
    val siblingLabels: List<String>,
    val correctLabel: String,
    val sentence: String
)

/** 개념카드들의 목차 트리를 훑어서, 실제 설명 문장이 붙어있는 목차들을 문제로 뽑아낸다. */
fun buildSentenceQuizItems(cards: List<Card>): List<SentenceQuizItem> {
    val items = mutableListOf<SentenceQuizItem>()

    fun sentencesOf(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(Regex("(?<=\\.)\\s+|\\n"))
            .map { it.trim() }
            .filter { it.length in 8..120 }
    }

    for (card in cards) {
        val roots = try {
            parseOutline(card.back).filter { it.level >= 0 }
        } catch (_: Exception) {
            continue
        }

        fun considerGroup(parentLabel: String, siblings: List<OutlineNode>) {
            if (siblings.size < 2) return
            val labels = siblings.map { it.label }
            for (node in siblings) {
                val sentences = sentencesOf(node.bodyText)
                if (sentences.isNotEmpty()) {
                    items.add(
                        SentenceQuizItem(
                            topicTitle = card.topicTitle,
                            cardId = card.id,
                            parentLabel = parentLabel,
                            siblingLabels = labels,
                            correctLabel = node.label,
                            sentence = sentences.random()
                        )
                    )
                }
            }
        }

        considerGroup(card.topicTitle, roots)
        fun walk(node: OutlineNode) {
            considerGroup(node.label, node.children)
            node.children.forEach { walk(it) }
        }
        roots.forEach { walk(it) }
    }
    return items
}

/** 목차퀴즈에서 쓰는 "형제 목차 묶음" 하나. parentLabel 아래에 siblingLabels가 나란히 있다. */
data class OutlineGroup(
    val parentLabel: String,
    val topicTitle: String,
    val cardId: String,
    val siblingLabels: List<String>
)

/** 개념카드들의 목차 트리에서, 형제가 2개 이상인 묶음을 전부 뽑아낸다 (목차퀴즈 출제용). */
fun buildOutlineGroups(cards: List<Card>): List<OutlineGroup> {
    val groups = mutableListOf<OutlineGroup>()
    for (card in cards) {
        val roots = try {
            parseOutline(card.back).filter { it.level >= 0 }
        } catch (_: Exception) {
            continue
        }
        if (roots.size >= 2) {
            groups.add(OutlineGroup(card.topicTitle, card.topicTitle, card.id, roots.map { it.label }))
        }
        fun walk(node: OutlineNode) {
            if (node.children.size >= 2) {
                groups.add(OutlineGroup(node.label, card.topicTitle, card.id, node.children.map { it.label }))
            }
            node.children.forEach { walk(it) }
        }
        roots.forEach { walk(it) }
    }
    return groups
}
fun parseOutline(text: String): List<OutlineNode> {
    val reflowed = unfoldOutlineText(text)
    val roots = mutableListOf<OutlineNode>()
    val stack = mutableListOf<OutlineNode>() // stack[i] = 현재 레벨 i의 열려있는 노드

    for (raw in reflowed.split("\n")) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val lvl = outlineLevelOf(line)
        if (lvl != null) {
            val node = OutlineNode(level = lvl, label = line)
            while (stack.size > lvl) stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()
            if (parent == null) {
                roots.add(node)
            } else {
                parent.children.add(node)
            }
            if (stack.size <= lvl) {
                stack.add(node)
            } else {
                stack[lvl] = node
            }
        } else {
            val target = stack.lastOrNull()
            if (target != null) {
                target.bodyText = if (target.bodyText.isBlank()) line else target.bodyText + "\n" + line
            } else {
                if (roots.isEmpty() || roots.last().level != -1) {
                    roots.add(OutlineNode(level = -1, label = "", bodyText = line))
                } else {
                    roots.last().bodyText = roots.last().bodyText + "\n" + line
                }
            }
        }
    }
    return roots
}

data class RecallCheckItem(
    val heading: String,
    val keys: List<String>
)

private val KEY_STOP = setOf(
    "있다", "없다", "한다", "된다", "되어", "되며", "대한", "경우", "또는", "및", "등",
    "것이다", "것이", "것을", "것은", "그리고", "그러나", "따라서", "위해", "통해",
    "따라", "같은", "이러한", "관련", "이하", "이상", "해당", "이에", "이는", "이를",
    "있는", "없는", "하는", "되는", "하면", "하면", "또는", "즉"
)

fun extractMustKeywords(body: String, heading: String, extra: String = ""): List<String> {
    val headingNorm = outlineHeadingText(heading).replace(Regex("[\\s\\d.\\)(·-]"), "")
    val text = "$body $extra"
    val words = Regex("[가-힣]{2,12}|제\\d+조(?:의\\d+)?|\\d+일")
        .findAll(text)
        .map { it.value.trim('.', ',', '·', ' ') }
        .filter { it.length in 2..12 }
        .filter { it !in KEY_STOP }
        .filter { w -> w != headingNorm && (w.length >= 4 || !headingNorm.contains(w)) }
        .distinct()
        .toList()
    val ranked = words.sortedWith(
        compareByDescending<String> {
            when {
                it.contains("조") || it.contains("법") -> 3
                it.length >= 5 -> 2
                it.length >= 4 -> 1
                else -> 0
            }
        }.thenByDescending { it.length }
    )
    return ranked.take(4)
}

fun outlineRecallItems(card: Card): List<RecallCheckItem> {
    val roots = parseOutline(card.back).filter { it.level >= 0 }
    val out = mutableListOf<RecallCheckItem>()
    fun add(node: OutlineNode) {
        val heading = node.label.trim()
        if (heading.isBlank()) return
        val childText = node.children.joinToString(" ") { outlineHeadingText(it.label) }
        val keys = extractMustKeywords(node.bodyText, heading, childText)
            .ifEmpty {
                node.children.map { outlineHeadingText(it.label) }
                    .filter { it.length in 2..16 }
                    .take(3)
            }
            .ifEmpty { listOf(outlineHeadingText(heading)).filter { it.isNotBlank() } }
        out.add(RecallCheckItem(heading, keys.distinct().take(4)))
    }
    roots.forEach { root ->
        add(root)
        root.children.take(8).forEach { add(it) }
    }
    if (out.isEmpty() && card.mnemonic.isNotBlank()) {
        val keys = card.mnemonic.split(Regex("[,/·\\s]+")).map { it.trim() }.filter { it.length in 1..12 }.take(4)
        out.add(RecallCheckItem("두문자  ${card.mnemonic.trim()}", keys.ifEmpty { listOf(card.mnemonic.trim()) }))
    }
    return out.distinctBy { it.heading }.take(14)
}

fun caseRecallItemChunks(card: Card): List<List<RecallCheckItem>> {
    return caseRecallStages(card).map { stage ->
        stage.map { line ->
            val keys = when {
                line.startsWith("결론") ->
                    extractMustKeywords(line.removePrefix("결론").trim(), "결론")
                        .ifEmpty { listOf("결론") }
                line.startsWith("이유") -> {
                    val k = line.removePrefix("이유").trim()
                    if (k.isBlank()) listOf("이유") else listOf(k.take(16))
                }
                else -> extractMustKeywords("", line)
                    .ifEmpty { listOf(outlineHeadingText(line).ifBlank { line }.take(16)) }
            }
            RecallCheckItem(line, keys.distinct().take(4))
        }
    }
}
fun outlineRecallLines(card: Card): List<String> = outlineRecallItems(card).map { it.heading }

/** 사례 인출: 결론 → 이유(키워드) → 목차 */
fun caseRecallLines(card: Card): List<String> {
    val exp = card.back
    val conclusion = caseConclusionLine(exp)
    val keywords = card.mnemonics.filter { it.isNotBlank() }
    val reasons = if (keywords.isNotEmpty()) {
        keywords.take(8).map { "이유  $it" }
    } else {
        listOf("이유  요건·효과·사안 적용을 한 문장씩")
    }
    val outline = parseOutline(exp)
        .filter { it.level >= 0 }
        .map { it.label.trim() }
        .filter { it.isNotBlank() }
        .take(8)
        .map { "목차  $it" }
        .ifEmpty { listOf("목차  논점 → 법리 → 사안 검토") }
    return listOf("결론  $conclusion") + reasons + outline
}

fun caseRecallStages(card: Card): List<List<String>> {
    val lines = caseRecallLines(card)
    val conclusion = lines.filter { it.startsWith("결론") }
    val reason = lines.filter { it.startsWith("이유") }
    val outline = lines.filter { it.startsWith("목차") }
    return listOf(conclusion, reason, outline)
}

private fun caseConclusionLine(exp: String): String {
    val idx = exp.indexOf("사안의 검토")
    if (idx >= 0) {
        val line = exp.substring(idx)
            .lineSequence()
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
        if (!line.isNullOrBlank()) return line.take(160)
    }
    val issue = exp.lineSequence()
        .dropWhile { !it.contains("논점") }
        .drop(1)
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
    return (issue ?: exp.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()).take(160)
}

/** 위젯 목록에 넣을 카드 본문 텍스트 */
fun getFormattedCardText(card: Card): String {
    val title = card.topicTitle.ifBlank { card.title }
    return listOf(title, getWidgetBodyText(card))
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

fun getWidgetBodyText(card: Card): String {
    val mnemonicLine = when {
        card.mnemonic.isNotBlank() -> "두문자  ${card.mnemonic}"
        card.mnemonics.isNotEmpty() -> "두문자  ${card.mnemonics.joinToString("  ")}"
        else -> ""
    }
    val body = displayStudyBody(
        card.back.ifBlank { card.front.ifBlank { "본문 내용이 없어요" } },
        keepTypedBreaks = false
    )
    return listOf(mnemonicLine, body)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}
