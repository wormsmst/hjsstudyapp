package com.example.adminmemo

import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.cardview.widget.CardView
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        printDebugSha1()
        setupDDayCard()

        findViewById<CardView>(R.id.tileStudy).setOnClickListener {
            goToSubjectSelect(PURPOSE_STUDY)
        }
        findViewById<CardView>(R.id.tileOutline).setOnClickListener {
            goToSubjectSelect(PURPOSE_OUTLINE)
        }
        findViewById<CardView>(R.id.tileExam).setOnClickListener {
            goToSubjectSelect(PURPOSE_EXAM)
        }
        findViewById<CardView>(R.id.tileQuiz).setOnClickListener {
            goToSubjectSelect(PURPOSE_QUIZ)
        }
        findViewById<CardView>(R.id.tileManage).setOnClickListener {
            goToSubjectSelect(PURPOSE_MANAGE)
        }
        findViewById<CardView>(R.id.tileHonesty).setOnClickListener {
            goToSubjectSelect(PURPOSE_HONESTY)
        }
        findViewById<CardView>(R.id.tileSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun printDebugSha1() {
        try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            val signatures = info.signatures
            if (signatures != null) {
                for (signature in signatures) {
                    val md = MessageDigest.getInstance("SHA1")
                    md.update(signature.toByteArray())
                    val digest = md.digest()
                    val sb = StringBuilder()
                    for (b in digest) {
                        sb.append(String.format("%02X:", b))
                    }
                    if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                    Log.d("APP_SHA1", ">>> MY APP SHA1: $sb <<<")
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onResume() {
        super.onResume()
        updateLoginStatus()
    }

    private fun updateLoginStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvHomeLoginStatus)
        val user = FirebaseSyncManager.currentUser
        if (user != null) {
            tvStatus.text = "👤 연동됨: ${user.email ?: user.displayName}"
        } else {
            tvStatus.text = "👤 비로그인 상태 (설정에서 연동 가능)"
        }
    }

    private fun setupDDayCard() {
        val cardDDay = findViewById<CardView>(R.id.cardDDay)
        val tvBadge = findViewById<TextView>(R.id.tvDDayBadge)
        val tvDesc = findViewById<TextView>(R.id.tvDDayDesc)

        fun updateDisplay() {
            val dateStr = AppPrefs.getDDayDate(this)
            val days = calculateDays(dateStr)
            tvBadge.text = when {
                days > 0 -> "D-$days"
                days == 0L -> "D-Day 🎉"
                else -> "D+${-days}"
            }
            tvDesc.text = "시험일: $dateStr (탭하여 변경)"
            updateLoginStatus()
        }

        updateDisplay()

        cardDDay.setOnClickListener {
            val currentStr = AppPrefs.getDDayDate(this)
            val cal = Calendar.getInstance()
            try {
                val parts = currentStr.split("-")
                if (parts.size == 3) {
                    cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                }
            } catch (e: Exception) {
                // ignore
            }

            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    val newDateStr = String.format(Locale.getDefault(), "%d-%02d-%02d", year, month + 1, dayOfMonth)
                    AppPrefs.setDDayDate(this, newDateStr)
                    updateDisplay()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun calculateDays(targetDateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val target = sdf.parse(targetDateStr)?.time ?: return 0L
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val diff = target - today
            TimeUnit.MILLISECONDS.toDays(diff)
        } catch (e: Exception) {
            0L
        }
    }

    private fun goToSubjectSelect(purpose: String) {
        val intent = Intent(this, SubjectSelectActivity::class.java)
        intent.putExtra(EXTRA_PURPOSE, purpose)
        startActivity(intent)
    }
}
