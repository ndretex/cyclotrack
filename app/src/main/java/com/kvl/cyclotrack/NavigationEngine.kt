package com.kvl.cyclotrack

import android.location.Location
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val NAV_DEBUG_TAG = "NavigationEngine"
private const val MIN_POINT_SPACING_METERS = 3.0
private const val MIN_CUE_SPACING_METERS = 25.0
private const val MIN_SEGMENT_LENGTH_METERS = 6.0
private const val MIN_TURN_SAMPLE_DELTA_DEGREES = 18.0
private const val MAX_TURN_CLUSTER_GAP_METERS = 24.0
private const val OFF_ROUTE_THRESHOLD_METERS = 75.0
private const val ARRIVAL_THRESHOLD_METERS = 30.0
private const val LOOP_ROUTE_ENDPOINT_PROXIMITY_THRESHOLD_METERS = 75.0
private const val LOOP_ROUTE_MIN_PROGRESS_FOR_FINISH = 0.9
private const val LOOP_ROUTE_INITIAL_MATCH_CAPTURE_RADIUS_METERS = 120.0
private const val LOOP_ROUTE_INITIAL_MATCH_WINDOW_METERS = 500.0
private const val NEXT_CUE_LOOKAHEAD_METERS = 1.0
private const val PREVIEW_DISTANCE_BEHIND_METERS = 20.0
private const val PREVIEW_DISTANCE_AHEAD_METERS = 140.0
private const val PREVIEW_MAX_DISTANCE_AHEAD_METERS = 180.0
private const val PREVIEW_TURN_PADDING_METERS = 30.0

fun buildNavigablePath(name: String, routePoints: Array<RoutePoint>): NavigablePath? =
    buildNavigablePath(
        name = name,
        rawPoints = routePoints.map {
            Triple(it.latitude, it.longitude, it.elevation)
        }
    )

fun buildNavigablePath(name: String, measurements: Array<Measurements>): NavigablePath? =
    buildNavigablePath(
        name = name,
        rawPoints = measurements.map {
            Triple(it.latitude, it.longitude, it.altitude)
        }
    )

private fun buildNavigablePath(
    name: String,
    rawPoints: List<Triple<Double, Double, Double?>>,
): NavigablePath? {
    if (rawPoints.size < 2) {
        Log.w(NAV_DEBUG_TAG, "buildNavigablePath($name): not enough raw points (${rawPoints.size})")
        return null
    }

    val navigablePoints = mutableListOf<NavigablePoint>()
    var cumulativeDistance = 0.0

    rawPoints.forEachIndexed { index, rawPoint ->
        if (index == 0) {
            navigablePoints += NavigablePoint(
                latitude = rawPoint.first,
                longitude = rawPoint.second,
                elevation = rawPoint.third,
                cumulativeDistanceMeters = 0.0
            )
            return@forEachIndexed
        }

        val previous = navigablePoints.last()
        val segmentDistance = distanceMeters(
            previous.latitude,
            previous.longitude,
            rawPoint.first,
            rawPoint.second
        )

        if (segmentDistance < MIN_POINT_SPACING_METERS && index != rawPoints.lastIndex) {
            return@forEachIndexed
        }

        cumulativeDistance += segmentDistance
        navigablePoints += NavigablePoint(
            latitude = rawPoint.first,
            longitude = rawPoint.second,
            elevation = rawPoint.third,
            cumulativeDistanceMeters = cumulativeDistance
        )
    }

    if (navigablePoints.size < 2) {
        Log.w(
            NAV_DEBUG_TAG,
            "buildNavigablePath($name): not enough filtered points (${navigablePoints.size})"
        )
        return null
    }

    val cues = generateNavigationCues(navigablePoints)
    Log.i(
        NAV_DEBUG_TAG,
        "buildNavigablePath($name): rawPoints=${rawPoints.size}, filteredPoints=${navigablePoints.size}, cues=${cues.size}, totalDistanceMeters=${navigablePoints.last().cumulativeDistanceMeters}"
    )
    return NavigablePath(
        name = name,
        points = navigablePoints,
        totalDistanceMeters = navigablePoints.last().cumulativeDistanceMeters,
        cues = cues,
        source = NavigationEngineSource.LOCAL
    )
}

fun initialGuidanceSnapshot(path: NavigablePath): GuidanceSnapshot {
    val nextCue = path.cues.firstOrNull {
        it.distanceFromStartMeters > NEXT_CUE_LOOKAHEAD_METERS
    } ?: path.cues.firstOrNull()
    return buildSnapshot(
        path = path,
        state = GuidanceUiState.GUIDANCE,
        maneuver = nextCue?.maneuver ?: ManeuverDirection.FINISH,
        matchedIndex = 0,
        nextCueDistanceMeters = nextCue?.distanceFromStartMeters,
        remainingDistanceMeters = path.totalDistanceMeters,
        previewAnchorIndex = nextCue?.pointIndex ?: 0,
        instructionText = nextCue?.instructionText.orEmpty(),
        turnAngleDegrees = nextCue?.turnAngleDegrees
    )
}

fun computeGuidanceSnapshot(
    path: NavigablePath,
    location: Location,
    previousMatchedIndex: Int,
): GuidanceSnapshot {
    val matchedIndex = matchPointIndex(path, location, previousMatchedIndex)
    val matchedPoint = path.points[matchedIndex]
    val remainingDistance = max(
        0.0,
        path.totalDistanceMeters - matchedPoint.cumulativeDistanceMeters
    )
    val nearestPointDistance = distanceMeters(
        location.latitude,
        location.longitude,
        matchedPoint.latitude,
        matchedPoint.longitude
    )

    if (shouldTreatAsArrival(path, location, matchedIndex, remainingDistance)) {
        Log.d(
            NAV_DEBUG_TAG,
            "computeGuidanceSnapshot(${path.name}): ARRIVAL remainingDistance=$remainingDistance, matchedIndex=$matchedIndex"
        )
        return buildSnapshot(
            path = path,
            state = GuidanceUiState.ARRIVAL,
            maneuver = ManeuverDirection.FINISH,
            matchedIndex = matchedIndex,
            nextCueDistanceMeters = 0.0,
            remainingDistanceMeters = remainingDistance,
            previewAnchorIndex = path.points.lastIndex
        )
    }

    if (location.accuracy > LOCATION_ACCURACY_THRESHOLD * 2f) {
        Log.d(
            NAV_DEBUG_TAG,
            "computeGuidanceSnapshot(${path.name}): GPS_WEAK accuracy=${location.accuracy}, matchedIndex=$matchedIndex"
        )
        return buildSnapshot(
            path = path,
            state = GuidanceUiState.GPS_WEAK,
            maneuver = ManeuverDirection.STRAIGHT,
            matchedIndex = matchedIndex,
            nextCueDistanceMeters = null,
            remainingDistanceMeters = remainingDistance,
            previewAnchorIndex = matchedIndex
        )
    }

    if (nearestPointDistance > OFF_ROUTE_THRESHOLD_METERS) {
        Log.d(
            NAV_DEBUG_TAG,
            "computeGuidanceSnapshot(${path.name}): OFF_ROUTE nearestPointDistance=$nearestPointDistance, matchedIndex=$matchedIndex"
        )
        return buildSnapshot(
            path = path,
            state = GuidanceUiState.OFF_ROUTE,
            maneuver = ManeuverDirection.STRAIGHT,
            matchedIndex = matchedIndex,
            nextCueDistanceMeters = null,
            remainingDistanceMeters = remainingDistance,
            previewAnchorIndex = matchedIndex
        )
    }

    val nextCue = path.cues.firstOrNull {
        it.distanceFromStartMeters > matchedPoint.cumulativeDistanceMeters + NEXT_CUE_LOOKAHEAD_METERS
    }
    val distanceToNextCue = nextCue?.distanceFromStartMeters?.minus(matchedPoint.cumulativeDistanceMeters)
        ?.coerceAtLeast(0.0)
    val etaToNextCueSeconds = when {
        distanceToNextCue == null -> Double.POSITIVE_INFINITY
        location.speed > 1.0f -> distanceToNextCue / location.speed
        else -> Double.POSITIVE_INFINITY
    }
    val state = if ((distanceToNextCue ?: Double.POSITIVE_INFINITY) < 300.0 ||
        etaToNextCueSeconds < 20.0
    ) {
        GuidanceUiState.TURN_GUIDANCE
    } else {
        GuidanceUiState.GUIDANCE
    }

    return buildSnapshot(
        path = path,
        state = state,
        maneuver = nextCue?.maneuver ?: ManeuverDirection.FINISH,
        matchedIndex = matchedIndex,
        nextCueDistanceMeters = distanceToNextCue,
        remainingDistanceMeters = remainingDistance,
        previewAnchorIndex = nextCue?.pointIndex ?: matchedIndex,
        instructionText = nextCue?.instructionText.orEmpty(),
        turnAngleDegrees = nextCue?.turnAngleDegrees
    )
}

private fun buildSnapshot(
    path: NavigablePath,
    state: GuidanceUiState,
    maneuver: ManeuverDirection,
    matchedIndex: Int,
    nextCueDistanceMeters: Double?,
    remainingDistanceMeters: Double,
    previewAnchorIndex: Int,
    instructionText: String = "",
    turnAngleDegrees: Double? = null,
): GuidanceSnapshot {
    val (startIndex, endIndex, previewCueIndex) = buildPreviewWindow(
        path = path,
        matchedIndex = matchedIndex,
        previewAnchorIndex = previewAnchorIndex,
        nextCueDistanceMeters = nextCueDistanceMeters
    )
    val previewPoints = path.points.subList(startIndex, endIndex + 1)
    Log.v(
        NAV_DEBUG_TAG,
        "buildSnapshot(${path.name}): state=$state, matchedIndex=$matchedIndex, previewStart=$startIndex, previewEnd=$endIndex, previewPoints=${previewPoints.size}, previewCueIndex=$previewCueIndex"
    )

    return GuidanceSnapshot(
        state = state,
        routeName = path.name,
        maneuver = maneuver,
        nextCueDistanceMeters = nextCueDistanceMeters,
        remainingDistanceMeters = remainingDistanceMeters,
        instructionText = instructionText,
        engineSource = path.source,
        turnAngleDegrees = turnAngleDegrees,
        previewPoints = previewPoints.map { GuidancePreviewPoint(it.latitude, it.longitude) },
        previewCurrentIndex = matchedIndex - startIndex,
        previewCueIndex = previewCueIndex,
        matchedPointIndex = matchedIndex
    )
}

private fun buildPreviewWindow(
    path: NavigablePath,
    matchedIndex: Int,
    previewAnchorIndex: Int,
    nextCueDistanceMeters: Double?,
): Triple<Int, Int, Int> {
    val matchedDistance = path.points[matchedIndex].cumulativeDistanceMeters
    val startDistance = max(0.0, matchedDistance - PREVIEW_DISTANCE_BEHIND_METERS)
    val visibleAheadDistance = when {
        nextCueDistanceMeters == null -> PREVIEW_DISTANCE_AHEAD_METERS
        nextCueDistanceMeters <= PREVIEW_MAX_DISTANCE_AHEAD_METERS ->
            min(
                PREVIEW_MAX_DISTANCE_AHEAD_METERS,
                nextCueDistanceMeters + PREVIEW_TURN_PADDING_METERS
            )

        else -> PREVIEW_DISTANCE_AHEAD_METERS
    }
    val endDistance = min(path.totalDistanceMeters, matchedDistance + visibleAheadDistance)
    val startIndex = path.points.indexOfFirst {
        it.cumulativeDistanceMeters >= startDistance
    }.let { foundIndex -> if (foundIndex >= 0) foundIndex else 0 }
    val endIndex = path.points.indexOfLast {
        it.cumulativeDistanceMeters <= endDistance
    }.let { foundIndex ->
        when {
            foundIndex < 0 -> matchedIndex
            foundIndex < matchedIndex -> matchedIndex
            else -> foundIndex
        }
    }
    val previewCueIndex = if (previewAnchorIndex in startIndex..endIndex) {
        previewAnchorIndex - startIndex
    } else {
        -1
    }
    return Triple(startIndex, endIndex, previewCueIndex)
}

private fun matchPointIndex(
    path: NavigablePath,
    location: Location,
    previousMatchedIndex: Int,
): Int {
    val startIndex = if (previousMatchedIndex >= 0) {
        max(0, previousMatchedIndex - 12)
    } else {
        0
    }
    val endIndex = when {
        previousMatchedIndex >= 0 -> min(path.points.lastIndex, previousMatchedIndex + 160)
        shouldPreferLoopStartWindow(path, location) -> {
            path.points.indexOfLast {
                it.cumulativeDistanceMeters <= LOOP_ROUTE_INITIAL_MATCH_WINDOW_METERS
            }.let { if (it >= 0) it else min(path.points.lastIndex, 160) }
        }
        else -> path.points.lastIndex
    }

    var bestIndex = startIndex
    var bestDistance = Double.POSITIVE_INFINITY

    for (index in startIndex..endIndex) {
        val point = path.points[index]
        val pointDistance = distanceMeters(
            location.latitude,
            location.longitude,
            point.latitude,
            point.longitude
        )
        if (pointDistance < bestDistance) {
            bestDistance = pointDistance
            bestIndex = index
        }
    }

    return if (previousMatchedIndex >= 0) max(previousMatchedIndex, bestIndex) else bestIndex
}

fun isLoopRoute(path: NavigablePath): Boolean {
    val start = path.points.firstOrNull() ?: return false
    val end = path.points.lastOrNull() ?: return false
    return distanceMeters(
        start.latitude,
        start.longitude,
        end.latitude,
        end.longitude
    ) <= LOOP_ROUTE_ENDPOINT_PROXIMITY_THRESHOLD_METERS
}

fun progressFractionAt(path: NavigablePath, pointIndex: Int): Double {
    if (path.totalDistanceMeters <= 0.0) return 1.0
    val clampedIndex = pointIndex.coerceIn(path.points.indices)
    return (path.points[clampedIndex].cumulativeDistanceMeters / path.totalDistanceMeters)
        .coerceIn(0.0, 1.0)
}

private fun shouldTreatAsArrival(
    path: NavigablePath,
    location: Location,
    matchedIndex: Int,
    remainingDistanceMeters: Double,
): Boolean {
    if (remainingDistanceMeters > ARRIVAL_THRESHOLD_METERS) return false
    if (!isLoopRoute(path)) return true

    val progressFraction = progressFractionAt(path, matchedIndex)
    if (progressFraction < LOOP_ROUTE_MIN_PROGRESS_FOR_FINISH) {
        Log.d(
            NAV_DEBUG_TAG,
            "Suppressing loop arrival for ${path.name}: progressFraction=$progressFraction, matchedIndex=$matchedIndex"
        )
        return false
    }

    val finishPoint = path.points.last()
    val distanceToFinish = distanceMeters(
        location.latitude,
        location.longitude,
        finishPoint.latitude,
        finishPoint.longitude
    )
    return distanceToFinish <= ARRIVAL_THRESHOLD_METERS
}

private fun shouldPreferLoopStartWindow(
    path: NavigablePath,
    location: Location,
): Boolean {
    if (!isLoopRoute(path)) return false

    val startPoint = path.points.first()
    val finishPoint = path.points.last()
    val distanceToStart = distanceMeters(
        location.latitude,
        location.longitude,
        startPoint.latitude,
        startPoint.longitude
    )
    val distanceToFinish = distanceMeters(
        location.latitude,
        location.longitude,
        finishPoint.latitude,
        finishPoint.longitude
    )

    return distanceToStart <= LOOP_ROUTE_INITIAL_MATCH_CAPTURE_RADIUS_METERS &&
        distanceToFinish <= LOOP_ROUTE_INITIAL_MATCH_CAPTURE_RADIUS_METERS
}

private fun generateNavigationCues(points: List<NavigablePoint>): List<NavigationCue> {
    if (points.size < 2) return emptyList()

    val turnSamples = mutableListOf<TurnSample>()
    for (index in 1 until points.lastIndex) {
        val previous = points[index - 1]
        val current = points[index]
        val next = points[index + 1]

        val incomingDistance = current.cumulativeDistanceMeters - previous.cumulativeDistanceMeters
        val outgoingDistance = next.cumulativeDistanceMeters - current.cumulativeDistanceMeters

        if (incomingDistance < MIN_SEGMENT_LENGTH_METERS || outgoingDistance < MIN_SEGMENT_LENGTH_METERS) {
            continue
        }

        val incomingBearing = bearingDegrees(previous, current)
        val outgoingBearing = bearingDegrees(current, next)
        val turnDelta = normalizeDegrees(outgoingBearing - incomingBearing)
        if (abs(turnDelta) < MIN_TURN_SAMPLE_DELTA_DEGREES) continue

        turnSamples += TurnSample(
            index = index,
            distanceFromStartMeters = current.cumulativeDistanceMeters,
            turnDelta = turnDelta
        )
    }

    val cues = mutableListOf<NavigationCue>()
    var lastCueDistance = Double.NEGATIVE_INFINITY
    val currentCluster = mutableListOf<TurnSample>()

    fun flushCluster() {
        if (currentCluster.isEmpty()) return

        val totalTurnDelta = currentCluster.sumOf { it.turnDelta }
        val maneuver = classifyManeuver(totalTurnDelta)
        val cueSample = currentCluster.first()

        if (maneuver != ManeuverDirection.STRAIGHT &&
            cueSample.distanceFromStartMeters - lastCueDistance >= MIN_CUE_SPACING_METERS
        ) {
            cues += NavigationCue(
                maneuver = maneuver,
                pointIndex = cueSample.index,
                distanceFromStartMeters = cueSample.distanceFromStartMeters,
                turnAngleDegrees = totalTurnDelta
            )
            lastCueDistance = cueSample.distanceFromStartMeters
        }

        currentCluster.clear()
    }

    turnSamples.forEach { sample ->
        val previousSample = currentCluster.lastOrNull()
        val canMerge =
            previousSample != null &&
                    previousSample.turnDelta * sample.turnDelta > 0.0 &&
                    sample.distanceFromStartMeters - previousSample.distanceFromStartMeters <=
                    MAX_TURN_CLUSTER_GAP_METERS

        if (!canMerge) flushCluster()
        currentCluster += sample
    }
    flushCluster()

    cues += NavigationCue(
        maneuver = ManeuverDirection.FINISH,
        pointIndex = points.lastIndex,
        distanceFromStartMeters = points.last().cumulativeDistanceMeters
    )

    return cues
}

private data class TurnSample(
    val index: Int,
    val distanceFromStartMeters: Double,
    val turnDelta: Double,
)

private fun classifyManeuver(turnDelta: Double): ManeuverDirection {
    val absoluteDelta = abs(turnDelta)
    if (absoluteDelta < 25.0) return ManeuverDirection.STRAIGHT
    if (absoluteDelta >= 170.0) return ManeuverDirection.U_TURN

    return if (turnDelta < 0.0) {
        when {
            absoluteDelta < 55.0 -> ManeuverDirection.SLIGHT_LEFT
            absoluteDelta < 120.0 -> ManeuverDirection.LEFT
            else -> ManeuverDirection.SHARP_LEFT
        }
    } else {
        when {
            absoluteDelta < 55.0 -> ManeuverDirection.SLIGHT_RIGHT
            absoluteDelta < 120.0 -> ManeuverDirection.RIGHT
            else -> ManeuverDirection.SHARP_RIGHT
        }
    }
}

private fun bearingDegrees(start: NavigablePoint, end: NavigablePoint): Double {
    val results = FloatArray(2)
    Location.distanceBetween(
        start.latitude,
        start.longitude,
        end.latitude,
        end.longitude,
        results
    )
    return results[1].toDouble()
}

private fun distanceMeters(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double,
): Double {
    val results = FloatArray(1)
    Location.distanceBetween(
        startLatitude,
        startLongitude,
        endLatitude,
        endLongitude,
        results
    )
    return results[0].toDouble()
}

private fun normalizeDegrees(value: Double): Double {
    var normalized = value
    while (normalized <= -180.0) normalized += 360.0
    while (normalized > 180.0) normalized -= 360.0
    return normalized
}
