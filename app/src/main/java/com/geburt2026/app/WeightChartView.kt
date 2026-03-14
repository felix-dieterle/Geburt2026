package com.geburt2026.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
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

    /** Solid orange line – actual daily weight-change between measurements. */
    private val diffLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E65100")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val diffDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E65100")
        style = Paint.Style.FILL
    }

    /** Dashed green line – recommended daily weight-change (derivative of recommendation). */
    private val diffRecommendedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    /** Horizontal zero-line for the difference chart. */
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val sectionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        textSize = 22f
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.DEFAULT_BOLD
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

    /**
     * Returns the recommended daily weight change (g/day) at [daysSinceBirth],
     * i.e. the derivative of [recommendedWeightAt]:
     *  - Days 0–5:  −birthWeight × 1.4 % / day  (initial loss)
     *  - Days 5–14: +birthWeight × 0.07 / 9 g/day  (≈ 0.78 % / day, recovery)
     *  - Day 14+:   +25 g/day                    (steady gain)
     */
    private fun recommendedDiffAt(daysSinceBirth: Double): Double {
        if (birthWeight <= 0) return 0.0
        return when {
            daysSinceBirth <= 5.0 -> -birthWeight * 0.014
            daysSinceBirth <= 14.0 -> birthWeight * 0.07 / 9.0
            else -> 25.0
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val padL = 16f
        val padR = 16f
        val padT = 42f   // space for legend row above main chart
        val chartW = w - padL - padR

        val hasBirthData = birthWeight > 0 && birthTimestamp > 0L
        val hasDiffs = dataPoints.size >= 2

        // ── Vertical layout ──────────────────────────────────────────────────
        // When we have enough data to show the diff section, split the canvas.
        // Otherwise the whole height is used for the main chart.
        val diffSectionVisible = hasDiffs
        val mainChartBottom: Float
        val padB_main: Float
        val diffTop: Float

        if (diffSectionVisible) {
            padB_main = 24f          // date labels below main chart
            mainChartBottom = h * 0.53f
            diffTop = mainChartBottom + padB_main + 4f  // separator gap
        } else {
            padB_main = 28f
            mainChartBottom = h - padB_main
            diffTop = 0f
        }
        val mainChartH = mainChartBottom - padT

        if (chartW <= 0 || mainChartH <= 0) return

        // ── Time axis (shared) ────────────────────────────────────────────────
        val timestamps = dataPoints.map { it.first }
        val minTime = timestamps.minOrNull() ?: return
        val maxTime = timestamps.maxOrNull() ?: return
        val chartMinTime = if (hasBirthData) minOf(minTime, birthTimestamp) else minTime
        val chartMaxTime = maxTime
        val timeRange = max(chartMaxTime - chartMinTime, 1L).toFloat()

        fun xFor(ts: Long): Float =
            padL + ((ts - chartMinTime) / timeRange) * chartW

        // ── Main chart value range ────────────────────────────────────────────
        val values = dataPoints.map { it.second }.toMutableList()
        if (hasBirthData) {
            val daysCovered = (maxTime - birthTimestamp).toDouble() / msPerDay
            listOf(0.0, 5.0, 14.0, daysCovered).forEach { d -> values.add(recommendedWeightAt(d)) }
            values.add(birthWeight * 0.90)
        }
        val minVal = (values.minOrNull() ?: 0.0) * 0.97
        val maxVal = (values.maxOrNull() ?: 0.0) * 1.03
        val valRange = max(maxVal - minVal, 50.0)

        fun yForMain(v: Double): Float =
            padT + mainChartH - ((v - minVal) / valRange * mainChartH).toFloat()

        // ── Baseline for main chart ───────────────────────────────────────────
        canvas.drawLine(padL, padT + mainChartH, padL + chartW, padT + mainChartH, axisPaint)

        // ── Recommended progression line (dashed green) ───────────────────────
        if (hasBirthData) {
            val lastDays = (chartMaxTime - birthTimestamp).toDouble() / msPerDay
            val endDays = maxOf(lastDays, 14.0)
            val steps = (endDays * 2).toInt().coerceAtLeast(30)

            val recPath = Path()
            var first = true
            for (i in 0..steps) {
                val d = i * endDays / steps
                val ts = birthTimestamp + (d * msPerDay).toLong()
                val x = xFor(ts)
                val y = yForMain(recommendedWeightAt(d))
                if (first) { recPath.moveTo(x, y); first = false } else recPath.lineTo(x, y)
            }
            canvas.drawPath(recPath, recommendedLinePaint)

            // Critical 10 % weight loss line (dashed red)
            val critY = yForMain(birthWeight * 0.90)
            canvas.drawLine(padL, critY, padL + chartW, critY, criticalLinePaint)

            // Legend row
            val legendY = padT - 4f
            val swatchSize = 18f
            val gap = 6f
            var lx = padL

            legendPaint.color = Color.parseColor("#2E7D32")
            canvas.drawRect(lx, legendY - swatchSize, lx + swatchSize, legendY, legendPaint)
            legendPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("Empfehlung", lx + swatchSize + gap, legendY, legendPaint)
            lx += swatchSize + gap + legendPaint.measureText("Empfehlung") + 14f

            legendPaint.color = Color.parseColor("#C62828")
            canvas.drawRect(lx, legendY - swatchSize, lx + swatchSize, legendY, legendPaint)
            canvas.drawText("Kritisch −10%", lx + swatchSize + gap, legendY, legendPaint)
        }

        // ── Actual measurement data ───────────────────────────────────────────
        if (dataPoints.size == 1) {
            val x = xFor(dataPoints[0].first)
            val y = yForMain(dataPoints[0].second)
            canvas.drawCircle(x, y, 8f, dotPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            val lx = x.coerceIn(padL + 30f, padL + chartW - 30f)
            canvas.drawText("${dataPoints[0].second.roundToInt()} g", lx, y - 16f, labelPaint)
            canvas.drawText(sdf.format(Date(dataPoints[0].first)), lx, mainChartBottom + 18f, labelPaint)
            return
        }

        // Fill area under the line
        val fillPath = Path()
        fillPath.moveTo(xFor(dataPoints.first().first), padT + mainChartH)
        dataPoints.forEach { (ts, v) -> fillPath.lineTo(xFor(ts), yForMain(v)) }
        fillPath.lineTo(xFor(dataPoints.last().first), padT + mainChartH)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Line
        val linePath = Path()
        linePath.moveTo(xFor(dataPoints.first().first), yForMain(dataPoints.first().second))
        dataPoints.drop(1).forEach { (ts, v) -> linePath.lineTo(xFor(ts), yForMain(v)) }
        canvas.drawPath(linePath, linePaint)

        // Dots and weight labels; date labels just below main chart
        val showAllLabels = dataPoints.size <= 5
        val labelIndices = if (showAllLabels) dataPoints.indices.toSet()
                           else setOf(0, dataPoints.lastIndex)

        dataPoints.forEachIndexed { i, (ts, v) ->
            val x = xFor(ts)
            val y = yForMain(v)
            canvas.drawCircle(x, y, 6f, dotPaint)
            if (i in labelIndices) {
                labelPaint.textAlign = Paint.Align.CENTER
                val lx = x.coerceIn(padL + 30f, padL + chartW - 30f)
                canvas.drawText("${v.roundToInt()} g", lx, y - 14f, labelPaint)
                canvas.drawText(sdf.format(Date(ts)), lx, mainChartBottom + 18f, labelPaint)
            }
        }

        // ── Difference section ────────────────────────────────────────────────
        if (!diffSectionVisible) return

        // --- Compute actual daily differences (g/day) -------------------------
        // diffPoints: (timestamp of 2nd measurement, g/day change)
        val diffPoints = mutableListOf<Pair<Long, Double>>()
        for (i in 1 until dataPoints.size) {
            val (t1, w1) = dataPoints[i - 1]
            val (t2, w2) = dataPoints[i]
            val days = (t2 - t1).toDouble() / msPerDay
            if (days > 0.0) diffPoints.add(Pair(t2, (w2 - w1) / days))
        }

        // --- Diff chart value range -------------------------------------------
        val diffValues = diffPoints.map { it.second }.toMutableList()
        if (hasBirthData) {
            // Include key recommended-diff values in the range
            val lastDays = (chartMaxTime - birthTimestamp).toDouble() / msPerDay
            listOf(0.0, 5.0, 14.0, lastDays).forEach { d -> diffValues.add(recommendedDiffAt(d)) }
        }
        diffValues.add(0.0) // always include 0 in range

        val diffMinRaw = diffValues.minOrNull() ?: 0.0
        val diffMaxRaw = diffValues.maxOrNull() ?: 0.0
        val diffPad = max(abs(diffMaxRaw - diffMinRaw) * 0.15, 5.0)
        val diffMin = diffMinRaw - diffPad
        val diffMax = diffMaxRaw + diffPad
        val diffRange = max(diffMax - diffMin, 10.0)

        // --- Section separator + label ----------------------------------------
        canvas.drawLine(padL, diffTop - 2f, padL + chartW, diffTop - 2f, axisPaint)

        canvas.drawText("Tägliche Veränderung (g/Tag)", padL, diffTop + 18f, sectionLabelPaint)

        // Diff legend (top-right of section)
        val dLegendY = diffTop + 18f
        val swatchSize = 14f
        val gap = 5f
        var dlx = padL + sectionLabelPaint.measureText("Tägliche Veränderung (g/Tag)") + 12f
        if (dlx + 140f > w) dlx = w - 140f // clamp to view width

        legendPaint.color = Color.parseColor("#E65100")
        legendPaint.textAlign = Paint.Align.LEFT
        canvas.drawRect(dlx, dLegendY - swatchSize, dlx + swatchSize, dLegendY, legendPaint)
        canvas.drawText("Ist", dlx + swatchSize + gap, dLegendY, legendPaint)
        dlx += swatchSize + gap + legendPaint.measureText("Ist") + 10f

        if (hasBirthData) {
            legendPaint.color = Color.parseColor("#2E7D32")
            canvas.drawRect(dlx, dLegendY - swatchSize, dlx + swatchSize, dLegendY, legendPaint)
            canvas.drawText("Empfohlen", dlx + swatchSize + gap, dLegendY, legendPaint)
        }

        val diffChartAreaTop = diffTop + 26f   // below label
        val diffChartAreaH = h - 24f - diffChartAreaTop

        fun yForDiffSection(v: Double): Float =
            diffChartAreaTop + diffChartAreaH - ((v - diffMin) / diffRange * diffChartAreaH).toFloat()

        // --- Zero reference line ----------------------------------------------
        val zeroY = yForDiffSection(0.0)
        canvas.drawLine(padL, zeroY, padL + chartW, zeroY, zeroLinePaint)

        // Zero label
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("0", padL, zeroY - 3f, labelPaint)

        // --- Recommended diff line (dashed green) -----------------------------
        if (hasBirthData) {
            val lastDays = (chartMaxTime - birthTimestamp).toDouble() / msPerDay
            val endDays = maxOf(lastDays, 14.0)
            val steps = (endDays * 2).toInt().coerceAtLeast(30)

            val recDiffPath = Path()
            var first = true
            for (i in 0..steps) {
                val d = i * endDays / steps
                val ts = birthTimestamp + (d * msPerDay).toLong()
                val x = xFor(ts)
                val y = yForDiffSection(recommendedDiffAt(d))
                if (first) { recDiffPath.moveTo(x, y); first = false } else recDiffPath.lineTo(x, y)
            }
            canvas.drawPath(recDiffPath, diffRecommendedPaint)
        }

        // --- Actual diff line and dots ----------------------------------------
        if (diffPoints.size >= 2) {
            val diffPath = Path()
            diffPath.moveTo(xFor(diffPoints.first().first), yForDiffSection(diffPoints.first().second))
            diffPoints.drop(1).forEach { (ts, dv) -> diffPath.lineTo(xFor(ts), yForDiffSection(dv)) }
            canvas.drawPath(diffPath, diffLinePaint)
        }

        diffPoints.forEach { (ts, dv) ->
            val x = xFor(ts)
            val y = yForDiffSection(dv)
            canvas.drawCircle(x, y, 5f, diffDotPaint)
            // Label the value above/below dot depending on sign
            labelPaint.textAlign = Paint.Align.CENTER
            val lx = x.coerceIn(padL + 28f, padL + chartW - 28f)
            val label = "${if (dv >= 0) "+" else ""}${dv.roundToInt()}"
            val labelY = if (dv >= 0) y - 10f else y + 20f
            canvas.drawText(label, lx, labelY, labelPaint)
        }

        // Bottom date labels for the diff section (first and last diff point)
        if (diffPoints.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            val firstX = xFor(diffPoints.first().first).coerceIn(padL + 30f, padL + chartW - 30f)
            canvas.drawText(sdf.format(Date(diffPoints.first().first)), firstX, h - 4f, labelPaint)
            if (diffPoints.size > 1) {
                val lastX = xFor(diffPoints.last().first).coerceIn(padL + 30f, padL + chartW - 30f)
                canvas.drawText(sdf.format(Date(diffPoints.last().first)), lastX, h - 4f, labelPaint)
            }
        }
    }
}
