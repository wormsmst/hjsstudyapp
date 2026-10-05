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
 * 본문은 저장된 줄바꿈을 그대로 쓴다.
 * (예전 PDF 폭 기준 줄붙임·목차 자동 분리는 자주 틀려서 쓰지 않는다.)
 */
fun normalizeNewlines(text: String): String =
    text.replace("\r\n", "\n").replace("\r", "\n")

fun reflowBody(text: String): String = normalizeNewlines(text)

/** 직접 고친 본문과 추출 본문 모두 입력한 줄바꿈을 그대로 보여 준다. */
fun displayStudyBody(raw: String, keepTypedBreaks: Boolean): String {
    val text = raw.ifBlank { "본문 내용이 없어요" }
    return normalizeNewlines(text)
}

fun unfoldOutlineText(text: String): String = normalizeNewlines(text)

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
    val reflowed = normalizeNewlines(text)
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

fun outlineDepth(roots: List<OutlineNode>): Int {
    var depth = -1
    fun walk(node: OutlineNode) {
        if (node.level >= 0) depth = maxOf(depth, node.level)
        node.children.forEach(::walk)
    }
    roots.forEach(::walk)
    return depth
}

/** throughLevel 미만은 펼치고, 그 레벨은 제목만, 더 아래는 ···. -1이면 제목만. */
fun maskOutline(roots: List<OutlineNode>, throughLevel: Int): List<OutlineNode> {
    if (throughLevel < 0) return emptyList()
    fun copy(node: OutlineNode): OutlineNode {
        val realKids = node.children.filter { it.level >= 0 }
        val kids = when {
            node.level < throughLevel -> realKids.map(::copy).toMutableList()
            realKids.isNotEmpty() -> mutableListOf(OutlineNode(level = node.level + 1, label = "···"))
            else -> mutableListOf()
        }
        return OutlineNode(node.level, node.label, "", kids)
    }
    return roots.filter { it.level >= 0 }.map(::copy)
}

fun fadePromptBody(roots: List<OutlineNode>): String {
    val chunks = mutableListOf<String>()
    fun walk(node: OutlineNode) {
        if (node.level >= 0 && node.bodyText.isNotBlank()) {
            chunks.add("${outlineHeadingText(node.label)}\n${node.bodyText.trim()}")
        }
        node.children.forEach(::walk)
    }
    roots.forEach(::walk)
    return chunks.joinToString("\n\n").ifBlank {
        "이 장에는 목차 아래 문장이 거의 없어요. 열린 목차 가지와 대조하세요."
    }
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
