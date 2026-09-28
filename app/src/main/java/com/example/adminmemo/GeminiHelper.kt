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
fun enableGeminiSelection(activity: Activity, textView: TextView, contextProvider: () -> String) {
    textView.setTextIsSelectable(true)
    textView.customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            menu?.add(0, GEMINI_MENU_ID, 0, "✨ Gemini에게 질문")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            if (item?.itemId == GEMINI_MENU_ID) {
                val start = textView.selectionStart
                val end = textView.selectionEnd
                if (start in 0 until end && end <= textView.text.length) {
                    val selected = textView.text.subSequence(start, end).toString()
                    mode?.finish()
                    showGeminiAskDialog(activity, selected, contextProvider())
                }
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode?) {}
    }
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
            val fullContext = if (contextText.length > 4000) contextText.take(4000) else contextText
            GeminiClient.ask(apiKey, question, "$fullContext\n\n[특히 이 부분]: $trimmed") { answer, error ->
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
