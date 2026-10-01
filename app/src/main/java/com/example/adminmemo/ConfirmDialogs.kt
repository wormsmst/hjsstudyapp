package com.example.adminmemo

import android.app.Activity
import android.app.AlertDialog

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
    dialog.setOnShowListener {
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
    dialog.show()
}
