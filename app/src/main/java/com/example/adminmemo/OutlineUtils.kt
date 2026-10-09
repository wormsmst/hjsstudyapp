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
    val keys: List<String>,
    val peekText: String = "",
    val allowGap: Boolean = true
)

private val KEY_STOP = setOf(
    "있다", "없다", "한다", "된다", "되어", "되며", "대한", "경우", "또는", "및", "등",
    "것이다", "것이", "것을", "것은", "그리고", "그러나", "따라서", "위해", "위한", "위하여",
    "통해", "통한", "따라", "따른", "따르면", "같은", "다른", "이러한", "그러한", "관련",
    "이하", "이상", "이내", "해당", "이에", "이는", "이를", "있는", "없는", "하는", "되는",
    "하면", "하여", "하며", "하고", "해서", "즉", "각종", "전반", "대해", "대하여", "관한",
    "관하여", "모든", "각각", "각급", "특정한", "일정한", "단순한", "지속적", "지속적으로",
    "필요한", "포함한", "제외한", "요구하는", "말한다", "의미한다", "목적으로",
    "크게", "매우", "가장", "보다", "또한", "다만", "가능", "가능한",
    "행정기관", "해당기관", "소속기관", "행정업무", "공무상", "국민", "주민", "처리",
    "운영", "업무", "사항", "내용", "부분", "대상", "일반",
    "아니하도록", "않도록", "하도록", "있어야", "있게", "없으며"
)

private val KEY_GENERIC_HEADS = listOf(
    "시스템", "제도", "원칙", "규정", "절차", "방법", "기준", "체계",
    "방안", "원리", "이론", "조직", "위원회", "포털", "네트워크", "플랫폼",
    "카드", "대장", "서식"
)

private val KEY_COMPOUND = Regex(
    "([가-힣]{2,10})[\\s·\\-]*(" + KEY_GENERIC_HEADS.distinct().joinToString("|") + ")"
)

private val KEY_SHORT_KEEP = setOf(
    "처분", "허가", "인가", "특허", "신고", "신청", "청구", "재량", "기속", "하자",
    "무효", "취소", "철회", "청문", "부관", "기한", "부담", "직권", "위법", "부당",
    "소급", "신뢰", "평등", "비례", "손실", "배상", "보상", "소송", "심판", "판결",
    "결정", "명령", "규칙", "조례", "법률", "헌법", "훈령", "예규", "고시", "통첩"
)

private val KEY_TAIL = Regex(
    "(아니하도록|않도록|하도록|하여야만|하여야|해야|한다면|하면서|" +
        "하며|하고|하여|해서|하면|하는|되는|되어|되며|된다|한다|이다|입니다|" +
        "적으로|적인|하지|되지|에게서|으로부터|으로서|에서의|에서는|에서|" +
        "에게|부터|까지|이나|으로|로써|로서|은|는|이|가|을|를|의|에|와|과|도|만|께)$"
)

private fun normalizeKeyword(raw: String): String {
    var w = raw.trim('.', ',', '·', ' ', ')', '(')
    repeat(4) {
        if (w.length < 3) return w
        val next = KEY_TAIL.replace(w, "")
        if (next == w || next.length < 2) return w
        w = next
    }
    return w
}

private fun isJunkKeyword(word: String, headingNorm: String): Boolean {
    val maxLen = if (KEY_GENERIC_HEADS.any { word.endsWith(it) }) 16 else 12
    if (word.length !in 2..maxLen) return true
    if (word in KEY_STOP) return true
    if (word == headingNorm) return true
    if (word.length < 4 && headingNorm.contains(word)) return true
    if (word.endsWith("도록") || word.endsWith("하여야") || word.endsWith("해야")) return true
    if (word.length == 2 && word !in KEY_SHORT_KEEP && !word.contains("법") && !word.contains("령")) return true
    return false
}

private fun keywordScore(word: String, fromChildHeading: Boolean): Int {
    var s = 0
    if (word.startsWith("제") && word.contains("조")) s += 8
    if (word.contains("법") || word.contains("령") || word.contains("규칙") || word.contains("조례")) s += 5
    if (word in KEY_SHORT_KEEP) s += 4
    if (fromChildHeading) s += 3
    if (KEY_GENERIC_HEADS.any { head -> word != head && word.endsWith(head) }) s += 4
    if (word in KEY_GENERIC_HEADS) s -= 5
    s += when {
        word.length >= 6 -> 2
        word.length >= 4 -> 1
        else -> 0
    }
    if (word.endsWith("적")) s -= 2
    return s
}

fun extractMustKeywords(body: String, heading: String, extra: String = ""): List<String> {
    val headingNorm = outlineHeadingText(heading).replace(Regex("[\\s\\d.\\)(·-]"), "")
    fun compounds(src: String): List<String> =
        KEY_COMPOUND.findAll(src).map { m ->
            normalizeKeyword(m.groupValues[1] + m.groupValues[2])
        }.filter { !isJunkKeyword(it, headingNorm) }.toList()
    fun tokens(src: String): List<String> =
        Regex("[가-힣]{2,12}|제\\d+조(?:의\\d+)?|\\d+일")
            .findAll(src)
            .map { normalizeKeyword(it.value) }
            .filter { !isJunkKeyword(it, headingNorm) }
            .distinct()
            .toList()
    val childToks = (compounds(extra) + tokens(extra)).toSet()
    val merged = (compounds(extra) + compounds(body) + tokens(extra) + tokens(body)).distinct()
    val dropBare = KEY_GENERIC_HEADS.filter { head ->
        merged.any { it != head && it.endsWith(head) }
    }.toSet()
    return merged
        .filter { it !in dropBare }
        .sortedWith(
            compareByDescending<String> { keywordScore(it, it in childToks) }
                .thenByDescending { it.length }
        )
        .take(4)
        .map { spacedGenericHead(it) }
}

/** 목차 한 항목의 채점 한 줄. 정의는 가운데, 효과·금지는 끝, 나열은 목록. */
fun extractGradePunchline(body: String, heading: String, extra: String = ""): String {
    val head = outlineHeadingText(heading)
    val rawBody = body.substringBefore("판례")
        .substringBefore("※")
        .trim()
    val src = joinBrokenHangul(rawBody.ifBlank { extra.trim() })
    if (src.isBlank()) {
        val kids = extra.split(Regex("[\\n·,]"))
            .map { outlineHeadingText(it) }
            .filter { it.length in 2..18 }
            .take(3)
        return kids.joinToString(" · ").ifBlank { clipPunch(head, 20) }
    }
    val sentences = punchSentences(src)
    val wantLast = head.contains("효과") || head.contains("금지") || head.contains("제재")
    val first = (if (wantLast) sentences.lastOrNull() else sentences.firstOrNull())
        ?.trim()
        .orEmpty()
        .ifBlank { src }
    val named = Regex("(?:이란|란)\\s*(.+)\$").find(first)
    if (named != null) {
        var mid = named.groupValues[1]
        mid = mid.replace(Regex("(?:을|를)?\\s*(?:말한다|의미한다|뜻한다|것이다|이다)\\.?\$"), "")
        return clipPunch(stripHeadingPrefix(mid, head), 40)
    }
    val listed = first.contains("등") &&
        (first.contains("·") || first.contains(",") || first.contains(" 및 "))
    if (listed) {
        val cut = Regex("(.+?)\\s*등(?:을|를)?\\s*(?:활용|추구|포함|말한다|한다)").find(first)
        val list = cut?.groupValues?.get(1) ?: first.replace(Regex("등(?:을|를)?\\s*.*\$"), "")
        return clipPunch(stripHeadingPrefix(list, head), 40)
    }
    val stripped = stripHeadingPrefix(first, head)
    val effect = Regex(
        "((?:철회|취소|무효|금지|제한|의무|원칙|예외|도달|효력|승낙|구속|청구|배상|보상).{0,24}(?:못한다|아니한다|않는다|생긴다|잃는다|있다|없다|한다))"
    ).find(stripped)
    if (effect != null) return clipPunch(effect.groupValues[1], 40)
    return clipPunch(stripped.ifBlank { first }, 40)
}

private val PUNCH_NO_JOIN = setOf(
    "없는", "있는", "없다", "있다", "위한", "대한", "따라", "통해", "경우", "때", "등", "및", "또는"
)

/** PDF 줄바꿈으로 쪼개진 '해 제권' → '해제권'. '수 없는'은 그대로. */
private fun joinBrokenHangul(text: String): String {
    val joined = text.split('\n').joinToString(" ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    return joined.replace(Regex("(?<=^| )([가-힣]) ([가-힣]+)")) { m ->
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        if (b in PUNCH_NO_JOIN) "$a $b" else a + b
    }
}

private fun punchSentences(src: String): List<String> =
    src.split(Regex("(?<=다\\.)\\s+|(?<=요\\.)\\s+|(?<=것\\.)\\s+|(?<=것이다\\.)\\s+"))
        .map { it.trim() }
        .filter { it.length >= 6 }

private fun stripHeadingPrefix(text: String, heading: String): String {
    var out = text.trim()
    val bits = heading.split(Regex("[\\s·,，、/()「」\\d.\\-]+"))
        .filter { it.length >= 2 }
        .sortedByDescending { it.length }
    for (b in bits) {
        if (out.startsWith(b)) {
            out = out.removePrefix(b).trim()
            break
        }
    }
    out = out.replace(Regex("^(?:이란|란|은|는|이|가|을|를|의)+"), "")
    return out.replace(Regex("\\s+"), " ").trim(' ', ',', '·', ':', '-')
}

private fun clipPunch(text: String, max: Int = 40): String {
    val t = text.replace(Regex("\\s+"), " ").trim(' ', ',', '·')
    if (t.isBlank() || t.length <= max) return t
    val window = t.take(max + 8)
    val ends = listOf("것이다", "것이다.", "하여야 한다", "해야 한다", "못한다", "아니한다", "않는다", "한다.", "한다", "이다", "없다", "있다", "것이다", "것.", "것", "요.", "다.")
    val hit = ends.mapNotNull { end ->
        val i = window.lastIndexOf(end)
        if (i >= 8) i + end.length else null
    }.maxOrNull()
    if (hit != null && hit <= max + 8) return window.take(hit).trim(' ', ',', '·')
    val cut = t.take(max)
    val br = cut.indexOfLast { it == ' ' || it == '·' || it == ',' }
    val prefix = if (br >= 12) cut.take(br) else cut
    return prefix.trim(' ', ',', '·') + "…"
}

private fun spacedGenericHead(word: String): String {
    val head = KEY_GENERIC_HEADS
        .filter { word != it && word.endsWith(it) }
        .maxByOrNull { it.length }
        ?: return word
    return word.dropLast(head.length) + " " + head
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
        "이 장에는 목차 아래 문장이 거의 없어요. 열린 목차와 대조하세요."
    }
}

private fun nodePeekText(node: OutlineNode): String {
    fun block(n: OutlineNode, depth: Int): String {
        if (depth > 3) return ""
        val title = outlineHeadingText(n.label).ifBlank { n.label.trim() }
        val body = n.bodyText.trim()
        val kids = n.children
            .filter { it.level >= 0 && it.label.isNotBlank() }
            .take(8)
            .map { block(it, depth + 1) }
            .filter { it.isNotBlank() }
        return buildString {
            append(title)
            if (body.isNotBlank()) {
                append('\n')
                append(body)
            }
            if (kids.isNotEmpty()) {
                append("\n\n")
                append(kids.joinToString("\n\n"))
            }
        }.trim()
    }
    return block(node, 0)
}

private fun clipPeek(text: String, max: Int = 900): String {
    val t = text.trim()
    if (t.length <= max) return t
    val window = t.take(max)
    val minKeep = max / 2
    val cuts = sequenceOf("\n\n", "다.\n", "다.", "요.", "것.")
        .map { mark ->
            val i = window.lastIndexOf(mark)
            if (i >= minKeep) i + mark.length else -1
        }
        .filter { it > 0 }
    val cut = cuts.maxOrNull() ?: window.length
    return window.take(cut).trimEnd() + "…"
}

fun outlineRecallItems(card: Card): List<RecallCheckItem> {
    val roots = parseOutline(card.back).filter { it.level >= 0 }
    val out = mutableListOf<RecallCheckItem>()
    fun add(node: OutlineNode) {
        val heading = node.label.trim()
        if (heading.isBlank()) return
        val kids = node.children.filter { it.level >= 0 && it.label.isNotBlank() }
        val hasBody = node.bodyText.trim().isNotBlank()
        val title = outlineHeadingText(heading)
        val hint = extractGradePunchline(node.bodyText, heading, "").trim()
            .let { h ->
                if (h.isBlank() || h == title || h.replace(" ", "") == title.replace(" ", "")) "" else h
            }
        out.add(
            RecallCheckItem(
                heading,
                listOf(hint),
                clipPeek(nodePeekText(node)),
                allowGap = hasBody
            )
        )
        kids.take(8).forEach(::add)
    }
    roots.forEach(::add)
    if (out.isEmpty() && card.mnemonic.isNotBlank()) {
        val mnemo = card.mnemonic.trim()
        out.add(RecallCheckItem("두문자  $mnemo", listOf(mnemo), clipPeek(mnemo)))
    }
    return out.distinctBy { it.heading }.take(18)
}

fun caseRecallItemChunks(card: Card): List<List<RecallCheckItem>> {
    return caseRecallStages(card).map { stage ->
        stage.map { line ->
            val raw = line.replace(Regex("^(결론|이유|목차)\\s*"), "").trim()
            val punch = extractGradePunchline(raw, line, "").trim()
                .let { h -> if (h.isBlank() || h == raw) "" else h }
            RecallCheckItem(line, listOf(punch), clipPeek(line))
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
