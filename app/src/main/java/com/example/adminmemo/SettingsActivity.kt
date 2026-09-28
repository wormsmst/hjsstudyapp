package com.example.adminmemo

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

class SettingsActivity : BaseActivity() {

    private val fontScales = floatArrayOf(0.9f, 1.0f, 1.15f, 1.35f)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupThemeSection()
        setupFontSection()
        setupWidgetSection()
        setupGeminiSection()

        findViewById<Button>(R.id.btnApplySettings).setOnClickListener {
            AppPrefs.setFontScale(this, fontScales[findViewById<SeekBar>(R.id.seekFontScale).progress])
            AppPrefs.setWidgetColor(this, selectedWidgetColor)
            AppPrefs.setWidgetOpacity(this, 100 - findViewById<SeekBar>(R.id.seekWidgetOpacity).progress)
            AppPrefs.setWidgetFontScale(this, fontScales[findViewById<SeekBar>(R.id.seekWidgetFont).progress])
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
            val gd = android.graphics.drawable.GradientDrawable()
            gd.shape = android.graphics.drawable.GradientDrawable.OVAL
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
            val gd = android.graphics.drawable.GradientDrawable()
            gd.shape = android.graphics.drawable.GradientDrawable.OVAL
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

    private fun setupGeminiSection() {
        findViewById<EditText>(R.id.etGeminiKey).setText(AppPrefs.getGeminiApiKey(this))
    }
}
