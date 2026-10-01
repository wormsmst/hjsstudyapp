package com.example.adminmemo

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast

object TtsVoiceUi {
    fun open(activity: Activity, onApplied: () -> Unit = {}) {
        val holder = arrayOfNulls<TextToSpeech>(1)
        holder[0] = TtsVoices.create(activity, { status ->
            val engine = holder[0]
            val ok = engine != null && status == TextToSpeech.SUCCESS &&
                StudyTtsService.koreanVoices(engine).isNotEmpty()
            if (ok) {
                showDialog(activity, engine!!, onApplied)
                return@create
            }
            engine?.shutdown()
            holder[0] = TextToSpeech(activity) { inner ->
                val fallback = holder[0]
                if (fallback == null || inner != TextToSpeech.SUCCESS) {
                    Toast.makeText(activity, "음성 엔진을 열 수 없어요", Toast.LENGTH_SHORT).show()
                    fallback?.shutdown()
                    return@TextToSpeech
                }
                if (StudyTtsService.koreanVoices(fallback).isEmpty()) {
                    Toast.makeText(activity, "한국어 목소리를 찾지 못했어요", Toast.LENGTH_SHORT).show()
                    fallback.shutdown()
                    return@TextToSpeech
                }
                showDialog(activity, fallback, onApplied)
            }
        }, TtsVoices.GOOGLE)
    }

    private fun showDialog(activity: Activity, engine: TextToSpeech, onApplied: () -> Unit) {
        val voices = StudyTtsService.koreanVoices(engine)
        val rates = listOf("천천히" to 0.8f, "보통" to 0.92f, "빠르게" to 1.25f)
        val cueOn = AppPrefs.getTtsCueEnabled(activity)
        val extra = listOf(
            "생각 시간 · ${AppPrefs.getTtsThinkSeconds(activity)}초",
            if (cueOn) "본문 앞 신호음 · 켜짐" else "본문 앞 신호음 · 꺼짐",
            "추천 목소리로 맞추기",
            "고음질 한국어 받기 (시스템)"
        )
        val labels = extra + rates.map { "속도 · ${it.first}" } +
            voices.map { StudyTtsService.voiceLabel(it) }
        Handler(Looper.getMainLooper()).post {
            if (activity.isFinishing) {
                engine.shutdown()
                return@post
            }
            AlertDialog.Builder(activity)
                .setTitle("목소리 · 읽기")
                .setItems(labels.toTypedArray()) { _, which ->
                    when {
                        which == 0 -> showThinkPicker(activity, onApplied)
                        which == 1 -> {
                            AppPrefs.setTtsCueEnabled(activity, !cueOn)
                            Toast.makeText(
                                activity,
                                if (!cueOn) "본문 직전에 짧은 소리를 낼게요" else "신호음을 껐어요",
                                Toast.LENGTH_SHORT
                            ).show()
                            onApplied()
                        }
                        which == 2 -> {
                            AppPrefs.setTtsVoiceName(activity, "")
                            Toast.makeText(activity, "자연음에 가까운 한국어로 맞춰 두었어요", Toast.LENGTH_SHORT).show()
                            onApplied()
                        }
                        which == 3 -> TtsVoices.openSettings(activity)
                        which < extra.size + rates.size -> {
                            val ri = which - extra.size
                            AppPrefs.setTtsRate(activity, rates[ri].second)
                            Toast.makeText(activity, "읽는 속도를 ${rates[ri].first}로 맞춰 두었어요", Toast.LENGTH_SHORT).show()
                            onApplied()
                        }
                        else -> {
                            val voice = voices[which - extra.size - rates.size]
                            AppPrefs.setTtsVoiceName(activity, voice.name)
                            Toast.makeText(activity, "목소리를 바꿨어요", Toast.LENGTH_SHORT).show()
                            onApplied()
                        }
                    }
                }
                .setOnDismissListener { engine.shutdown() }
                .setNeutralButton("반복 ${AppPrefs.getTtsRepeat(activity)}회") { _, _ ->
                    Handler(Looper.getMainLooper()).post { showRepeatPicker(activity, onApplied) }
                }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun showRepeatPicker(activity: Activity, onApplied: () -> Unit) {
        val labels = (1..10).map { if (it == 1) "1회 (반복 없음)" else "${it}회" }.toTypedArray()
        val checked = AppPrefs.getTtsRepeat(activity) - 1
        AlertDialog.Builder(activity)
            .setTitle("같은 카드 반복")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppPrefs.setTtsRepeat(activity, which + 1)
                Toast.makeText(activity, "한 카드를 ${which + 1}번 읽고 다음으로 넘어가요", Toast.LENGTH_SHORT).show()
                onApplied()
                dialog.dismiss()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showThinkPicker(activity: Activity, onApplied: () -> Unit) {
        val secs = listOf(3, 5, 8, 10)
        val checked = secs.indexOf(AppPrefs.getTtsThinkSeconds(activity)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("생각한 뒤 정답")
            .setSingleChoiceItems(secs.map { "${it}초" }.toTypedArray(), checked) { dialog, which ->
                AppPrefs.setTtsThinkSeconds(activity, secs[which])
                Toast.makeText(activity, "문제를 읽고 ${secs[which]}초 쉰 뒤 정답을 들려줘요", Toast.LENGTH_SHORT).show()
                onApplied()
                dialog.dismiss()
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
