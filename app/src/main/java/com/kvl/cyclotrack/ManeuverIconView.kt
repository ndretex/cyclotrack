package com.kvl.cyclotrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ManeuverIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private var maneuver: ManeuverDirection = ManeuverDirection.STRAIGHT
    private var state: GuidanceUiState = GuidanceUiState.GUIDANCE
    private var turnAngleDegrees: Double? = null

    fun setGuidance(
        state: GuidanceUiState,
        maneuver: ManeuverDirection,
        turnAngleDegrees: Double? = null,
    ) {
        this.state = state
        this.maneuver = maneuver
        this.turnAngleDegrees = turnAngleDegrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (state) {
            GuidanceUiState.OFF_ROUTE -> {
                drawWarning(canvas, Color.parseColor("#FF7043"))
                return
            }

            GuidanceUiState.GPS_WEAK -> {
                drawWarning(canvas, Color.parseColor("#BDBDBD"))
                return
            }

            GuidanceUiState.ARRIVAL -> {
                drawFinish(canvas)
                return
            }

            else -> Unit
        }

        if (maneuver == ManeuverDirection.FINISH) {
            drawFinish(canvas)
            return
        }

        strokePaint.color = Color.WHITE
        fillPaint.color = Color.WHITE

        val routePoints = buildArrowRoute()

        drawArrowRoute(canvas, routePoints)
    }

    private fun buildArrowRoute(): List<Pair<Float, Float>> {
        val requestedAngle = turnAngleDegrees ?: defaultAngleForManeuver(maneuver)
        val normalizedAngle = requestedAngle.coerceIn(-175.0, 175.0)
        val absoluteAngle = abs(normalizedAngle)

        if (absoluteAngle < 12.0) {
            return listOf(
                0.5f to 0.88f,
                0.5f to 0.16f
            )
        }

        if (absoluteAngle >= 150.0) {
            val direction = if (normalizedAngle < 0.0) -1f else 1f
            return listOf(
                0.5f to 0.88f,
                0.5f to 0.54f,
                (0.5f + 0.18f * direction) to 0.28f,
                (0.5f + 0.18f * direction) to 0.74f
            )
        }

        val pivotX = 0.5f
        val pivotY = 0.56f
        val segmentLength = 0.34f
        val radians = Math.toRadians(normalizedAngle)
        val endX = (pivotX + sin(radians).toFloat() * segmentLength).coerceIn(0.08f, 0.92f)
        val endY = (pivotY - cos(radians).toFloat() * segmentLength).coerceIn(0.12f, 0.90f)
        val controlYOffset = min(0.10f, absoluteAngle.toFloat() / 1000f)

        return listOf(
            0.5f to 0.88f,
            0.5f to 0.68f,
            pivotX to (pivotY + controlYOffset),
            endX to endY
        )
    }

    private fun defaultAngleForManeuver(maneuver: ManeuverDirection): Double = when (maneuver) {
        ManeuverDirection.STRAIGHT -> 0.0
        ManeuverDirection.SLIGHT_LEFT -> -35.0
        ManeuverDirection.LEFT -> -90.0
        ManeuverDirection.SHARP_LEFT -> -135.0
        ManeuverDirection.U_TURN -> 180.0
        ManeuverDirection.SLIGHT_RIGHT -> 35.0
        ManeuverDirection.RIGHT -> 90.0
        ManeuverDirection.SHARP_RIGHT -> 135.0
        ManeuverDirection.FINISH -> 0.0
    }

    private fun drawArrowRoute(
        canvas: Canvas,
        normalizedPoints: List<Pair<Float, Float>>,
    ) {
        if (normalizedPoints.size < 2) return

        val widthScale = width.toFloat()
        val heightScale = height.toFloat()
        val path = Path()
        normalizedPoints.forEachIndexed { index, point ->
            val x = point.first * widthScale
            val y = point.second * heightScale
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, strokePaint)

        val last = normalizedPoints.last()
        val previous = normalizedPoints[normalizedPoints.lastIndex - 1]
        drawArrowHead(
            canvas = canvas,
            x = last.first * widthScale,
            y = last.second * heightScale,
            angleRadians = atan2(
                (last.second - previous.second).toDouble(),
                (last.first - previous.first).toDouble()
            ).toFloat()
        )
    }

    private fun drawArrowHead(
        canvas: Canvas,
        x: Float,
        y: Float,
        angleRadians: Float,
    ) {
        val headLength = width.coerceAtMost(height) * 0.18f
        val spread = Math.toRadians(28.0).toFloat()
        val leftX = x - headLength * cos(angleRadians - spread)
        val leftY = y - headLength * sin(angleRadians - spread)
        val rightX = x - headLength * cos(angleRadians + spread)
        val rightY = y - headLength * sin(angleRadians + spread)
        val arrowHead = Path().apply {
            moveTo(x, y)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.drawPath(arrowHead, fillPaint)
    }

    private fun drawFinish(canvas: Canvas) {
        strokePaint.color = Color.WHITE
        fillPaint.color = Color.WHITE

        val poleX = width * 0.38f
        val poleTop = height * 0.16f
        val poleBottom = height * 0.88f
        canvas.drawLine(poleX, poleBottom, poleX, poleTop, strokePaint)

        val flag = Path().apply {
            moveTo(poleX, poleTop)
            lineTo(width * 0.72f, height * 0.24f)
            lineTo(poleX, height * 0.36f)
            close()
        }
        canvas.drawPath(flag, fillPaint)
    }

    private fun drawWarning(
        canvas: Canvas,
        color: Int,
    ) {
        strokePaint.color = color
        fillPaint.color = color

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = width.coerceAtMost(height) * 0.28f
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
        canvas.drawLine(centerX, centerY - radius * 0.55f, centerX, centerY + radius * 0.15f, strokePaint)
        canvas.drawCircle(centerX, centerY + radius * 0.48f, strokePaint.strokeWidth / 2f, fillPaint)
    }
}
