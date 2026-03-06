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

    /** Birth weight in grams; 0 means no birth data available. */
    private var birthWeight: Double = 0.0

    /** Unix timestamp (ms) of birth; 0 means unknown. */
    private var birthTimestamp: Long = 0L

    // ── Paints ────────────────────────────────────────────────────────────────

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

    /** Dashed green line – WHO-based recommended weight progression. */
    private val recommendedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }

    /** Dashed red line – critical 10 % weight loss threshold. */
    private val criticalLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        textAlign = Paint.Align.LEFT
    }

    private val sdf = SimpleDateFormat("dd.MM", Locale.GERMAN)

    // ── Public API ────────────────────────────────────────────────────────────

    fun setData(points: List<Pair<Long, Double>>) {
        dataPoints.clear()
        dataPoints.addAll(points.sortedBy { it.first })
        invalidate()
    }

    /**
     * Provide birth data so that the recommended progression and critical
     * marker lines can be rendered.
     *
     * @param birthWeight     Birth weight in grams (> 0 to enable the feature).
     * @param birthTimestamp  Unix timestamp (ms) of the moment of birth.
     */
    fun setBirthData(birthWeight: Double, birthTimestamp: Long) {
        this.birthWeight = birthWeight
        this.birthTimestamp = birthTimestamp
        invalidate()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the WHO/medical recommended weight (g) for [daysSinceBirth]
     * days after birth, based on a piecewise model:
     *  - Days 0–5: linear loss to 93 % (−7 % nadir)
     *  - Days 5–14: linear recovery back to birth weight
     *  - Day 14+: gain of ~25 g/day
     */
    private fun recommendedWeightAt(daysSinceBirth: Double): Double {
        if (birthWeight <= 0) return 0.0
        return when {
            daysSinceBirth < 0 -> birthWeight
            daysSinceBirth <= 5.0 -> birthWeight * (1.0 - 0.014 * daysSinceBirth)
            daysSinceBirth <= 14.0 -> birthWeight * (0.93 + 0.07 * (daysSinceBirth - 5.0) / 9.0)
            else -> birthWeight + 25.0 * (daysSinceBirth - 14.0)
        }
    }

    private val msPerDay = 24 * 60 * 60 * 1000L

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val padL = 16f
        val padR = 16f
        val padT = 42f   // space for weight labels above dots (+ legend row)
        val padB = 28f   // space for date labels below

        val chartW = w - padL - padR
        val chartH = h - padT - padB

        if (chartW <= 0 || chartH <= 0) return

        val hasBirthData = birthWeight > 0 && birthTimestamp > 0L

        // --- Compute value range, including recommended/critical values -------
        val values = dataPoints.map { it.second }.toMutableList()

        val timestamps = dataPoints.map { it.first }
        val minTime = timestamps.minOrNull() ?: return
        val maxTime = timestamps.maxOrNull() ?: return

        if (hasBirthData) {
            // Extend time axis to cover at least birth to last measurement
            val daysCovered = (maxTime - birthTimestamp).toDouble() / msPerDay
            // Also include recommended values in the value range
            val keyDays = listOf(0.0, 5.0, 14.0, daysCovered)
            keyDays.forEach { d -> values.add(recommendedWeightAt(d)) }
            values.add(birthWeight * 0.90) // critical line value
        }

        val minVal = (values.minOrNull() ?: 0.0) * 0.97
        val maxVal = (values.maxOrNull() ?: 0.0) * 1.03
        val valRange = max(maxVal - minVal, 50.0)

        // Include birth timestamp in the time range when available
        val chartMinTime = if (hasBirthData) minOf(minTime, birthTimestamp) else minTime
        val chartMaxTime = maxTime
        val timeRange = max(chartMaxTime - chartMinTime, 1L).toFloat()

        fun xFor(ts: Long): Float =
            padL + ((ts - chartMinTime) / timeRange) * chartW

        fun yFor(v: Double): Float =
            padT + chartH - ((v - minVal) / valRange * chartH).toFloat()

        // --- Baseline ---------------------------------------------------------
        canvas.drawLine(padL, padT + chartH, padL + chartW, padT + chartH, axisPaint)

        // --- Recommended progression line (dashed green) ----------------------
        if (hasBirthData) {
            val lastDays = (chartMaxTime - birthTimestamp).toDouble() / msPerDay
            val endDays = maxOf(lastDays, 14.0) // always draw at least to day 14
            val steps = (endDays * 2).toInt().coerceAtLeast(30)

            val recPath = Path()
            var first = true
            for (i in 0..steps) {
                val d = i * endDays / steps
                val ts = birthTimestamp + (d * msPerDay).toLong()
                val x = xFor(ts)
                val y = yFor(recommendedWeightAt(d))
                if (first) { recPath.moveTo(x, y); first = false } else recPath.lineTo(x, y)
            }
            canvas.drawPath(recPath, recommendedLinePaint)

            // --- Critical 10 % weight loss line (dashed red) ------------------
            val criticalWeight = birthWeight * 0.90
            val critY = yFor(criticalWeight)
            canvas.drawLine(padL, critY, padL + chartW, critY, criticalLinePaint)

            // --- Legend (top-right) -------------------------------------------
            val legendY = padT - 4f
            val swatchSize = 18f
            val gap = 6f
            var lx = padL

            // Green swatch + label
            legendPaint.color = Color.parseColor("#2E7D32")
            canvas.drawRect(lx, legendY - swatchSize, lx + swatchSize, legendY, legendPaint)
            legendPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("Empfehlung", lx + swatchSize + gap, legendY, legendPaint)

            lx += swatchSize + gap + legendPaint.measureText("Empfehlung") + 14f

            // Red swatch + label
            legendPaint.color = Color.parseColor("#C62828")
            canvas.drawRect(lx, legendY - swatchSize, lx + swatchSize, legendY, legendPaint)
            canvas.drawText("Kritisch −10%", lx + swatchSize + gap, legendY, legendPaint)
        }

        // --- Actual measurement data ------------------------------------------
        if (dataPoints.size == 1) {
            val x = xFor(dataPoints[0].first)
            val y = yFor(dataPoints[0].second)
            canvas.drawCircle(x, y, 8f, dotPaint)
            val p = dataPoints[0]
            labelPaint.textAlign = Paint.Align.CENTER
            val lx = x.coerceIn(padL + 30f, padL + chartW - 30f)
            canvas.drawText("${p.second.roundToInt()} g", lx, y - 16f, labelPaint)
            canvas.drawText(sdf.format(Date(p.first)), lx, h - 4f, labelPaint)
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
                val lx = x.coerceIn(padL + 30f, padL + chartW - 30f)
                canvas.drawText("${v.roundToInt()} g", lx, y - 14f, labelPaint)
                canvas.drawText(sdf.format(Date(ts)), lx, h - 4f, labelPaint)
            }
        }
    }
}
