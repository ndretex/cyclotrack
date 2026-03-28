package com.kvl.cyclotrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
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

    fun setGuidance(
        state: GuidanceUiState,
        maneuver: ManeuverDirection,
    ) {
        this.state = state
        this.maneuver = maneuver
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

        val routePoints = when (maneuver) {
            ManeuverDirection.STRAIGHT -> listOf(
                0.5f to 0.88f,
                0.5f to 0.18f
            )

            ManeuverDirection.SLIGHT_LEFT -> listOf(
                0.5f to 0.88f,
                0.5f to 0.60f,
                0.44f to 0.40f,
                0.34f to 0.18f
            )

            ManeuverDirection.LEFT -> listOf(
                0.5f to 0.88f,
                0.5f to 0.58f,
                0.38f to 0.36f,
                0.20f to 0.18f
            )

            ManeuverDirection.SHARP_LEFT -> listOf(
                0.5f to 0.88f,
                0.5f to 0.66f,
                0.44f to 0.50f,
                0.24f to 0.34f,
                0.10f to 0.30f
            )

            ManeuverDirection.SLIGHT_RIGHT -> listOf(
                0.5f to 0.88f,
                0.5f to 0.60f,
                0.56f to 0.40f,
                0.66f to 0.18f
            )

            ManeuverDirection.RIGHT -> listOf(
                0.5f to 0.88f,
                0.5f to 0.58f,
                0.62f to 0.36f,
                0.80f to 0.18f
            )

            ManeuverDirection.SHARP_RIGHT -> listOf(
                0.5f to 0.88f,
                0.5f to 0.66f,
                0.56f to 0.50f,
                0.76f to 0.34f,
                0.90f to 0.30f
            )

            ManeuverDirection.U_TURN -> listOf(
                0.5f to 0.88f,
                0.5f to 0.40f,
                0.28f to 0.30f,
                0.28f to 0.64f
            )

            ManeuverDirection.FINISH -> emptyList()
        }

        drawArrowRoute(canvas, routePoints)
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
