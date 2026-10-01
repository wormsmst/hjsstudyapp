package com.example.adminmemo

object StudyTips {
    private const val SLOT_MS = 30L * 60L * 1000L

    private val lines = listOf(
        "조금 하는 날이 쌓이면, 안 한 날과 완전히 달라집니다.",
        "오늘은 한 과목만 진득하게. 섞는 건 모의고사 때입니다.",
        "제목만 보고 종이에 목차를 쓰는 시간이, 다시 읽는 시간보다 점수가 됩니다.",
        "이해했다고 남은 게 아닙니다. 덮고 꺼내야 기억입니다.",
        "두문자는 뼈대가 잡힌 뒤, 빈틈에만 붙이세요.",
        "모의고사는 맞히는 연습이 아니라, 빈칸에 쓰는 연습입니다.",
        "사례문제는 결론 문장부터 쓰고, 이유를 뒤로 붙이면 흔들리지 않습니다.",
        "오늘 잘 떠올랐다고 안심하지 마세요. 시험장 문장으로 다시 꺼내 보세요.",
        "자주 보는 건 불안을 달래고, 깊게 넣는 게 시험장에서 살아남습니다.",
        "키워드만 쓰지 말고, 그 키워드로 한 문장을 이으세요.",
        "쉬고 싶을 때는 5분만 더, 그다음 진짜 쉬어도 됩니다.",
        "졸리면 펜을 내려놓고 10분 걷고 오세요. 억지로 읽은 줄은 잘 안 남습니다.",
        "목차 숫자만 가리고 제목을 말해 보면, 실전 약술에 바로 씁니다.",
        "오래 앉아 있는 시간보다, 눈을 떼고 떠올리는 시간이 점수가 됩니다.",
        "시험장에서 이 제목만 보는 장면을 떠올리며 쓰세요.",
        "막힌 장은 본문을 베끼지 말고, 제목만 보고 한 번 더 펼치세요.",
        "모르는 게 보여도 괜찮습니다. 보이는 순간이 공부 시작입니다.",
        "설정에 시험일을 넣어 두면, 하루에 볼 약점 개수가 계산됩니다."
    )

    fun current(nowMillis: Long = System.currentTimeMillis()): String {
        val slot = (nowMillis / SLOT_MS).toInt().let { kotlin.math.abs(it) }
        return lines[slot % lines.size]
    }

    fun millisUntilNext(nowMillis: Long = System.currentTimeMillis()): Long {
        val rem = nowMillis % SLOT_MS
        return if (rem == 0L) SLOT_MS else SLOT_MS - rem
    }
}
