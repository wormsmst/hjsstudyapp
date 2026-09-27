package com.example.adminmemo

data class Card(
    val id: String,
    val type: String,       // "concept" or "mnemonic"
    val subject: String,
    val num: String = "",          // PDF 상의 주제 번호 (정렬용)
    val title: String,
    val topicTitle: String = "",   // 두문자가 속한 주제 제목 (매칭 퀴즈용)
    val mnemonic: String = "",     // 두문자 문자열 자체, 예: "체.상.내.방" (매칭 퀴즈용)
    val grade: String,
    val front: String,
    val back: String,
    val mnemonics: List<String>
)
