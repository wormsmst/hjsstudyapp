package com.example.adminmemo

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gemini API에 질문 하나를 보내고 답을 받아오는 아주 단순한 클라이언트. */
object GeminiClient {

    private const val MODEL = "gemini-1.5-flash"

    /** callback은 백그라운드 스레드에서 호출된다 — UI 갱신은 호출부에서 runOnUiThread로 감싸야 한다. */
    fun ask(apiKey: String, question: String, contextText: String, callback: (answer: String?, error: String?) -> Unit) {
        Thread {
            try {
                val prompt = "다음은 학습 자료의 일부입니다:\n\n\"$contextText\"\n\n" +
                    "위 내용과 관련해서 아래 질문에 한국어로, 학생이 이해하기 쉽게 간결히 답해주세요.\n\n질문: $question"

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 25000

                val body = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", prompt))
                            )
                        )
                    )
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (code !in 200..299) {
                    callback(null, "요청 실패 (코드 $code)\n${responseText.take(300)}")
                    return@Thread
                }

                val json = JSONObject(responseText)
                val text = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")

                if (text.isNullOrBlank()) {
                    callback(null, "답변을 받지 못했어요.\n${responseText.take(300)}")
                } else {
                    callback(text, null)
                }
            } catch (e: Exception) {
                callback(null, "네트워크 오류: ${e.message}")
            }
        }.start()
    }
}
