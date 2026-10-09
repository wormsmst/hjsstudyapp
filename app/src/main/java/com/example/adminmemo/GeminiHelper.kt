package com.example.adminmemo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView

private const val GEMINI_MENU_ID = 92001

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
    captureGeminiSelection(activity, selected, contextProvider())
}

fun captureGeminiSelection(activity: Activity, selected: String, contextText: String) {
    showGeminiAskDialog(activity, selected, contextText)
}

fun enableGeminiSelection(activity: Activity, textView: TextView, contextProvider: () -> String) {
    textView.setTextIsSelectable(true)
    textView.customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            menu?.add(0, GEMINI_MENU_ID, 0, "Gemini에게 질문")
                ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = true

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            if (item?.itemId == GEMINI_MENU_ID) {
                val selected = selectedTextOf(textView)
                if (selected.isNotEmpty()) {
                    (activity as? BaseActivity)?.swallowBackBriefly()
                    captureGeminiSelection(activity, selected, contextProvider())
                    mode?.finish()
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
            .setMessage("설정에서 Gemini API 키를 먼저 넣어 주세요.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .setNegativeButton("닫기", null)
            .show()
        return
    }
    (activity as? BaseActivity)?.openGeminiPanel(selectedText, contextText)
}

/** 선택한 문장 주변만 보내서 응답을 빠르게 한다. */
internal fun clipStudyContext(full: String, selected: String): String {
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
