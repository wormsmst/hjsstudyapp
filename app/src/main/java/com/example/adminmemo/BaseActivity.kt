package com.example.adminmemo

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/** 모든 화면이 상속받는 기본 Activity. 다크 테마 / 글자 크기 설정을 일괄 적용한다. */
open class BaseActivity : AppCompatActivity() {

    companion object {
        private const val KEEP_SCREEN_MS = 15 * 60 * 1000L
    }

    protected open val showScratchPad = false
    protected open val scratchPadInToolbar = false
    protected open val showGeminiFab = false
    private var scratchHost: View? = null
    private var scratchExpanded = false
    private var scratchW = 0
    private var scratchH = 0
    private val keepScreenHandler = Handler(Looper.getMainLooper())
    private val releaseKeepScreen = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private var geminiFab: View? = null
    private var geminiPanel: View? = null
    private var geminiSelected = ""
    private var geminiContext = ""
    private var geminiArmed = false
    private var eatBackUntil = 0L
    private val geminiBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            onGeminiBack()
        }
    }
    private var geminiImeBottom = 0
    private var geminiSoftInputSaved: Int? = null
    private var geminiInsetsWired = false
    private var geminiImeAnimating = false

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
        onBackPressedDispatcher.addCallback(this, geminiBack)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (geminiPanel?.visibility == View.VISIBLE) {
            geminiPanel?.post { relayoutGeminiPanel() }
        }
    }

    override fun onResume() {
        super.onResume()
        bumpKeepScreen()
    }

    override fun onPause() {
        FirebaseSyncManager.flushPendingProgressSync(this)
        keepScreenHandler.removeCallbacks(releaseKeepScreen)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) bumpKeepScreen()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val panelOpen = geminiPanel?.visibility == View.VISIBLE
        val eat = SystemClock.uptimeMillis() < eatBackUntil
        if (event.keyCode == KeyEvent.KEYCODE_BACK && (panelOpen || eat)) {
            if (event.action == KeyEvent.ACTION_UP && panelOpen && !eat) {
                onGeminiBack()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onGeminiBack() {
        if (geminiKeyboardOpen()) hideGeminiKeyboard() else hideGeminiPanel()
    }

    private fun geminiKeyboardOpen(): Boolean {
        if (geminiImeBottom > 0) return true
        val insets = ViewCompat.getRootWindowInsets(window.decorView) ?: return false
        return insets.isVisible(WindowInsetsCompat.Type.ime())
    }

    private fun hideGeminiKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = currentFocus?.windowToken
            ?: geminiPanel?.windowToken
            ?: window.decorView.windowToken
        imm.hideSoftInputFromWindow(token, 0)
        geminiPanel?.requestFocus()
    }

    fun swallowBackBriefly() {
        eatBackUntil = SystemClock.uptimeMillis() + 1200L
    }

    /** 앱이 앞에 있는 동안 시스템 절전 시간과 상관없이 15분 화면을 켠다. 만지면 다시 15분. */
    protected fun bumpKeepScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        keepScreenHandler.removeCallbacks(releaseKeepScreen)
        keepScreenHandler.postDelayed(releaseKeepScreen, KEEP_SCREEN_MS)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        attachScratchPad()
        attachGeminiFab()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        attachScratchPad()
        attachGeminiFab()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        attachScratchPad()
        attachGeminiFab()
    }

    override fun onStop() {
        FirebaseSyncManager.flushPendingProgressSync(this)
        super.onStop()
    }

    protected open fun geminiStudyContext(): String = ""

    fun armGemini(selected: String, contextText: String) {
        geminiSelected = selected.trim()
        geminiContext = contextText
        geminiArmed = geminiSelected.isNotEmpty()
        bindGeminiArmed()
    }

    private fun attachGeminiFab() {
        if (!showGeminiFab) return
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val fab = geminiFab ?: LayoutInflater.from(this)
            .inflate(R.layout.view_gemini_fab, content, false)
            .also { geminiFab = it }
        (fab.parent as? ViewGroup)?.removeView(fab)
        val d = resources.displayMetrics.density
        val lp = FrameLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt())
        content.addView(fab, lp)
        fab.setOnTouchListener(geminiDragListener(fab))
        bindGeminiArmed()
        fab.post { placeGeminiFab(fab) }
        wireGeminiTextViews(content)
    }

    private fun placeGeminiFab(fab: View) {
        val parent = fab.parent as? View ?: return
        val d = resources.displayMetrics.density
        val m = 18 * d
        val savedX = AppPrefs.getGeminiFabX(this)
        val savedY = AppPrefs.getGeminiFabY(this)
        val maxX = (parent.width - fab.width).toFloat().coerceAtLeast(0f)
        val maxY = (parent.height - fab.height).toFloat().coerceAtLeast(0f)
        fab.translationX = if (savedX >= 0f) savedX.coerceIn(0f, maxX) else (maxX - m).coerceAtLeast(0f)
        fab.translationY = if (savedY >= 0f) savedY.coerceIn(0f, maxY) else (maxY - m).coerceAtLeast(0f)
    }

    private fun bindGeminiArmed() {
        val fab = geminiFab ?: return
        fab.scaleX = if (geminiArmed) 1.12f else 1f
        fab.scaleY = if (geminiArmed) 1.12f else 1f
        fab.alpha = if (geminiArmed) 1f else 0.92f
        fab.elevation = if (geminiArmed) 10f * resources.displayMetrics.density else 0f
    }

    fun openGeminiPanel(selected: String, contextText: String) {
        swallowBackBriefly()
        armGemini(selected, contextText)
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        wireGeminiInsets()
        if (geminiSoftInputSaved == null) {
            geminiSoftInputSaved = window.attributes.softInputMode
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val panel = geminiPanel ?: LayoutInflater.from(this)
            .inflate(R.layout.view_gemini_panel, content, false)
            .also { geminiPanel = it; bindGeminiPanel(it) }
        if (panel.parent == null) {
            content.addView(
                panel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        panel.findViewById<EditText>(R.id.etGeminiSelected).apply {
            setText(selected.trim())
            clearFocus()
        }
        panel.findViewById<EditText>(R.id.etGeminiQuestion).apply {
            setText(if (selected.trim().isEmpty()) "" else "이게 무슨 뜻이야?")
            clearFocus()
        }
        panel.findViewById<TextView>(R.id.tvGeminiAnswer).text = ""
        panel.findViewById<View>(R.id.progressGemini).visibility = View.GONE
        panel.requestFocus()
        geminiBack.isEnabled = true
        panel.visibility = View.VISIBLE
        relayoutGeminiPanel()
        panel.bringToFront()
        geminiFab?.bringToFront()
    }

    private fun wireGeminiInsets() {
        val root = window.decorView
        if (geminiInsetsWired) {
            ViewCompat.requestApplyInsets(root)
            return
        }
        geminiInsetsWired = true
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            if (!geminiImeAnimating) {
                applyGeminiIme(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        geminiImeAnimating = true
                    }
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        applyGeminiIme(bounds.upperBound.bottom)
                    }
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat = insets

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                    geminiImeAnimating = false
                    val bottom = ViewCompat.getRootWindowInsets(root)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    applyGeminiIme(bottom)
                }
            }
        )
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyGeminiIme(bottom: Int) {
        val v = bottom.coerceAtLeast(0)
        if (abs(v - geminiImeBottom) < 8) return
        geminiImeBottom = v
        if (geminiPanel?.visibility == View.VISIBLE) relayoutGeminiPanel()
    }

    private fun relayoutGeminiPanel() {
        val panel = geminiPanel ?: return
        val parent = panel.parent as? View ?: return
        val d = resources.displayMetrics.density
        val gap = (8 * d).toInt()
        val parentW = if (parent.width > 0) parent.width else resources.displayMetrics.widthPixels
        val parentH = if (parent.height > 0) parent.height else resources.displayMetrics.heightPixels
        val room = (parentH - geminiImeBottom - gap * 2).coerceAtLeast((120 * d).toInt())
        val land = isLandscape()
        val lp = (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (land) {
                (parentW * 0.45f).toInt().coerceAtLeast((160 * d).toInt())
            } else {
                (parentW * 0.675f).toInt().coerceAtLeast((240 * d).toInt())
            }
            height = if (land) room else (room * 0.7f).toInt().coerceAtLeast((120 * d).toInt())
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = gap
            bottomMargin = gap + geminiImeBottom
        }
        panel.layoutParams = lp
        panel.translationX = 0f
        panel.translationY = 0f
        panel.findViewById<EditText>(R.id.etGeminiSelected).minHeight =
            ((if (land) 216 else 88) * d).toInt()
        panel.findViewById<TextView>(R.id.tvGeminiAnswer).minHeight =
            ((if (land) 280 else 64) * d).toInt()
    }

    private fun hideGeminiPanel() {
        hideGeminiKeyboard()
        geminiImeAnimating = false
        geminiImeBottom = 0
        geminiPanel?.visibility = View.GONE
        geminiBack.isEnabled = false
        geminiSoftInputSaved?.let { window.setSoftInputMode(it) }
        geminiSoftInputSaved = null
    }

    private fun bindGeminiPanel(panel: View) {
        panel.findViewById<View>(R.id.tvGeminiDragHandle).setOnTouchListener(geminiPanelDragListener(panel))
        panel.findViewById<View>(R.id.btnGeminiClose).setOnClickListener { hideGeminiPanel() }
        panel.findViewById<View>(R.id.btnGeminiAsk).setOnClickListener {
            val etSelected = panel.findViewById<EditText>(R.id.etGeminiSelected)
            val etQuestion = panel.findViewById<EditText>(R.id.etGeminiQuestion)
            val tvAnswer = panel.findViewById<TextView>(R.id.tvGeminiAnswer)
            val progress = panel.findViewById<View>(R.id.progressGemini)
            val question = etQuestion.text.toString().trim()
            if (question.isEmpty()) return@setOnClickListener
            val apiKey = AppPrefs.getGeminiApiKey(this)
            if (apiKey.isBlank()) return@setOnClickListener
            hideGeminiKeyboard()
            val focus = etSelected.text.toString().trim()
            progress.visibility = View.VISIBLE
            tvAnswer.text = ""
            GeminiClient.ask(apiKey, question, clipStudyContext(geminiContext.ifBlank { geminiStudyContext() }, focus)) { answer, error ->
                runOnUiThread {
                    progress.visibility = View.GONE
                    tvAnswer.text = answer ?: error ?: "알 수 없는 오류가 발생했어요"
                }
            }
        }
    }

    private fun geminiPanelDragListener(host: View) = View.OnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                v.setTag(R.id.geminiPanelHost, floatArrayOf(ev.rawX, ev.rawY, host.translationX, host.translationY))
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = v.getTag(R.id.geminiPanelHost) as? FloatArray ?: return@OnTouchListener true
                val parent = host.parent as? View
                val maxX = ((parent?.width ?: host.width) - host.width).toFloat().coerceAtLeast(0f)
                val maxY = ((parent?.height ?: host.height) - host.height - geminiImeBottom)
                    .toFloat().coerceAtLeast(0f)
                host.translationX = (start[2] + (ev.rawX - start[0])).coerceIn(-maxX, maxX)
                host.translationY = (start[3] + (ev.rawY - start[1])).coerceIn(0f, maxY)
                true
            }
            else -> true
        }
    }

    private fun geminiDragListener(host: View) = View.OnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallowBackBriefly()
                host.setTag(R.id.btnGeminiFab, floatArrayOf(ev.rawX, ev.rawY, host.translationX, host.translationY))
                host.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = host.getTag(R.id.btnGeminiFab) as? FloatArray ?: return@OnTouchListener true
                val parent = host.parent as? View
                val maxX = ((parent?.width ?: host.width) - host.width).toFloat().coerceAtLeast(0f)
                val maxY = ((parent?.height ?: host.height) - host.height).toFloat().coerceAtLeast(0f)
                host.translationX = (start[2] + (ev.rawX - start[0])).coerceIn(0f, maxX)
                host.translationY = (start[3] + (ev.rawY - start[1])).coerceIn(0f, maxY)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val start = host.getTag(R.id.btnGeminiFab) as? FloatArray
                val dx = if (start != null) abs(ev.rawX - start[0]) else 0f
                val dy = if (start != null) abs(ev.rawY - start[1]) else 0f
                AppPrefs.setGeminiFabPos(this, host.translationX, host.translationY)
                if (dx < 12 && dy < 12 && ev.actionMasked == MotionEvent.ACTION_UP) {
                    host.post { openGeminiFromFab() }
                }
                true
            }
            else -> false
        }
    }

    private fun openGeminiFromFab() {
        showGeminiAskDialog(this, geminiSelected, geminiContext.ifBlank { geminiStudyContext() })
    }

    private fun wireGeminiTextViews(root: View) {
        fun walk(v: View) {
            if (v.id == R.id.geminiPanelHost || v.id == R.id.btnGeminiFab) return
            when (v) {
                is Button -> {}
                is EditText -> enableGeminiSelection(this, v) { geminiStudyContext() }
                is TextView -> if (v.isTextSelectable) enableGeminiSelection(this, v) { geminiStudyContext() }
                is ViewGroup -> {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
        }
        walk(root)
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
        val gutter = if (land) (40 * resources.displayMetrics.density).toInt() else 0
        for (i in 0 until split.childCount) {
            val child = split.getChildAt(i)
            val lp = child.layoutParams as android.widget.LinearLayout.LayoutParams
            if (land) {
                lp.width = 0
                lp.weight = 1f
                lp.height = if (splitFills) android.view.ViewGroup.LayoutParams.MATCH_PARENT
                else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                lp.marginStart = if (i > 0) gutter else 0
            } else {
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                lp.weight = if (splitFills) 1f else 0f
                lp.height = if (splitFills) 0 else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                lp.marginStart = 0
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
