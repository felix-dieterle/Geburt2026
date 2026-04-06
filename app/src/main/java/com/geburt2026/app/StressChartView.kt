package com.geburt2026.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.*

/**
 * A custom View that renders a theoretical stress-level curve for parents of a newborn.
 *
 * Two curves are shown:
 *  1. **Babygeschrei-Stress** – crying-driven stress, peaking around weeks 4–6 after birth.
 *  2. **Familien-Stress**     – overall family stress accounting for sibling dynamics.
 *
 * The background is divided into three colour zones:
 *  • 🟢 Green  (0–40 %)   – manageable
 *  • 🟡 Yellow (40–70 %)  – elevated, pay attention
 *  • 🔴 Red    (70–100 %) – high – seek support
 *
 * Call [setBirthTimestamp] to anchor the curves; the view covers
 * 6 weeks before birth through 13 weeks (≈ 3 months) after birth.
 *
 * The model is pre-configured for a household with:
 *  – Two siblings aged 4.5 and 7 years
 *  – 4-room apartment
 *  – Parents aged 44 and 39 years
 */
class StressChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Unix timestamp (ms) of birth – used as the timeline anchor. */
    private var birthTimestamp: Long = 0L

    private val msPerDay = 24L * 60 * 60 * 1000

    private val sdf = SimpleDateFormat("dd.MM.", Locale.GERMAN)

    // ── Paints ─────────────────────────────────────────────────────────────────

    private val greenZonePaint = Paint().apply {
        color = Color.parseColor("#4488C34A")
        style = Paint.Style.FILL
    }
    private val yellowZonePaint = Paint().apply {
        color = Color.parseColor("#44FFC107")
        style = Paint.Style.FILL
    }
    private val redZonePaint = Paint().apply {
        color = Color.parseColor("#44F44336")
        style = Paint.Style.FILL
    }

    /** Solid blue line – baby-crying stress. */
    private val cryingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /** Dashed orange line – general family stress (siblings included). */
    private val familyLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E65100")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }

    /** Thin dashed purple vertical line marking today. */
    private val todayLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7B1FA2")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }

    /** Thin dashed navy vertical line marking the birth date. */
    private val birthLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D47A1")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBBBBB")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDDDDD")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        textSize = 22f
        textAlign = Paint.Align.RIGHT
    }

    private val todayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7B1FA2")
        textSize = 20f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val birthLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D47A1")
        textSize = 20f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        textSize = 22f
        textAlign = Paint.Align.LEFT
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Set the birth timestamp so the curves are anchored correctly.
     * If 0 is passed, the current time is used as a fallback anchor.
     */
    fun setBirthTimestamp(ts: Long) {
        birthTimestamp = ts
        invalidate()
    }

    // ── Stress model ───────────────────────────────────────────────────────────

    /**
     * Baby-crying stress level (0–100) at [weeksSinceBirth].
     *
     * Model:
     *  • < −6 w  : 22 % – pre-pregnancy baseline
     *  • −6…0 w  : rising anticipatory stress → 57 %
     *  • 0…2 w   : rapid rise (sleep deprivation) → 75 %
     *  • 2…4 w   : peak crying phase → 85 %
     *  • 4…6.5 w : slight plateau / start of decline → 80 %
     *  • 6.5…8 w : improving → 70 %
     *  • 8…10 w  : further improvement → 55 %
     *  • 10…13 w : continued improvement → 40 %
     *  • > 13 w  : 40 % (levelling off)
     */
    private fun cryingStressAt(weeksSinceBirth: Double): Double {
        return when {
            weeksSinceBirth < -6.0 -> 22.0
            weeksSinceBirth < 0.0  -> 22.0 + (weeksSinceBirth + 6.0) * (35.0 / 6.0)
            weeksSinceBirth < 2.0  -> 57.0 + weeksSinceBirth * (18.0 / 2.0)
            weeksSinceBirth < 4.0  -> 75.0 + (weeksSinceBirth - 2.0) * (10.0 / 2.0)
            weeksSinceBirth < 6.5  -> 85.0 - (weeksSinceBirth - 4.0) * (5.0 / 2.5)
            weeksSinceBirth < 8.0  -> 80.0 - (weeksSinceBirth - 6.5) * (10.0 / 1.5)
            weeksSinceBirth < 10.0 -> 70.0 - (weeksSinceBirth - 8.0) * (15.0 / 2.0)
            weeksSinceBirth < 13.0 -> 55.0 - (weeksSinceBirth - 10.0) * (15.0 / 3.0)
            else                   -> 40.0
        }.coerceIn(0.0, 100.0)
    }

    /**
     * General family stress level (0–100) at [weeksSinceBirth].
     *
     * Pre-configured for:
     *  – 2 siblings (4.5 y and 7 y)  → sibling-jealousy bonus weeks 0–8
     *  – 4-room apartment             → space pressure factor
     *  – Parents aged 44 and 39       → slightly elevated fatigue baseline
     *
     * Sibling bonus:
     *  • < −4 w : +10 % (school/Kita logistics)
     *  • −4…0 w : +13 % (preparing siblings for new arrival)
     *  • 0…8 w  : +18 % (sibling jealousy / regression peak)
     *  • 8…10 w : +15 %
     *  • > 10 w : +10 % (adjusting to new routine)
     */
    private fun familyStressAt(weeksSinceBirth: Double): Double {
        val siblingBonus = when {
            weeksSinceBirth < -4.0 -> 10.0
            weeksSinceBirth < 0.0  -> 13.0
            weeksSinceBirth < 8.0  -> 18.0
            weeksSinceBirth < 10.0 -> 15.0
            else                   -> 10.0
        }
        val base = when {
            weeksSinceBirth < -6.0 -> 28.0
            weeksSinceBirth < -4.0 -> 28.0 + (weeksSinceBirth + 6.0) * (7.0 / 2.0)
            weeksSinceBirth < -2.0 -> 35.0 + (weeksSinceBirth + 4.0) * (7.0 / 2.0)
            weeksSinceBirth < 0.0  -> 42.0 + (weeksSinceBirth + 2.0) * (20.0 / 2.0)
            weeksSinceBirth < 1.0  -> 62.0 + weeksSinceBirth * 12.0
            weeksSinceBirth < 4.0  -> 74.0 + (weeksSinceBirth - 1.0) * (4.0 / 3.0)
            weeksSinceBirth < 6.0  -> 78.0
            weeksSinceBirth < 8.0  -> 78.0 - (weeksSinceBirth - 6.0) * 5.0
            weeksSinceBirth < 10.0 -> 68.0 - (weeksSinceBirth - 8.0) * 8.0
            weeksSinceBirth < 13.0 -> 52.0 - (weeksSinceBirth - 10.0) * 4.0
            else                   -> 40.0
        }
        return (base + siblingBonus).coerceIn(0.0, 100.0)
    }

    // ── Drawing ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val vw = width.toFloat()
        val vh = height.toFloat()

        // Layout margins
        val padL = 56f   // room for y-axis percentage labels
        val padR = 12f
        val padT = 56f   // room for two legend rows
        val padB = 42f   // room for x-axis date labels

        val chartL = padL
        val chartR = vw - padR
        val chartT = padT
        val chartB = vh - padB
        val chartW = chartR - chartL
        val chartH = chartB - chartT

        if (chartW <= 0f || chartH <= 0f) return

        // Anchor time: use actual birth timestamp when available, otherwise current time
        val refTime = if (birthTimestamp > 0L) birthTimestamp else System.currentTimeMillis()
        val startTime = refTime - 42L * msPerDay    // −6 weeks
        val endTime   = refTime + 91L * msPerDay    // +13 weeks  (≈ 3 months)
        val timeRange = (endTime - startTime).toFloat()
        val nowTime   = System.currentTimeMillis()

        fun timeToX(ts: Long): Float = chartL + (ts - startTime).toFloat() / timeRange * chartW
        fun stressToY(pct: Double): Float = (chartB - pct / 100.0 * chartH).toFloat()

        // ── Coloured background zones ─────────────────────────────────────────
        canvas.drawRect(chartL, stressToY(100.0), chartR, stressToY(70.0), redZonePaint)
        canvas.drawRect(chartL, stressToY(70.0),  chartR, stressToY(40.0), yellowZonePaint)
        canvas.drawRect(chartL, stressToY(40.0),  chartR, chartB,          greenZonePaint)

        // ── Horizontal grid + y-axis labels ───────────────────────────────────
        for (pct in listOf(20, 40, 60, 70, 80, 100)) {
            val y = stressToY(pct.toDouble())
            canvas.drawLine(chartL, y, chartR, y, gridPaint)
            canvas.drawText(
                "$pct%",
                chartL - 5f,
                y + yLabelPaint.textSize / 3f,
                yLabelPaint
            )
        }

        // ── Vertical week tick marks + x-axis date labels ─────────────────────
        val weekTicks = listOf(-6, -4, -2, 0, 2, 4, 6, 8, 10, 12)
        for (wk in weekTicks) {
            val ts = refTime + wk * 7L * msPerDay
            val x = timeToX(ts)
            if (x < chartL || x > chartR) continue
            canvas.drawLine(x, chartT, x, chartB, gridPaint)
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            canvas.drawText(sdf.format(cal.time), x, chartB + padB * 0.75f, xLabelPaint)
        }

        // ── Birth vertical line ───────────────────────────────────────────────
        if (birthTimestamp > 0L) {
            val bx = timeToX(birthTimestamp)
            if (bx in chartL..chartR) {
                canvas.drawLine(bx, chartT, bx, chartB, birthLinePaint)
                canvas.drawText("Geburt", bx, chartT - 6f, birthLabelPaint)
            }
        }

        // ── Today vertical line ───────────────────────────────────────────────
        val nowX = timeToX(nowTime)
        if (nowX in chartL..chartR) {
            canvas.drawLine(nowX, chartT, nowX, chartB, todayLinePaint)
            canvas.drawText("Heute", nowX, chartT - 6f, todayLabelPaint)
        }

        // ── Chart border ──────────────────────────────────────────────────────
        canvas.drawRect(chartL, chartT, chartR, chartB, axisPaint)

        // ── Family-stress curve (orange dashed) ───────────────────────────────
        val familyPath = Path()
        val steps = 200
        for (i in 0..steps) {
            val ts = startTime + i.toLong() * (endTime - startTime) / steps
            val weeks = (ts - refTime).toDouble() / (7.0 * msPerDay)
            val x = timeToX(ts)
            val y = stressToY(familyStressAt(weeks))
            if (i == 0) familyPath.moveTo(x, y) else familyPath.lineTo(x, y)
        }
        canvas.drawPath(familyPath, familyLinePaint)

        // ── Crying-stress curve (blue solid) ──────────────────────────────────
        val cryingPath = Path()
        for (i in 0..steps) {
            val ts = startTime + i.toLong() * (endTime - startTime) / steps
            val weeks = (ts - refTime).toDouble() / (7.0 * msPerDay)
            val x = timeToX(ts)
            val y = stressToY(cryingStressAt(weeks))
            if (i == 0) cryingPath.moveTo(x, y) else cryingPath.lineTo(x, y)
        }
        canvas.drawPath(cryingPath, cryingLinePaint)

        // ── Legend ─────────────────────────────────────────────────────────────
        val legendLineLen = 30f
        val legendBoxSz   = 14f
        val row1Y = 20f
        val row2Y = 44f
        var lx = chartL

        // Row 1 – curves
        val legendCryingPaint = Paint(cryingLinePaint).apply { pathEffect = null }
        canvas.drawLine(lx, row1Y, lx + legendLineLen, row1Y, legendCryingPaint)
        canvas.drawText("Babygeschrei-Stress", lx + legendLineLen + 5f, row1Y + legendTextPaint.textSize / 3f, legendTextPaint)
        lx += legendLineLen + 5f + legendTextPaint.measureText("Babygeschrei-Stress") + 14f

        canvas.drawLine(lx, row1Y, lx + legendLineLen, row1Y, familyLinePaint)
        canvas.drawText("Familien-Stress (Geschwister)", lx + legendLineLen + 5f, row1Y + legendTextPaint.textSize / 3f, legendTextPaint)

        // Row 2 – zones
        lx = chartL
        val zoneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        fun drawZoneBox(x: Float, fillColor: Int, strokeColor: String) {
            val boxPaint = Paint().apply { color = fillColor; style = Paint.Style.FILL }
            canvas.drawRect(x, row2Y - legendBoxSz, x + legendBoxSz, row2Y, boxPaint)
            zoneStrokePaint.color = Color.parseColor(strokeColor)
            canvas.drawRect(x, row2Y - legendBoxSz, x + legendBoxSz, row2Y, zoneStrokePaint)
        }

        drawZoneBox(lx, Color.parseColor("#4488C34A"), "#4CAF50")
        canvas.drawText("Gut (0–40%)", lx + legendBoxSz + 4f, row2Y - 1f, legendTextPaint)
        lx += legendBoxSz + 4f + legendTextPaint.measureText("Gut (0–40%)") + 14f

        drawZoneBox(lx, Color.parseColor("#44FFC107"), "#FFC107")
        canvas.drawText("Erhöht (40–70%)", lx + legendBoxSz + 4f, row2Y - 1f, legendTextPaint)
        lx += legendBoxSz + 4f + legendTextPaint.measureText("Erhöht (40–70%)") + 14f

        drawZoneBox(lx, Color.parseColor("#44F44336"), "#F44336")
        canvas.drawText("Hoch (70–100%)", lx + legendBoxSz + 4f, row2Y - 1f, legendTextPaint)
    }
}
