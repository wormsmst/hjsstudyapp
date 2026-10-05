package com.example.adminmemo

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.abs

/** 모든 화면이 상속받는 기본 Activity. 다크 테마 / 글자 크기 설정을 일괄 적용한다. */
open class BaseActivity : AppCompatActivity() {

    protected open val showScratchPad = false
    protected open val scratchPadInToolbar = false
    private var scratchHost: View? = null
    private var scratchExpanded = false
    private var scratchW = 0
    private var scratchH = 0

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

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        attachScratchPad()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        attachScratchPad()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        attachScratchPad()
    }

    override fun onStop() {
        FirebaseSyncManager.flushPendingProgressSync(this)
        super.onStop()
    }

    protected fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    protected fun toggleScratchPad() {
        if (!showScratchPad) return
        scratchExpanded = !scratchExpanded
        ensureScratchSize()
        scratchHost?.let { applyScratchFold(it, scratchExpanded) } ?: attachScratchPad()
    }

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

    private fun ensureScratchSize() {
        if (scratchW > 0 && scratchH > 0) return
        val dm = resources.displayMetrics
        scratchW = (dm.widthPixels * 0.5f).toInt()
        scratchH = (dm.heightPixels * 0.5f).toInt()
    }

    private fun attachScratchPad() {
        if (!showScratchPad) return
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val host = scratchHost ?: LayoutInflater.from(this)
            .inflate(R.layout.view_scratch_pad, content, false)
            .also { scratchHost = it; bindScratchPad(it) }
        (host.parent as? ViewGroup)?.removeView(host)
        content.addView(host, scratchLayoutParams())
        applyScratchFold(host, scratchExpanded)
    }

    private fun scratchLayoutParams(): FrameLayout.LayoutParams {
        val d = resources.displayMetrics.density
        val expanded = scratchExpanded
        return FrameLayout.LayoutParams(
            if (expanded) scratchW.coerceAtLeast(1) else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (expanded) scratchH.coerceAtLeast(1) else ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }
    }

    private fun bindScratchPad(host: View) {
        val canvas = host.findViewById<ScratchPadView>(R.id.scratchPadCanvas)
        host.findViewById<View>(R.id.tvScratchTitle).setOnTouchListener(scratchDragListener(host))
        host.findViewById<TextView>(R.id.btnScratchClear).setOnClickListener { canvas.clearPad() }
        host.findViewById<TextView>(R.id.btnScratchFold).setOnClickListener {
            scratchExpanded = false
            applyScratchFold(host, false)
        }
        bindResize(host, R.id.scratchResizeTl, -1, -1)
        bindResize(host, R.id.scratchResizeTr, 1, -1)
        bindResize(host, R.id.scratchResizeBl, -1, 1)
        bindResize(host, R.id.scratchResizeBr, 1, 1)
        applyScratchFold(host, scratchExpanded)
    }

    private fun bindResize(host: View, id: Int, dirX: Int, dirY: Int) {
        host.findViewById<View>(id).setOnTouchListener { v, ev ->
            val d = resources.displayMetrics.density
            val min = (180 * d).toInt()
            val dm = resources.displayMetrics
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.setTag(
                        floatArrayOf(
                            ev.rawX, ev.rawY,
                            scratchW.toFloat(), scratchH.toFloat(),
                            host.translationX, host.translationY
                        )
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = v.tag as? FloatArray ?: return@setOnTouchListener true
                    val dx = ev.rawX - s[0]
                    val dy = ev.rawY - s[1]
                    var w = s[2] + dirX * dx
                    var h = s[3] + dirY * dy
                    var tx = s[4]
                    var ty = s[5]
                    if (dirX < 0) tx = s[4] + dx
                    if (dirY < 0) ty = s[5] + dy
                    w = w.coerceIn(min.toFloat(), dm.widthPixels.toFloat())
                    h = h.coerceIn(min.toFloat(), dm.heightPixels.toFloat())
                    scratchW = w.toInt()
                    scratchH = h.toInt()
                    host.translationX = tx
                    host.translationY = ty
                    host.layoutParams = scratchLayoutParams()
                    host.requestLayout()
                    true
                }
                else -> true
            }
        }
    }

    private fun applyScratchFold(host: View, expanded: Boolean) {
        ensureScratchSize()
        val opening = expanded &&
            host.findViewById<View>(R.id.scratchCanvasBox).visibility != View.VISIBLE
        host.findViewById<View>(R.id.scratchCanvasBox).visibility =
            if (expanded) View.VISIBLE else View.GONE
        host.findViewById<View>(R.id.btnScratchClear).visibility =
            if (expanded) View.VISIBLE else View.GONE
        host.findViewById<TextView>(R.id.btnScratchFold).text = if (expanded) "접기" else "열기"
        if (!expanded && scratchPadInToolbar) {
            host.visibility = View.GONE
        } else {
            host.visibility = View.VISIBLE
        }
        host.layoutParams = scratchLayoutParams()
        if (opening) {
            pinScratchTopEnd(host)
        } else if (!expanded) {
            host.translationX = 0f
            host.translationY = 0f
        }
        host.requestLayout()
    }

    private fun pinScratchTopEnd(host: View) {
        host.post {
            val parent = host.parent as? View ?: return@post
            val d = resources.displayMetrics.density
            val m = 12 * d
            host.translationX = (parent.width - scratchW - m).coerceAtLeast(0f)
            host.translationY = m
        }
    }

    private fun scratchDragListener(host: View) = View.OnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.setTag(R.id.scratchPadHost, floatArrayOf(ev.rawX, ev.rawY, host.translationX, host.translationY))
                v.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = v.getTag(R.id.scratchPadHost) as? FloatArray ?: return@OnTouchListener true
                host.translationX = start[2] + (ev.rawX - start[0])
                host.translationY = start[3] + (ev.rawY - start[1])
                true
            }
            MotionEvent.ACTION_UP -> {
                val start = v.getTag(R.id.scratchPadHost) as? FloatArray
                val dx = if (start != null) abs(ev.rawX - start[0]) else 0f
                val dy = if (start != null) abs(ev.rawY - start[1]) else 0f
                if (dx < 12 && dy < 12 && !scratchExpanded && !scratchPadInToolbar) {
                    scratchExpanded = true
                    applyScratchFold(host, true)
                }
                true
            }
            else -> false
        }
    }
}
