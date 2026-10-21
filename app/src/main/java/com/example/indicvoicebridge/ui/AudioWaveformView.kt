package com.example.indicvoicebridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.*
import kotlin.random.Random

class AudioWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barCount = 24
    private val barHeights = FloatArray(barCount) { 0.05f }
    private val targetHeights = FloatArray(barCount) { 0.05f }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
        style = Paint.Style.FILL
    }

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBDEFB")
        style = Paint.Style.FILL
    }

    private var rms = 0f
    private var isActive = false

    fun updateRms(rmsDb: Float) {
        isActive = true
        rms = rmsDb.coerceIn(0f, 12f) / 12f
        for (i in 0 until barCount) {
            val noise = Random.nextFloat() * 0.3f
            val base = rms * 0.7f + noise
            targetHeights[i] = (base * Random.nextFloat()).coerceIn(0.05f, 1f)
        }
        invalidate()
    }

    fun setInactive() {
        isActive = false
        rms = 0f
        targetHeights.fill(0.05f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val totalGap = w * 0.4f
        val barW = (w - totalGap) / barCount
        val gap = totalGap / (barCount + 1)
        val centerY = h / 2f

        for (i in 0 until barCount) {
            barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.25f
            val barH = (barHeights[i] * h * 0.9f).coerceAtLeast(4f)
            val left = gap + i * (barW + gap)
            val top = centerY - barH / 2f
            val right = left + barW
            val bottom = centerY + barH / 2f
            val radius = barW / 2f
            val p = if (isActive) paint else inactivePaint
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, p)
        }

        if (isActive) postInvalidateDelayed(40)
    }
}

