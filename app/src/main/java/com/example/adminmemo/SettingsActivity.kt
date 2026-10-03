package com.example.adminmemo

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Calendar

class SettingsActivity : BaseActivity() {

    private val fontScales = floatArrayOf(0.9f, 1.0f, 1.15f, 1.35f, 1.55f, 1.8f, 2.1f)
    private val fontScaleNames = arrayOf("작게", "보통", "크게", "아주 크게", "더 크게", "태블릿", "최대")
    private val widgetFontScales = floatArrayOf(0.9f, 1.0f, 1.15f, 1.35f)
    private val widgetColors = listOf(
        0xF7F5F2, // 아이보리
        0x2A2833, // 다크
        0xE8DCC8, // 샌드
        0xC9CEDA, // 슬레이트
        0xC9D4C8, // 세이지
        0xD8CFD4, // 모브
        0xD4D0C6  // 스톤
    )

    private var selectedWidgetColor = 0xFFFFFF
    private lateinit var widgetPreviewBg: View
    private lateinit var widgetPreviewText: TextView
    private lateinit var colorButtons: MutableList<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupThemeSection()
        setupFontSection()
        setupWidgetSection()
        setupGeminiSection()
        setupAccountSection()
        setupDdaySection()
        findViewById<Button>(R.id.btnTtsSettings).setOnClickListener { TtsVoiceUi.open(this) }

        findViewById<Button>(R.id.btnApplySettings).setOnClickListener {
            AppPrefs.setFontScale(this, fontScales[findViewById<SeekBar>(R.id.seekFontScale).progress])
            AppPrefs.setWidgetColor(this, selectedWidgetColor)
            AppPrefs.setWidgetOpacity(this, 100 - findViewById<SeekBar>(R.id.seekWidgetOpacity).progress)
            AppPrefs.setWidgetFontScale(this, widgetFontScales[findViewById<SeekBar>(R.id.seekWidgetFont).progress])
            AppPrefs.setGeminiApiKey(this, findViewById<EditText>(R.id.etGeminiKey).text.toString().trim())
            android.appwidget.AppWidgetManager.getInstance(this).let { mgr ->
                val ids = mgr.getAppWidgetIds(android.content.ComponentName(this, CardWidgetProvider::class.java))
                ids.forEach { CardWidgetProvider.updateWidget(this, mgr, it) }
            }
            recreate()
        }
    }

    private fun setupThemeSection() {
        val rg = findViewById<RadioGroup>(R.id.rgTheme)
        when (AppPrefs.getThemeMode(this)) {
            "light" -> rg.check(R.id.rbThemeLight)
            "dark" -> rg.check(R.id.rbThemeDark)
            else -> rg.check(R.id.rbThemeSystem)
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> "light"
                R.id.rbThemeDark -> "dark"
                else -> "system"
            }
            AppPrefs.setThemeMode(this, mode)
        }
    }

    private fun nearestScaleIndex(scales: FloatArray, value: Float, defaultIdx: Int = 1): Int {
        return scales.indices.minByOrNull { kotlin.math.abs(scales[it] - value) } ?: defaultIdx
    }

    private fun setupFontSection() {
        val seek = findViewById<SeekBar>(R.id.seekFontScale)
        val preview = findViewById<TextView>(R.id.tvFontPreview)
        val label = findViewById<TextView>(R.id.tvFontScaleLabel)
        val currentIdx = nearestScaleIndex(fontScales, AppPrefs.getFontScale(this))
        seek.max = fontScales.lastIndex
        seek.progress = currentIdx
        fun applyPreview(idx: Int) {
            val i = idx.coerceIn(0, fontScales.lastIndex)
            preview.textSize = 16f * fontScales[i]
            label.text = "${fontScaleNames[i]}  (${String.format("%.2f", fontScales[i])}배)"
        }
        applyPreview(currentIdx)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                applyPreview(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupWidgetSection() {
        widgetPreviewBg = findViewById(R.id.widgetPreviewBg)
        widgetPreviewText = findViewById(R.id.widgetPreviewText)

        selectedWidgetColor = AppPrefs.getWidgetColor(this)
        val row = findViewById<LinearLayout>(R.id.rowWidgetColors)
        colorButtons = mutableListOf()
        val dpSize = (36 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()

        widgetColors.forEach { colorRgb ->
            val swatch = View(this)
            val lp = LinearLayout.LayoutParams(dpSize, dpSize)
            lp.marginEnd = margin
            swatch.layoutParams = lp
            val gd = android.graphics.drawable.GradientDrawable()
            gd.shape = android.graphics.drawable.GradientDrawable.OVAL
            gd.setColor(Color.rgb(colorRgb shr 16 and 0xFF, colorRgb shr 8 and 0xFF, colorRgb and 0xFF))
            gd.setStroke(
                if (colorRgb == selectedWidgetColor) (3 * resources.displayMetrics.density).toInt() else 0,
                ContextCompat.getColor(this, R.color.primary)
            )
            swatch.background = gd
            swatch.setOnClickListener {
                selectedWidgetColor = colorRgb
                updateWidgetPreview()
                refreshSwatchBorders()
            }
            row.addView(swatch)
            colorButtons.add(swatch)
        }

        val seekOpacity = findViewById<SeekBar>(R.id.seekWidgetOpacity)
        seekOpacity.progress = 100 - AppPrefs.getWidgetOpacity(this)
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { updateWidgetPreview() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val seekWidgetFont = findViewById<SeekBar>(R.id.seekWidgetFont)
        seekWidgetFont.max = widgetFontScales.lastIndex
        seekWidgetFont.progress = nearestScaleIndex(widgetFontScales, AppPrefs.getWidgetFontScale(this))
        seekWidgetFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                widgetPreviewText.textSize = 13f * widgetFontScales[progress]
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        updateWidgetPreview()
    }

    private fun refreshSwatchBorders() {
        colorButtons.forEachIndexed { i, view ->
            val colorRgb = widgetColors[i]
            val gd = android.graphics.drawable.GradientDrawable()
            gd.shape = android.graphics.drawable.GradientDrawable.OVAL
            gd.setColor(Color.rgb(colorRgb shr 16 and 0xFF, colorRgb shr 8 and 0xFF, colorRgb and 0xFF))
            gd.setStroke(
                if (colorRgb == selectedWidgetColor) (3 * resources.displayMetrics.density).toInt() else 0,
                ContextCompat.getColor(this, R.color.primary)
            )
            view.background = gd
        }
    }

    private fun updateWidgetPreview() {
        val opacityPercent = 100 - findViewById<SeekBar>(R.id.seekWidgetOpacity).progress
        val alpha = (opacityPercent * 255 / 100).coerceIn(0, 255)
        val r = selectedWidgetColor shr 16 and 0xFF
        val g = selectedWidgetColor shr 8 and 0xFF
        val b = selectedWidgetColor and 0xFF
        widgetPreviewBg.setBackgroundColor(Color.argb(alpha, r, g, b))
        widgetPreviewText.setTextColor(if ((r + g + b) / 3 < 128) Color.WHITE else Color.parseColor("#1A1C1E"))
    }

    private fun setupGeminiSection() {
        findViewById<EditText>(R.id.etGeminiKey).setText(AppPrefs.getGeminiApiKey(this))
    }

    private fun setupAccountSection() {
        refreshAccountUi()
        findViewById<Button>(R.id.btnGoogleLogin).setOnClickListener { startGoogleLogin() }
        findViewById<Button>(R.id.btnGoogleLogout).setOnClickListener { startGoogleLogout() }
        findViewById<Button>(R.id.btnContentPush).setOnClickListener { confirmContentPush() }
        findViewById<Button>(R.id.btnContentPull).setOnClickListener { confirmContentPull() }
    }

    private fun setupDdaySection() {
        refreshExamDateLabel()
        findViewById<Button>(R.id.btnPickExamDate2).setOnClickListener { showExamDatePicker(2) }
    }

    private fun refreshExamDateLabel() {
        val d1 = AppPrefs.getExam1DateMillis(this)
        val d2 = AppPrefs.getExam2DateMillis(this)
        findViewById<TextView>(R.id.tvExamDate1Picked).text = if (d1 <= 0L) {
            "1차 시험일을 아직 정하지 않았어요"
        } else {
            "1차  ${DateFormatters.dateOnly(d1)}  ·  ${DdayCalculator.ddayToken(d1)}"
        }
        findViewById<TextView>(R.id.tvExamDate2Picked).text = if (d2 <= 0L) {
            "2차 시험일을 아직 정하지 않았어요"
        } else {
            "2차  ${DateFormatters.dateOnly(d2)}  ·  ${DdayCalculator.ddayToken(d2)}"
        }
    }

    private fun showExamDatePicker(round: Int) {
        val cal = Calendar.getInstance()
        val saved = if (round == 1) AppPrefs.getExam1DateMillis(this) else AppPrefs.getExam2DateMillis(this)
        if (saved > 0L) cal.timeInMillis = saved
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance()
                picked.set(year, month, day, 0, 0, 0)
                picked.set(Calendar.MILLISECOND, 0)
                if (round == 1) AppPrefs.setExam1DateMillis(this, picked.timeInMillis)
                else AppPrefs.setExam2DateMillis(this, picked.timeInMillis)
                refreshExamDateLabel()
                Toast.makeText(this, "${round}차 시험일을 저장했어요", Toast.LENGTH_SHORT).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun refreshAccountUi() {
        val user = FirebaseSyncManager.currentUser
        val tv = findViewById<TextView>(R.id.tvAccountStatus)
        val btnLogin = findViewById<Button>(R.id.btnGoogleLogin)
        val btnLogout = findViewById<Button>(R.id.btnGoogleLogout)
        val btnPush = findViewById<Button>(R.id.btnContentPush)
        val btnPull = findViewById<Button>(R.id.btnContentPull)
        if (user == null) {
            tv.text = "로그인되어 있지 않아요"
            btnLogin.visibility = View.VISIBLE
            btnLogout.visibility = View.GONE
            btnPush.visibility = View.GONE
            btnPull.visibility = View.GONE
        } else {
            val name = user.email ?: user.displayName ?: "Google 계정"
            tv.text = "로그인됨 · $name"
            btnLogin.visibility = View.GONE
            btnLogout.visibility = View.VISIBLE
            btnPush.visibility = View.VISIBLE
            btnPull.visibility = View.VISIBLE
        }
    }

    private fun setAccountBusy(busy: Boolean) {
        findViewById<ProgressBar>(R.id.progressAccount).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnGoogleLogin).isEnabled = !busy
        findViewById<Button>(R.id.btnGoogleLogout).isEnabled = !busy
        findViewById<Button>(R.id.btnContentPush).isEnabled = !busy
        findViewById<Button>(R.id.btnContentPull).isEnabled = !busy
    }

    private fun startGoogleLogin() {
        setAccountBusy(true)
        lifecycleScope.launch {
            val result = GoogleAuthHelper.signInWithGoogle(this@SettingsActivity)
            setAccountBusy(false)
            result.fold(
                onSuccess = { email ->
                    refreshAccountUi()
                    Toast.makeText(this@SettingsActivity, "$email 으로 로그인했어요", Toast.LENGTH_SHORT).show()
                    mergeProgressAfterLogin()
                },
                onFailure = { e ->
                    if (e is GetCredentialCancellationException) return@fold
                    Toast.makeText(
                        this@SettingsActivity,
                        e.localizedMessage ?: "Google 로그인에 실패했어요",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun startGoogleLogout() {
        setAccountBusy(true)
        lifecycleScope.launch {
            GoogleAuthHelper.signOut(this@SettingsActivity)
            setAccountBusy(false)
            refreshAccountUi()
            Toast.makeText(this@SettingsActivity, "로그아웃했어요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mergeProgressAfterLogin() {
        if (!FirebaseSyncManager.isSignedIn()) return
        FirebaseSyncManager.syncProgressNow(this) { ok, message ->
            runOnUiThread {
                refreshAccountUi()
                if (!ok) {
                    Toast.makeText(
                        this,
                        "진도 합치기 실패: ${message ?: "알 수 없는 오류"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmContentPush() {
        if (!FirebaseSyncManager.isSignedIn()) {
            Toast.makeText(this, "먼저 Google로 로그인해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        confirmChoice(
            "이 기기의 본문·사례 수정을 클라우드에 올릴까요? 지금 올라 있는 본문은 이전 버전으로 최대 5개 남겨 두어요.",
            "올리기"
        ) {
            setAccountBusy(true)
            FirebaseSyncManager.pushContent(this) { ok, message ->
                runOnUiThread {
                    setAccountBusy(false)
                    Toast.makeText(
                        this,
                        if (ok) message ?: "올렸어요" else "올리기 실패: ${message ?: ""}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmContentPull() {
        if (!FirebaseSyncManager.isSignedIn()) {
            Toast.makeText(this, "먼저 Google로 로그인해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        setAccountBusy(true)
        FirebaseSyncManager.listContentRevisions { list, error ->
            runOnUiThread {
                setAccountBusy(false)
                if (error != null && list.isEmpty()) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (list.isEmpty()) {
                    Toast.makeText(this, "받아올 본문 백업이 없어요. 먼저 올려 주세요", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = list.mapIndexed { i, rev ->
                    val whenText = DateFormatters.dateTime(rev.updatedAt)
                    if (i == 0) "가장 최근  ·  $whenText" else "이전 ${i}  ·  $whenText"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("받을 본문 시점 (최대 5개)")
                    .setItems(labels) { _, which ->
                        val picked = list[which]
                        confirmChoice(
                            "${labels[which]} 본문으로 이 기기를 덮어쓸까요? 여기서만 고친 본문은 사라질 수 있어요.",
                            "받기"
                        ) {
                            setAccountBusy(true)
                            FirebaseSyncManager.pullContent(this, picked.id) { ok, message ->
                                runOnUiThread {
                                    setAccountBusy(false)
                                    Toast.makeText(
                                        this,
                                        if (ok) message ?: "받았어요" else "받기 실패: ${message ?: ""}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton("돌아가기", null)
                    .show()
            }
        }
    }
}
