package com.geburt2026.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

class WeightChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dataPoints = mutableListOf<Pair<Long, Double>>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#281565C0")
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val sdf = SimpleDateFormat("dd.MM", Locale.GERMAN)

    fun setData(points: List<Pair<Long, Double>>) {
        dataPoints.clear()
        dataPoints.addAll(points.sortedBy { it.first })
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val padL = 16f
        val padR = 16f
        val padT = 36f   // space for weight labels above dots
        val padB = 28f   // space for date labels below

        val chartW = w - padL - padR
        val chartH = h - padT - padB

        if (chartW <= 0 || chartH <= 0) return

        val values = dataPoints.map { it.second }
        val minVal = (values.minOrNull() ?: 0.0) * 0.97
        val maxVal = (values.maxOrNull() ?: 0.0) * 1.03
        val valRange = max(maxVal - minVal, 50.0)

        val timestamps = dataPoints.map { it.first }
        val minTime = timestamps.minOrNull() ?: return
        val maxTime = timestamps.maxOrNull() ?: return
        val timeRange = max(maxTime - minTime, 1L).toFloat()

        fun xFor(ts: Long): Float =
            padL + ((ts - minTime) / timeRange) * chartW

        fun yFor(v: Double): Float =
            padT + chartH - ((v - minVal) / valRange * chartH).toFloat()

        // Draw baseline
        canvas.drawLine(padL, padT + chartH, padL + chartW, padT + chartH, axisPaint)

        if (dataPoints.size == 1) {
            // Single point: just a dot + label
            val x = padL + chartW / 2
            val y = padT + chartH / 2
            canvas.drawCircle(x, y, 8f, dotPaint)
            val p = dataPoints[0]
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("${p.second.roundToInt()} g", x, y - 16f, labelPaint)
            canvas.drawText(sdf.format(Date(p.first)), x, h - 4f, labelPaint)
            return
        }

        // Fill area under the line
        val fillPath = Path()
        fillPath.moveTo(xFor(dataPoints.first().first), padT + chartH)
        dataPoints.forEach { (ts, v) -> fillPath.lineTo(xFor(ts), yFor(v)) }
        fillPath.lineTo(xFor(dataPoints.last().first), padT + chartH)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Draw line
        val linePath = Path()
        linePath.moveTo(xFor(dataPoints.first().first), yFor(dataPoints.first().second))
        dataPoints.drop(1).forEach { (ts, v) -> linePath.lineTo(xFor(ts), yFor(v)) }
        canvas.drawPath(linePath, linePaint)

        // Draw dots and labels
        // Show labels for every point if <= 5, otherwise only first and last
        val showAllLabels = dataPoints.size <= 5
        val labelIndices = if (showAllLabels) {
            dataPoints.indices.toSet()
        } else {
            setOf(0, dataPoints.lastIndex)
        }

        dataPoints.forEachIndexed { i, (ts, v) ->
            val x = xFor(ts)
            val y = yFor(v)
            canvas.drawCircle(x, y, 6f, dotPaint)
            if (i in labelIndices) {
                labelPaint.textAlign = Paint.Align.CENTER
                // Clamp x so labels don't go out of bounds
                val lx = x.coerceIn(padL + 30f, padL + chartW - 30f)
                canvas.drawText("${v.roundToInt()} g", lx, y - 14f, labelPaint)
                canvas.drawText(sdf.format(Date(ts)), lx, h - 4f, labelPaint)
            }
        }
    }
}
