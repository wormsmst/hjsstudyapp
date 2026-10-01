package com.example.adminmemo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView

private const val GEMINI_MENU_ID = 92001

/**
 * 텍스트를 드래그로 선택했을 때 "✨ Gemini에게 질문" 메뉴가 뜨도록 TextView를 설정한다.
 * contextProvider는 그 순간의 "이 문단이 속한 주제/본문 맥락" 문자열을 돌려준다.
 */
fun selectedTextOf(textView: TextView): String {
    val start = textView.selectionStart
    val end = textView.selectionEnd
    if (start in 0 until end && end <= textView.text.length) {
        return textView.text.subSequence(start, end).toString().trim()
    }
    return ""
}

/** 버튼에서 호출: 드래그 선택한 구간이 있으면 질문 창을 연다. */
fun askGeminiAboutSelection(activity: Activity, textView: TextView, contextProvider: () -> String) {
    val selected = selectedTextOf(textView)
    if (selected.isEmpty()) {
        android.widget.Toast.makeText(
            activity,
            "본문을 길게 눌러 궁금한 문장을 선택한 뒤, 다시 눌러 주세요.",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }
    showGeminiAskDialog(activity, selected, contextProvider())
}

fun enableGeminiSelection(activity: Activity, textView: TextView, contextProvider: () -> String) {
    textView.setTextIsSelectable(true)
    val callback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            menu?.add(0, GEMINI_MENU_ID, 0, "✨ Gemini에게 질문")
                ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = true

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            if (item?.itemId == GEMINI_MENU_ID) {
                val selected = selectedTextOf(textView)
                if (selected.isNotEmpty()) {
                    mode?.finish()
                    showGeminiAskDialog(activity, selected, contextProvider())
                }
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode?) {}
    }
    textView.customSelectionActionModeCallback = callback
    textView.customInsertionActionModeCallback = callback
}

fun showGeminiAskDialog(activity: Activity, selectedText: String, contextText: String) {
    val apiKey = AppPrefs.getGeminiApiKey(activity)
    if (apiKey.isBlank()) {
        AlertDialog.Builder(activity)
            .setTitle("Gemini API 키가 필요해요")
            .setMessage("설정 화면에서 Gemini API 키를 먼저 등록해주세요.")
            .setPositiveButton("설정으로 이동") { _, _ -> activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
            .setNegativeButton("닫기", null)
            .show()
        return
    }

    val view = LayoutInflater.from(activity).inflate(R.layout.dialog_gemini_ask, null)
    val tvSelected = view.findViewById<TextView>(R.id.tvGeminiSelectedText)
    val etQuestion = view.findViewById<EditText>(R.id.etGeminiQuestion)
    val tvAnswer = view.findViewById<TextView>(R.id.tvGeminiAnswer)
    val progress = view.findViewById<View>(R.id.progressGemini)

    val trimmed = selectedText.trim()
    tvSelected.text = "선택한 내용: \"${trimmed.take(60)}${if (trimmed.length > 60) "…" else ""}\""
    etQuestion.setText("이게 무슨 뜻이야?")

    val dialog = AlertDialog.Builder(activity)
        .setTitle("✨ Gemini에게 질문")
        .setView(view)
        .setPositiveButton("질문하기", null)
        .setNegativeButton("닫기", null)
        .create()

    dialog.setOnShowListener {
        val askBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        askBtn.setOnClickListener {
            val question = etQuestion.text.toString().trim()
            if (question.isEmpty()) return@setOnClickListener
            progress.visibility = View.VISIBLE
            tvAnswer.text = ""
            askBtn.isEnabled = false
            val clipped = clipStudyContext(contextText, trimmed)
            GeminiClient.ask(apiKey, question, clipped) { answer, error ->
                activity.runOnUiThread {
                    progress.visibility = View.GONE
                    askBtn.isEnabled = true
                    tvAnswer.text = answer ?: error ?: "알 수 없는 오류가 발생했어요"
                }
            }
        }
    }
    dialog.show()
}

/** 선택한 문장 주변만 보내서 응답을 빠르게 한다. */
private fun clipStudyContext(full: String, selected: String): String {
    val focus = selected.trim()
    if (full.isBlank()) return "[질문 구간]: $focus"
    val idx = full.indexOf(focus)
    val window = if (idx >= 0) {
        val start = (idx - 280).coerceAtLeast(0)
        val end = (idx + focus.length + 520).coerceAtMost(full.length)
        full.substring(start, end)
    } else {
        full.take(900)
    }
    return "$window\n\n[질문 구간]: $focus"
}
