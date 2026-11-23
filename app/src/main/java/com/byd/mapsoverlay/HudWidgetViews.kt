package com.byd.mapsoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Phone/Tesla-style mini battery icon with dynamic fill level & color.
 */
class MiniBatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var percent: Int = 80
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#90A4AE")
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#90A4AE")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#69F0AE")
    }
    private val bodyRect = RectF()
    private val fillRect = RectF()
    private val capRect = RectF()

    fun setPercent(pct: Int) {
        val clamped = pct.coerceIn(0, 100)
        if (clamped == percent) return
        percent = clamped
        fillPaint.color = when {
            percent > 50 -> Color.parseColor("#69F0AE")
            percent >= 20 -> Color.parseColor("#FFB74D")
            else -> Color.parseColor("#FF5252")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val strokeW = (h * 0.12f).coerceAtLeast(1.5f)
        strokePaint.strokeWidth = strokeW
        val capW = (w * 0.11f).coerceAtLeast(2f)
        val bodyRight = w - capW - strokeW

        bodyRect.set(strokeW / 2f, strokeW / 2f, bodyRight, h - strokeW / 2f)
        val r = h * 0.22f
        canvas.drawRoundRect(bodyRect, r, r, strokePaint)

        // Battery terminal cap
        val capH = h * 0.44f
        val capTop = (h - capH) / 2f
        capRect.set(bodyRight + strokeW * 0.5f, capTop, w, capTop + capH)
        canvas.drawRoundRect(capRect, capW * 0.4f, capW * 0.4f, capPaint)

        // Inner fill
        val pad = strokeW + 1f
        val availW = (bodyRight - strokeW / 2f - pad) - pad
        if (availW > 0f && percent > 0) {
            val fillW = (availW * (percent / 100f)).coerceAtLeast(2f)
            fillRect.set(pad, pad, pad + fillW, h - pad)
            canvas.drawRoundRect(fillRect, r * 0.5f, r * 0.5f, fillPaint)
        }
    }
}

/**
 * Compact horizontal progress bar for Real Range.
 */
class MiniRangeBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var percent: Int = 80
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#263238")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38BDF8")
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    fun setPercent(pct: Int) {
        val clamped = pct.coerceIn(0, 100)
        if (clamped == percent) return
        percent = clamped
        fillPaint.color = if (percent > 25) Color.parseColor("#38BDF8") else Color.parseColor("#FF5252")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, r, r, trackPaint)
        val fillW = (w * (percent / 100f)).coerceAtLeast(if (percent > 0) h else 0f)
        if (fillW > 0f) {
            fillRect.set(0f, 0f, fillW, h)
            canvas.drawRoundRect(fillRect, r, r, fillPaint)
        }
    }
}

/**
 * Full-height vertical power bar rising from 0 at bottom.
 * Regen (-kW) = Mint Green, Normal (+kW) = Cyan, High Power (>30kW) = Amber.
 */
class MiniPowerBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var powerKw: Double = 0.0
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#263238")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#40C4FF")
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    fun setPowerKw(kw: Double) {
        if (kw == powerKw) return
        powerKw = kw
        fillPaint.color = when {
            kw < -0.5 -> Color.parseColor("#69F0AE")
            kw > 30.0 -> Color.parseColor("#FFB74D")
            else -> Color.parseColor("#40C4FF")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = w / 2f
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, r, r, trackPaint)

        val absKw = kotlin.math.abs(powerKw)
        if (absKw > 0.2) {
            val maxKw = if (powerKw < 0.0) 35.0 else 65.0
            val ratio = (absKw / maxKw).coerceIn(0.08, 1.0).toFloat()
            val fillH = h * ratio
            fillRect.set(0f, h - fillH, w, h)
            canvas.drawRoundRect(fillRect, r, r, fillPaint)
        }
    }
}

/**
 * Crisp circular status indicator dot for 12V battery health.
 */
class MiniStatusDotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var healthy: Boolean = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#22C55E")
    }

    fun setHealthy(isHealthy: Boolean) {
        if (isHealthy == healthy) return
        healthy = isHealthy
        paint.color = if (healthy) Color.parseColor("#22C55E") else Color.parseColor("#FF5252")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = (kotlin.math.min(width, height) / 2f).coerceAtLeast(1f)
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
    }
}
