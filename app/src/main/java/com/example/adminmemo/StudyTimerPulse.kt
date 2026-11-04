package com.example.adminmemo

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/** 남은 시간이 30초 미만이면 타이머를 빨간색으로 점멸한다. */
object StudyTimerPulse {
    const val WARN_UNDER_SEC = 30

    fun bind(
        tv: TextView,
        remainSec: Int,
        counting: Boolean,
        idleColorRes: Int,
        solidWarnUnderSec: Int = 0
    ) {
        val urgent = counting && remainSec in 1 until WARN_UNDER_SEC
        val solid = remainSec <= 0 ||
            (solidWarnUnderSec > 0 && remainSec in 1..solidWarnUnderSec)
        val colorRes = if (urgent || solid) R.color.stat_delta_down else idleColorRes
        tv.setTextColor(ContextCompat.getColor(tv.context, colorRes))
        setBlinking(tv, urgent)
    }

    fun stop(tv: TextView?) {
        if (tv == null) return
        setBlinking(tv, false)
    }

    private fun setBlinking(tv: TextView, on: Boolean) {
        val running = tv.getTag(R.id.tag_timer_pulse) as? ObjectAnimator
        if (!on) {
            running?.cancel()
            tv.setTag(R.id.tag_timer_pulse, null)
            tv.alpha = 1f
            return
        }
        if (running?.isRunning == true) return
        running?.cancel()
        val anim = ObjectAnimator.ofFloat(tv, View.ALPHA, 1f, 0.18f).apply {
            duration = 800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = FastOutSlowInInterpolator()
        }
        tv.setTag(R.id.tag_timer_pulse, anim)
        anim.start()
    }
}
