package com.kvl.cyclotrack

import android.location.Location
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max

fun getRouteDistance(routePoints: Array<RoutePoint>): Double {
    if (routePoints.size < 2) return 0.0

    var totalDistance = 0.0
    var previous = routePoints.first()
    val distanceBuffer = floatArrayOf(0f)

    routePoints.drop(1).forEach { point ->
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            point.latitude,
            point.longitude,
            distanceBuffer
        )
        totalDistance += distanceBuffer[0]
        previous = point
    }

    return totalDistance
}

fun getRouteAscentDescent(routePoints: Array<RoutePoint>): Pair<Double, Double>? =
    routePoints.mapNotNull { point ->
        point.elevation?.let { Pair(it, 0.0) }
    }.takeIf { it.size > 1 }?.let(::accumulateAscentDescent)

data class RouteProfilePoint(
    val distanceMeters: Double,
    val elevationMeters: Double,
)

data class RouteGradePoint(
    val distanceMeters: Double,
    val gradePercent: Double,
)

data class RouteDerivedStats(
    val pointCount: Int,
    val distanceMeters: Double,
    val ascentMeters: Double? = null,
    val descentMeters: Double? = null,
    val minElevationMeters: Double? = null,
    val maxElevationMeters: Double? = null,
    val steepestClimbPercent: Double? = null,
    val steepestDescentPercent: Double? = null,
    val steepestClimbAngleDegrees: Double? = null,
    val steepestDescentAngleDegrees: Double? = null,
    val elevationProfile: List<RouteProfilePoint> = emptyList(),
    val gradeProfile: List<RouteGradePoint> = emptyList(),
)

fun getRouteDerivedStats(routePoints: Array<RoutePoint>): RouteDerivedStats {
    if (routePoints.isEmpty()) {
        return RouteDerivedStats(pointCount = 0, distanceMeters = 0.0)
    }

    val ascentDescent = getRouteAscentDescent(routePoints)
    val elevations = routePoints.mapNotNull { it.elevation }
    val elevationProfile = mutableListOf<RouteProfilePoint>()
    val gradeProfile = mutableListOf<RouteGradePoint>()
    var totalDistance = 0.0
    var smoothedElevation: Double? = null
    var steepestClimbPercent: Double? = null
    var steepestDescentPercent: Double? = null
    val distanceBuffer = floatArrayOf(0f)

    routePoints.first().elevation?.let { elevation ->
        smoothedElevation = elevation
        elevationProfile.add(RouteProfilePoint(0.0, elevation))
    }

    for (index in 1 until routePoints.size) {
        val previous = routePoints[index - 1]
        val current = routePoints[index]
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
            distanceBuffer
        )
        val segmentDistance = distanceBuffer[0].toDouble()
        totalDistance += segmentDistance

        current.elevation?.let { elevation ->
            val lastElevation = smoothedElevation ?: elevation
            val smoothed = exponentialSmoothing(0.15, elevation, lastElevation)
            smoothedElevation = smoothed
            elevationProfile.add(RouteProfilePoint(totalDistance, smoothed))
        }

        if (segmentDistance >= 5.0 && previous.elevation != null && current.elevation != null) {
            val gradePercent = ((current.elevation - previous.elevation) / segmentDistance) * 100.0
            gradeProfile.add(RouteGradePoint(totalDistance, gradePercent))
            if (gradePercent > 0.0) {
                steepestClimbPercent = max(steepestClimbPercent ?: gradePercent, gradePercent)
            }
            if (gradePercent < 0.0) {
                steepestDescentPercent = max(
                    steepestDescentPercent ?: abs(gradePercent),
                    abs(gradePercent)
                )
            }
        }
    }

    return RouteDerivedStats(
        pointCount = routePoints.size,
        distanceMeters = totalDistance,
        ascentMeters = ascentDescent?.first,
        descentMeters = ascentDescent?.second?.let(::abs),
        minElevationMeters = elevations.minOrNull(),
        maxElevationMeters = elevations.maxOrNull(),
        steepestClimbPercent = steepestClimbPercent,
        steepestDescentPercent = steepestDescentPercent,
        steepestClimbAngleDegrees = steepestClimbPercent?.let(::gradePercentToAngleDegrees),
        steepestDescentAngleDegrees = steepestDescentPercent?.let(::gradePercentToAngleDegrees),
        elevationProfile = elevationProfile,
        gradeProfile = gradeProfile
    )
}

suspend fun plotPath(routePoints: Array<RoutePoint>): MapPath =
    withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext MapPath(emptyArray(), null)

        val path = PolylineOptions()
        val boundsBuilder = LatLngBounds.Builder()
        var included = false

        routePoints.forEach { point ->
            val latLng = LatLng(point.latitude, point.longitude)
            path.add(latLng)
            boundsBuilder.include(latLng)
            included = true
        }

        MapPath(
            arrayOf(path),
            if (included) boundsBuilder.build() else null
        )
    }

private fun gradePercentToAngleDegrees(gradePercent: Double): Double =
    atan(gradePercent / 100.0) * 180.0 / PI
