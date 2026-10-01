package com.example.adminmemo

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Gemini API에 질문 하나를 보내고 답을 받아오는 아주 단순한 클라이언트. */
object GeminiClient {

    /** 설명 질문용으로 빠른 lite만 쓴다. 큰 Flash는 생각(thinking) 때문에 오래 걸린다. */
    private val MODELS = listOf(
        "gemini-3.1-flash-lite",
        "gemini-flash-lite-latest"
    )

    @Volatile
    private var workingModel: String? = null

    /** callback은 백그라운드 스레드에서 호출된다 — UI 갱신은 호출부에서 runOnUiThread로 감싸야 한다. */
    fun ask(apiKey: String, question: String, contextText: String, callback: (answer: String?, error: String?) -> Unit) {
        Thread {
            try {
                val prompt = "학습 자료:\n${contextText.take(2200)}\n\n질문: $question\n한국어로 짧게 답해."

                val cached = workingModel
                val models = if (cached != null && cached in MODELS) {
                    listOf(cached) + MODELS.filter { it != cached }
                } else {
                    MODELS
                }

                var lastError: String? = null
                var sawBusy = false
                for (model in models) {
                    val result = requestOnce(apiKey, model, prompt)
                    if (result.answer != null) {
                        workingModel = model
                        callback(result.answer, null)
                        return@Thread
                    }
                    lastError = result.error
                    if (result.busy) sawBusy = true
                    if (!result.retryable) {
                        callback(null, result.error)
                        return@Thread
                    }
                }
                callback(
                    null,
                    if (sawBusy) "Gemini 서버가 잠시 혼잡해요. 몇 초 뒤에 다시 질문해주세요."
                    else lastError ?: "사용 가능한 Gemini 모델을 찾지 못했어요"
                )
            } catch (e: Exception) {
                callback(null, "네트워크 오류: ${e.message}")
            }
        }.start()
    }

    private data class Attempt(
        val answer: String?,
        val error: String?,
        val retryable: Boolean,
        val busy: Boolean
    )

    private fun requestOnce(apiKey: String, model: String, prompt: String): Attempt {
        var result = post(apiKey, model, prompt, withThinkingHint = true)
        if (result.error?.contains("코드 400") == true) {
            result = post(apiKey, model, prompt, withThinkingHint = false)
        }
        return result
    }

    private fun post(apiKey: String, model: String, prompt: String, withThinkingHint: Boolean): Attempt {
        val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$encodedKey")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 20000

            val gen = JSONObject().apply {
                put("maxOutputTokens", 512)
                put("temperature", 0.2)
                if (withThinkingHint) {
                    put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
                }
            }
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
                put("generationConfig", gen)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val msg = shortError(responseText)
                val busy = code == 429 || code == 503 ||
                    msg.contains("high demand", ignoreCase = true) ||
                    msg.contains("overloaded", ignoreCase = true)
                val missing = code == 404 ||
                    msg.contains("no longer available", ignoreCase = true) ||
                    msg.contains("not found", ignoreCase = true)
                return Attempt(null, "요청 실패 (코드 $code, $model)\n$msg", busy || missing, busy)
            }

            val text = extractAnswer(responseText)
            return if (text.isNullOrBlank()) {
                Attempt(null, "답변을 받지 못했어요.", false, false)
            } else {
                Attempt(text, null, false, false)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractAnswer(responseText: String): String? {
        val parts = JSONObject(responseText)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: return null
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val t = part.optString("text")
            if (t.isNotBlank()) out.append(t)
        }
        return out.toString().trim().ifBlank { null }
    }

    private fun shortError(raw: String): String {
        return try {
            JSONObject(raw).optJSONObject("error")?.optString("message")?.take(240)
                ?: raw.take(240)
        } catch (_: Exception) {
            raw.take(240)
        }
    }
}
