package com.example.adminmemo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun Activity.confirmChoice(message: String, confirmLabel: String, onConfirm: () -> Unit) {
    AlertDialog.Builder(this)
        .setMessage(message)
        .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
        .setNegativeButton("돌아가기", null)
        .show()
}

/** 진행 중인 퀴즈·모의고사에서 뒤로가기를 누르면 나갈지 한 번 더 묻는다. */
fun androidx.appcompat.app.AppCompatActivity.confirmLeaveOnBack(
    message: String,
    shouldAsk: () -> Boolean = { true }
) {
    onBackPressedDispatcher.addCallback(
        this,
        object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!shouldAsk()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                confirmChoice(message, "나가기") { finish() }
            }
        }
    )
}

/**
 * 저장 / 취소 / 삭제 버튼을 눌러도 바로 닫히지 않고, 한 번 더 확인한다.
 * onSave가 false를 반환하면(검증 실패) 수정 대화상자는 그대로 둔다.
 * 뒤로가기는 키보드가 있으면 먼저 내리고, 없을 때만 상자를 닫는다.
 */
fun AlertDialog.Builder.showWithEditConfirms(
    activity: Activity,
    onSave: () -> Boolean,
    onDelete: (() -> Unit)? = null
) {
    setPositiveButton("저장", null)
    setNegativeButton("취소", null)
    if (onDelete != null) setNeutralButton("삭제", null)
    val dialog = create()
    var imeBottom = 0
    dialog.setOnShowListener {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                insets
            }
            ViewCompat.requestApplyInsets(decor)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            activity.confirmChoice("저장할까요?", "저장") {
                if (onSave()) dialog.dismiss()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            activity.confirmChoice("수정을 취소할까요?", "취소") {
                dialog.dismiss()
            }
        }
        if (onDelete != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                activity.confirmChoice("정말 삭제할까요? 되돌릴 수 없어요.", "삭제") {
                    onDelete()
                    dialog.dismiss()
                }
            }
        }
    }
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
        if (!editDialogKeyboardOpen(dialog, imeBottom)) return@setOnKeyListener false
        if (event.action == KeyEvent.ACTION_UP) hideEditDialogKeyboard(activity, dialog)
        true
    }
    dialog.show()
}

private fun editDialogKeyboardOpen(dialog: AlertDialog, imeBottom: Int): Boolean {
    if (imeBottom > 0) return true
    val decor = dialog.window?.decorView ?: return false
    val insets = ViewCompat.getRootWindowInsets(decor) ?: return false
    return insets.isVisible(WindowInsetsCompat.Type.ime()) ||
        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
}

private fun hideEditDialogKeyboard(activity: Activity, dialog: AlertDialog) {
    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val token = dialog.currentFocus?.windowToken ?: dialog.window?.decorView?.windowToken
    imm.hideSoftInputFromWindow(token, 0)
    dialog.currentFocus?.clearFocus()
}
