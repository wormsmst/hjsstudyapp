package com.example.adminmemo

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/** 모든 화면이 상속받는 기본 Activity. 다크 테마 / 글자 크기 설정을 일괄 적용한다. */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val scale = AppPrefs.getFontScale(newBase)
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = scale
        val newContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(newContext)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        when (AppPrefs.getThemeMode(this)) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        super.onCreate(savedInstanceState)
    }

    override fun onStop() {
        FirebaseSyncManager.flushPendingProgressSync(this)
        super.onStop()
    }

    protected fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    protected fun bindLandscapeSplit(splitId: Int) {
        val split = findViewById<android.widget.LinearLayout>(splitId) ?: return
        val land = isLandscape()
        split.orientation = if (land) {
            android.widget.LinearLayout.HORIZONTAL
        } else {
            android.widget.LinearLayout.VERTICAL
        }
        val splitLp = split.layoutParams
        val splitFills = splitLp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT ||
            ((splitLp as? android.widget.LinearLayout.LayoutParams)?.weight ?: 0f) > 0f
        for (i in 0 until split.childCount) {
            val child = split.getChildAt(i)
            val lp = child.layoutParams as android.widget.LinearLayout.LayoutParams
            if (land) {
                lp.width = 0
                lp.weight = 1f
                lp.height = if (splitFills) android.view.ViewGroup.LayoutParams.MATCH_PARENT
                else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                lp.weight = if (splitFills) 1f else 0f
                lp.height = if (splitFills) 0 else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            child.layoutParams = lp
        }
    }
}
