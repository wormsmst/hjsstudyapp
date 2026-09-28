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

private fun outlineHeadingContent(line: String): String? {
    for (re in listOf(OUTLINE_MAJOR_RE, OUTLINE_SUB1_RE, OUTLINE_SUB2_RE, OUTLINE_SUB3_RE)) {
        val m = re.find(line)
        if (m != null) return m.groupValues.last().trim()
    }
    return null
}

private fun outlineLevelOf(line: String): Int? = when {
    OUTLINE_MAJOR_RE.containsMatchIn(line) -> 0
    OUTLINE_SUB1_RE.containsMatchIn(line) -> 1
    OUTLINE_SUB2_RE.containsMatchIn(line) -> 2
    OUTLINE_SUB3_RE.containsMatchIn(line) -> 3
    else -> null
}

/**
 * PDF에서 추출한 본문은 화면 폭 기준으로 강제 줄바꿈되어 있어 문장이 어색하게
 * 끊겨 보인다. 목차 표시(1. / 1) / (1) / ① 등)로 시작하는 줄과 빈 줄만 "진짜"
 * 줄바꿈으로 남기고, 나머지는 같은 문단으로 이어붙여서 자연스럽게 다시
 * 줄바꿈되도록 한다. 목차 표시 뒤에 짧은 제목만 있는 경우는 제목 줄로 남긴다.
 */
fun reflowBody(text: String): String {
    val out = mutableListOf<String>()
    var buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(buf.toString())
            buf = StringBuilder()
        }
    }

    for (raw in text.split("\n")) {
        val line = raw.trim()
        if (line.isEmpty()) {
            flush()
            out.add("")
            continue
        }
        val content = outlineHeadingContent(line)
        if (content != null) {
            flush()
            if (content.length <= 15) {
                out.add(line)
            } else {
                buf = StringBuilder(line)
            }
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
        val roots = parseOutline(card.back).filter { it.level >= 0 }

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
        val roots = parseOutline(card.back).filter { it.level >= 0 }
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
    val reflowed = reflowBody(text)
    val roots = mutableListOf<OutlineNode>()
    val stack = mutableListOf<OutlineNode>() // stack[i] = 현재 레벨 i의 열려있는 노드

    for (raw in reflowed.split("\n")) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val lvl = outlineLevelOf(line)
        if (lvl != null) {
            val node = OutlineNode(level = lvl, label = line)
            while (stack.size > lvl) stack.removeAt(stack.size - 1)
            if (stack.isEmpty()) {
                roots.add(node)
            } else {
                stack.last().children.add(node)
            }
            if (stack.size == lvl) stack.add(node) else stack[lvl] = node
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
