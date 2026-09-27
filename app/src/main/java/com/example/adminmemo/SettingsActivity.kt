package com.example.adminmemo

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class SettingsActivity : BaseActivity() {

    private val fontScales = floatArrayOf(0.9f, 1.0f, 1.15f, 1.35f, 1.55f, 1.8f, 2.1f)
    private val widgetColors = listOf(
        0xFFFFFF, // 흰색
        0x1E2126, // 다크
        0xFFF3CD, // 크림
        0xD6E4FF, // 하늘
        0xD8F5D0, // 연두
        0xFFD9EC, // 핑크
        0xE6D9FF  // 라벤더
    )

    private var selectedWidgetColor = 0xFFFFFF
    private lateinit var widgetPreviewBg: View
    private lateinit var widgetPreviewText: TextView
    private lateinit var colorButtons: MutableList<View>
    private lateinit var googleSignInClient: GoogleSignInClient
    private val RC_SIGN_IN = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupThemeSection()
        setupFontSection()
        setupWidgetSection()
        setupCloudSyncSection()

        findViewById<Button>(R.id.btnApplySettings).setOnClickListener {
            AppPrefs.setFontScale(this, fontScales[findViewById<SeekBar>(R.id.seekFontScale).progress])
            AppPrefs.setWidgetColor(this, selectedWidgetColor)
            AppPrefs.setWidgetOpacity(this, 100 - findViewById<SeekBar>(R.id.seekWidgetOpacity).progress)
            AppPrefs.setWidgetFontScale(this, fontScales[findViewById<SeekBar>(R.id.seekWidgetFont).progress])
            AppWidgetManager.getInstance(this).let { mgr ->
                val ids = mgr.getAppWidgetIds(ComponentName(this, CardWidgetProvider::class.java))
                ids.forEach { CardWidgetProvider.updateWidget(this, mgr, it) }
            }
            recreate()
        }
    }

    private fun setupCloudSyncSection() {
        val tvStatus = findViewById<TextView>(R.id.tvLoginStatus)
        val btnLogin = findViewById<Button>(R.id.btnGoogleLogin)
        val btnSync = findViewById<Button>(R.id.btnCloudSync)

        fun updateUI() {
            val user = FirebaseSyncManager.currentUser
            if (user != null) {
                tvStatus.text = "로그인됨: ${user.email ?: user.displayName}"
                btnLogin.text = "로그아웃"
            } else {
                tvStatus.text = "로그인 상태: 비로그인"
                btnLogin.text = "구글 로그인"
            }
        }

        updateUI()

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        try {
            val webClientRes = resources.getIdentifier("default_web_client_id", "string", packageName)
            if (webClientRes != 0) {
                gsoBuilder.requestIdToken(getString(webClientRes))
            }
        } catch (e: Exception) {
            // ignore
        }
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        btnLogin.setOnClickListener {
            if (FirebaseSyncManager.isSignedIn()) {
                FirebaseAuth.getInstance().signOut()
                googleSignInClient.signOut().addOnCompleteListener {
                    Toast.makeText(this, "로그아웃 되었어요", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            } else {
                val signInIntent = googleSignInClient.signInIntent
                startActivityForResult(signInIntent, RC_SIGN_IN)
            }
        }

        btnSync.setOnClickListener {
            if (!FirebaseSyncManager.isSignedIn()) {
                Toast.makeText(this, "먼저 구글 로그인을 해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSync.isEnabled = false
            Toast.makeText(this, "스마트 클라우드 동기화 중...", Toast.LENGTH_SHORT).show()
            FirebaseSyncManager.smartSync(this) { success, err ->
                btnSync.isEnabled = true
                if (success) {
                    Toast.makeText(this, "스마트 동기화 완료! (최신 데이터 반영됨) ☁️", Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this, "동기화 실패: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken != null) {
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    FirebaseAuth.getInstance().signInWithCredential(credential)
                        .addOnCompleteListener(this) { authTask ->
                            if (authTask.isSuccessful) {
                                Toast.makeText(this, "구글 로그인 성공!", Toast.LENGTH_SHORT).show()
                                FirebaseSyncManager.smartSync(this) { _, _ -> }
                                recreate()
                            } else {
                                Toast.makeText(this, "인증 실패: ${authTask.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                } else {
                    Toast.makeText(this, "인증 토큰을 가져오지 못했어요", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                Toast.makeText(this, "구글 로그인 실패 (코드 ${e.statusCode})", Toast.LENGTH_SHORT).show()
            }
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

    private fun setupFontSection() {
        val seek = findViewById<SeekBar>(R.id.seekFontScale)
        val preview = findViewById<TextView>(R.id.tvFontPreview)
        val currentScale = AppPrefs.getFontScale(this)
        val currentIdx = fontScales.indexOfFirst { it == currentScale }.let { if (it < 0) 1 else it }
        seek.progress = currentIdx
        preview.textSize = 16f * fontScales[currentIdx]
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                preview.textSize = 16f * fontScales[progress]
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
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(Color.rgb(colorRgb shr 16 and 0xFF, colorRgb shr 8 and 0xFF, colorRgb and 0xFF))
            gd.setStroke(
                if (colorRgb == selectedWidgetColor) (3 * resources.displayMetrics.density).toInt() else 0,
                Color.parseColor("#2962FF")
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
        val currentWidgetScale = AppPrefs.getWidgetFontScale(this)
        seekWidgetFont.progress = fontScales.indexOfFirst { it == currentWidgetScale }.let { if (it < 0) 1 else it }
        seekWidgetFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                widgetPreviewText.textSize = 13f * fontScales[progress]
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        updateWidgetPreview()
    }

    private fun refreshSwatchBorders() {
        colorButtons.forEachIndexed { i, view ->
            val colorRgb = widgetColors[i]
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(Color.rgb(colorRgb shr 16 and 0xFF, colorRgb shr 8 and 0xFF, colorRgb and 0xFF))
            gd.setStroke(
                if (colorRgb == selectedWidgetColor) (3 * resources.displayMetrics.density).toInt() else 0,
                Color.parseColor("#2962FF")
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
}
