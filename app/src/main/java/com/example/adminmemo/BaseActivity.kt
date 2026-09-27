package com.example.adminmemo

import android.R
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 모든 화면이 상속받는 기본 Activity. 다크 테마 / 글자 크기 설정을 일괄 적용한다. */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val scale = AppPrefs.getFontScale(newBase)
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = scale
        val newContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(newContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        when (AppPrefs.getThemeMode(this)) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applySystemWindowInsets()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        applySystemWindowInsets()
    }

    private fun applySystemWindowInsets() {
        val rootView = findViewById<ViewGroup>(R.id.content)?.getChildAt(0)
        rootView?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, insets.top, v.paddingRight, v.paddingBottom)
                windowInsets
            }
        }
    }
}
