package com.example.adminmemo

import kotlin.random.Random

object TtsHostScripts {
    private val opens = listOf(
        "안녕하세요. 오늘의 학습 사연입니다.",
        "저녁 라디오처럼, 오늘 뽑은 사연을 읽어 드릴게요.",
        "잠시 귀만 빌려 주세요. 오늘 학습 사연입니다.",
        "시험지 대신 목소리로, 오늘의 사연을 열어 볼게요.",
        "편안히 들으세요. 오늘 준비된 사연입니다."
    )
    private val firsts = listOf(
        "첫 번째 사연입니다. %s 과목이에요.",
        "문을 여는 첫 편입니다. %s 입니다.",
        "자, 1번 사연. %s 과목입니다.",
        "가장 먼저 들려드릴 이야기는 %s 이에요.",
        "첫 사연, %s 으로 시작할게요."
    )
    private val nexts = listOf(
        "다음 사연입니다. %d번째, %s 과목이에요.",
        "이어서 %d번째 사연입니다. %s 입니다.",
        "페이지를 넘기듯, %d번째. %s 과목이에요.",
        "%d번째 사연으로 갑니다. %s 이에요.",
        "잠깐 숨 고르고, %d번째 %s 사연입니다."
    )
    private val repeats = listOf(
        "같은 사연을, 한 번 더 읽어 드릴게요.",
        "방금 그 편, 한 바퀴 더 돌릴게요.",
        "귀에 남도록, 같은 사연을 다시 읽습니다.",
        "복습하듯, 한 번 더 들려드릴게요.",
        "같은 이야기를 천천히 한 번 더요."
    )
    private val titles = listOf(
        "이번 사연의 제목은, %s, 입니다. 잠시, 목차부터 떠올려 보세요.",
        "제목은 %s 입니다. 종이에 목차를 적어 보셔도 좋아요.",
        "%s. 이 제목만 보고, 어떤 쟁점인지 생각해 보세요.",
        "사연 제목, %s. 설명하시오, 라고 나온다고 치고 떠올려 보세요.",
        "오늘은 %s 입니다. 답을 듣기 전에 한숨 고르세요."
    )
    private val bodies = listOf(
        "이어서, 본문을 천천히 읽어 드릴게요.",
        "자, 모범 목차와 본문입니다.",
        "이제 정답을 읽어 드릴게요.",
        "떠올리셨다면, 본문으로 맞춰 볼게요.",
        "여기부터는 교재처럼 읽어 드릴게요."
    )
    private val closes = listOf(
        "오늘 사연은 여기까지입니다. 내일도 들려드릴게요.",
        "이만 마이크를 내릴게요. 내일 또 만나요.",
        "오늘 분은 여기까지. 종이에 남은 목차만 한번 훑어 보세요.",
        "사연을 접습니다. 내일 같은 시간에 이어갈게요.",
        "여기까지 들으셨으면 충분해요. 내일 또 읽어 드릴게요."
    )
    private val archives = listOf(
        "보관해 둔 사연입니다.",
        "예전에 뽑아 둔 묶음이에요.",
        "지난 날짜 사연을 다시 틀어 볼게요.",
        "보관함에서 꺼낸 사연입니다.",
        "그날의 사연을 다시 읽어 드릴게요."
    )

    fun roll(avoid: Int = -1): Int {
        var s = Random.nextInt(5)
        if (s == avoid) s = (s + 1 + Random.nextInt(4)) % 5
        return s
    }

    fun greet(past: Boolean, count: Int, style: Int, displayDate: String): List<String> {
        val s = style.mod(5)
        return if (!past) {
            listOf(opens[s], "모두 ${count}편 준비했어요.")
        } else {
            listOf(archives[s], "${displayDate}에 뽑은 ${count}편이에요.")
        }
    }

    fun firstCard(subject: String, style: Int): String =
        firsts[style.mod(5)].format(subject)

    fun nextCard(index: Int, subject: String, style: Int): String =
        nexts[style.mod(5)].format(index + 1, subject)

    fun repeatCard(style: Int): String = repeats[style.mod(5)]

    fun titleLine(title: String, style: Int): String =
        titles[style.mod(5)].format(title)

    fun bodyLead(style: Int): String = bodies[style.mod(5)]

    fun closing(style: Int): List<String> {
        val line = closes[style.mod(5)]
        val parts = line.split(". ")
        return if (parts.size >= 2) {
            listOf(parts[0] + ".", parts.drop(1).joinToString(". "))
        } else listOf(line)
    }
}
