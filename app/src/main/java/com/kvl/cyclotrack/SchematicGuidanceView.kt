package com.kvl.cyclotrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class SchematicGuidanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 220
    }

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 230
    }

    private val riderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        style = Paint.Style.FILL
    }

    private var previewPoints: List<GuidancePreviewPoint> = emptyList()
    private var currentIndex: Int = -1
    private var cueIndex: Int = -1

    fun setPreview(
        previewPoints: List<GuidancePreviewPoint>,
        currentIndex: Int,
        cueIndex: Int,
    ) {
        this.previewPoints = previewPoints
        this.currentIndex = currentIndex
        this.cueIndex = cueIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (previewPoints.size < 2 || currentIndex !in previewPoints.indices) return

        val currentPoint = previewPoints[currentIndex]
        val referenceHeadingDegrees = when {
            cueIndex in (currentIndex + 1)..previewPoints.lastIndex ->
                bearingDegrees(currentPoint, previewPoints[cueIndex])
            currentIndex < previewPoints.lastIndex ->
                bearingDegrees(currentPoint, previewPoints[currentIndex + 1])
            else -> 0.0
        }
        val rotationRadians = Math.toRadians(referenceHeadingDegrees)

        val transformedPoints = previewPoints.map { point ->
            val localX = longitudeToMeters(currentPoint.longitude, point.longitude, currentPoint.latitude)
            val localY = latitudeToMeters(currentPoint.latitude, point.latitude)
            val rotatedX = localX * cos(rotationRadians) - localY * sin(rotationRadians)
            val rotatedY = localX * sin(rotationRadians) + localY * cos(rotationRadians)
            Pair(rotatedX.toFloat(), rotatedY.toFloat())
        }

        val maxAbsX = transformedPoints.maxOf { abs(it.first) }.coerceAtLeast(1f)
        val maxAhead = transformedPoints.maxOf { it.second }.coerceAtLeast(1f)
        val maxBehind = abs(transformedPoints.minOf { it.second }).coerceAtLeast(1f)

        val horizontalPadding = 12f
        val topPadding = 8f
        val bottomPadding = 12f
        val riderCenterX = width / 2f
        val riderCenterY = height - bottomPadding - 22f
        val scaleX = (width / 2f - horizontalPadding) / maxAbsX
        val scaleYForward = (riderCenterY - topPadding) / maxAhead
        val scaleYBackward = (height - riderCenterY - bottomPadding) / maxBehind
        val scale = min(scaleX, min(scaleYForward, scaleYBackward)).coerceAtLeast(0.2f)

        val routePath = Path()
        transformedPoints.forEachIndexed { index, point ->
            val screenX = riderCenterX + point.first * scale
            val screenY = riderCenterY - point.second * scale
            if (index == 0) routePath.moveTo(screenX, screenY)
            else routePath.lineTo(screenX, screenY)
        }
        canvas.drawPath(routePath, routePaint)

        if (cueIndex in transformedPoints.indices) {
            val cuePoint = transformedPoints[cueIndex]
            canvas.drawCircle(
                riderCenterX + cuePoint.first * scale,
                riderCenterY - cuePoint.second * scale,
                10f,
                cuePaint
            )
        }

        val riderPath = Path().apply {
            moveTo(riderCenterX, riderCenterY - 24f)
            lineTo(riderCenterX - 20f, riderCenterY + 20f)
            lineTo(riderCenterX + 20f, riderCenterY + 20f)
            close()
        }
        canvas.drawPath(riderPath, riderPaint)
    }

    private fun latitudeToMeters(referenceLatitude: Double, latitude: Double): Double =
        (latitude - referenceLatitude) * 110540.0

    private fun longitudeToMeters(
        referenceLongitude: Double,
        longitude: Double,
        referenceLatitude: Double,
    ): Double = (longitude - referenceLongitude) * 111320.0 *
            cos(Math.toRadians(referenceLatitude))

    private fun bearingDegrees(
        start: GuidancePreviewPoint,
        end: GuidancePreviewPoint,
    ): Double {
        val result = FloatArray(2)
        android.location.Location.distanceBetween(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude,
            result
        )
        return result[1].toDouble()
    }
}
