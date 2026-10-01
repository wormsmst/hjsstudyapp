package com.example.adminmemo

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object TtsVoices {
    const val GOOGLE = "com.google.android.tts"
    const val SAMSUNG = "com.samsung.SMT"

    fun create(context: Context, listener: TextToSpeech.OnInitListener, engine: String?): TextToSpeech {
        return if (engine.isNullOrBlank()) TextToSpeech(context, listener)
        else TextToSpeech(context, listener, engine)
    }

    fun nextEngine(current: String?): String? = when (current) {
        GOOGLE -> SAMSUNG
        SAMSUNG -> ""
        else -> null
    }

    fun score(voice: Voice): Int {
        val n = voice.name.lowercase()
        var s = voice.quality * 8
        if (listOf("neural2", "neural", "wavenet", "studio", "news", "natural").any { it in n }) s += 520
        if ("standard" in n) s += 80
        if (listOf("compact", "lite", "x-sfg", "networktimeout", "pico").any { it in n }) s -= 280
        if (voice.isNetworkConnectionRequired) s += 90
        if (voice.latency <= Voice.LATENCY_NORMAL) s += 20
        return s
    }

    /** 기호를 말로 바꾸면 억양이 덜 끊긴다. */
    fun spoken(text: String): String {
        var s = text
            .replace("·", ", ")
            .replace("ㆍ", ", ")
            .replace("→", ", ")
            .replace("↔", " 와 ")
            .replace("/", ", ")
            .replace("①", "첫째, ")
            .replace("②", "둘째, ")
            .replace("③", "셋째, ")
            .replace("④", "넷째, ")
            .replace("⑤", "다섯째, ")
            .replace(Regex("[*#`_]+"), "")
            .replace(Regex("[\\[\\]【】〈〉《》「」]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (s.isEmpty()) return s
        val last = s.last()
        return if (last in ".,!?。！？、") s else "$s."
    }

    fun korean(tts: TextToSpeech): List<Voice> {
        return (tts.voices ?: emptySet())
            .filter { it.locale.language.equals("ko", true) || it.locale.toLanguageTag().startsWith("ko") }
            .sortedByDescending { score(it) }
    }

    fun bestKorean(tts: TextToSpeech): Voice? = korean(tts).firstOrNull()

    fun label(voice: Voice): String {
        val n = voice.name.lowercase()
        val natural = listOf("neural", "wavenet", "studio", "news", "natural").any { it in n }
        val where = when {
            natural && voice.isNetworkConnectionRequired -> "자연음 · 온라인"
            natural -> "자연음"
            voice.isNetworkConnectionRequired -> "온라인"
            else -> "이 기기"
        }
        val q = if (voice.quality >= Voice.QUALITY_VERY_HIGH) "최고음질"
        else if (voice.quality >= Voice.QUALITY_HIGH) "고음질"
        else ""
        val nick = voice.name.substringAfterLast(":").substringAfterLast("-").take(18)
        return listOf(where, q, nick).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun apply(tts: TextToSpeech, context: Context, radio: Boolean = false) {
        val rate = AppPrefs.getTtsRate(context) * if (radio) 0.93f else 1f
        tts.setSpeechRate(rate.coerceIn(0.65f, 1.3f))
        tts.setPitch(if (radio) 0.92f else 0.97f)
        try {
            tts.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {
        }
        tts.language = Locale.KOREAN
        val voices = tts.voices ?: emptySet()
        val want = AppPrefs.getTtsVoiceName(context)
        val picked = if (want.isNotBlank()) voices.firstOrNull { it.name == want } else bestKorean(tts)
        if (picked != null) tts.voice = picked
    }

    fun openSettings(context: Context) {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
    }
}
