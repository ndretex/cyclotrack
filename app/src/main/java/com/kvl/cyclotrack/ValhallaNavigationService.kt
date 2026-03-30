package com.kvl.cyclotrack

import android.location.Location
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val VALHALLA_ROUTE_SHAPE_MATCH = "walk_or_snap"
private const val VALHALLA_TRACE_SHAPE_MATCH = "map_snap"
private const val VALHALLA_ROUTE_SEARCH_RADIUS_METERS = 20
private const val VALHALLA_ROUTE_GPS_ACCURACY_METERS = 6
private const val VALHALLA_TRACE_SEARCH_RADIUS_METERS = 35
private const val VALHALLA_TRACE_GPS_ACCURACY_METERS = 15
private const val VALHALLA_BREAKAGE_DISTANCE_METERS = 250
private const val VALHALLA_INTERPOLATION_DISTANCE_METERS = 8
private const val STEP_MATCH_THRESHOLD_METERS = 12.0
private const val VALHALLA_REROUTE_START_RADIUS_METERS = 30
private const val VALHALLA_REROUTE_END_RADIUS_METERS = 50

@Singleton
class ValhallaNavigationService @Inject constructor() {
    private val logTag = "ValhallaNavigation"
    private val client = OkHttpClient()
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val requestAdapter = moshi.adapter(ValhallaTraceRouteRequest::class.java)
    private val routeRequestAdapter = moshi.adapter(ValhallaRouteRequest::class.java)
    private val responseAdapter = moshi.adapter(ValhallaOsrmResponse::class.java)

    fun isConfigured(): Boolean = baseUrl().isNotBlank()

    suspend fun buildPath(
        name: String,
        routePoints: Array<RoutePoint>,
    ): NavigablePath? = buildPath(
        name = name,
        shapeMatch = VALHALLA_ROUTE_SHAPE_MATCH,
        traceOptions = ValhallaTraceOptions(
            searchRadius = VALHALLA_ROUTE_SEARCH_RADIUS_METERS,
            gpsAccuracy = VALHALLA_ROUTE_GPS_ACCURACY_METERS,
            breakageDistance = VALHALLA_BREAKAGE_DISTANCE_METERS,
            interpolationDistance = VALHALLA_INTERPOLATION_DISTANCE_METERS
        ),
        points = routePoints
            .sortedBy { it.sequence }
            .map { ValhallaShapePoint(lat = it.latitude, lon = it.longitude) }
    )

    suspend fun buildPath(
        name: String,
        measurements: Array<Measurements>,
    ): NavigablePath? = buildPath(
        name = name,
        shapeMatch = VALHALLA_TRACE_SHAPE_MATCH,
        traceOptions = ValhallaTraceOptions(
            searchRadius = VALHALLA_TRACE_SEARCH_RADIUS_METERS,
            gpsAccuracy = VALHALLA_TRACE_GPS_ACCURACY_METERS,
            breakageDistance = VALHALLA_BREAKAGE_DISTANCE_METERS,
            interpolationDistance = VALHALLA_INTERPOLATION_DISTANCE_METERS
        ),
        points = measurements
            .sortedBy { it.time }
            .map { measurement ->
                ValhallaShapePoint(
                    lat = measurement.latitude,
                    lon = measurement.longitude,
                    time = (measurement.time / 1000L).takeIf { it > 0 }
                )
            }
    )

    suspend fun reroute(
        name: String,
        currentLocation: Location,
        destination: NavigablePoint,
    ): NavigablePath? = withContext(Dispatchers.IO) {
        val serviceBaseUrl = baseUrl()
        if (serviceBaseUrl.isBlank()) {
            Log.w(logTag, "Valhalla reroute skipped for \"$name\": VALHALLA_BASE_URL is blank")
            return@withContext null
        }

        val requestJson = routeRequestAdapter.toJson(
            ValhallaRouteRequest(
                locations = buildList {
                    add(
                        ValhallaRouteLocation(
                            lat = currentLocation.latitude,
                            lon = currentLocation.longitude,
                            type = "break",
                            heading = currentLocation.bearing.takeIf { currentLocation.hasBearing() },
                            headingTolerance = 60,
                            radius = VALHALLA_REROUTE_START_RADIUS_METERS
                        )
                    )
                    add(
                        ValhallaRouteLocation(
                            lat = destination.latitude,
                            lon = destination.longitude,
                            type = "break",
                            radius = VALHALLA_REROUTE_END_RADIUS_METERS
                        )
                    )
                },
                costing = "bicycle",
                format = "osrm",
                shapeFormat = "polyline6",
                roundaboutExits = true,
                language = Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "en-US"
            )
        )

        val request = Request.Builder()
            .url("$serviceBaseUrl/route")
            .apply {
                val apiKey = BuildConfig.VALHALLA_API_KEY.trim()
                if (apiKey.isNotBlank()) {
                    addHeader("x-api-key", apiKey)
                }
            }
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()
        val requestUrl = request.url.toString()

        Log.i(
            logTag,
            "Requesting Valhalla reroute for \"$name\": url=$requestUrl, " +
                "start=${currentLocation.latitude},${currentLocation.longitude}, " +
                "end=${destination.latitude},${destination.longitude}"
        )

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    Log.w(
                        logTag,
                        "Valhalla reroute failed for \"$name\": url=$requestUrl, " +
                            "http=${response.code}, body=$errorBody"
                    )
                    return@withContext null
                }

                val payload = response.body?.source()?.let { responseAdapter.fromJson(it) }
                if (payload == null) {
                    Log.w(
                        logTag,
                        "Valhalla reroute returned an empty or unreadable body for \"$name\": url=$requestUrl"
                    )
                    return@withContext null
                }

                payload.toNavigablePath(name, requestUrl)
            }
        }.onFailure { error ->
            if (error is IOException) {
                Log.w(
                    logTag,
                    "Valhalla reroute request failed for \"$name\": url=$requestUrl, error=${error.message}"
                )
            } else {
                Log.e(logTag, "Unexpected Valhalla reroute failure for \"$name\": url=$requestUrl", error)
            }
        }.getOrNull()
    }

    private suspend fun buildPath(
        name: String,
        shapeMatch: String,
        traceOptions: ValhallaTraceOptions,
        points: List<ValhallaShapePoint>,
    ): NavigablePath? = withContext(Dispatchers.IO) {
        val serviceBaseUrl = baseUrl()
        if (serviceBaseUrl.isBlank()) {
            Log.w(logTag, "Valhalla disabled: VALHALLA_BASE_URL is blank for \"$name\"")
            return@withContext null
        }
        if (points.size < 2) {
            Log.w(logTag, "Valhalla skipped for \"$name\": not enough points (${points.size})")
            return@withContext null
        }

        val requestJson = requestAdapter.toJson(
            ValhallaTraceRouteRequest(
                shape = points,
                costing = "bicycle",
                shapeMatch = shapeMatch,
                format = "osrm",
                shapeFormat = "polyline6",
                roundaboutExits = true,
                language = Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "en-US",
                useTimestamps = points.any { it.time != null },
                traceOptions = traceOptions
            )
        )

        val request = Request.Builder()
            .url("$serviceBaseUrl/trace_route")
            .apply {
                val apiKey = BuildConfig.VALHALLA_API_KEY.trim()
                if (apiKey.isNotBlank()) {
                    addHeader("x-api-key", apiKey)
                }
            }
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()
        val requestUrl = request.url.toString()

        Log.i(
            logTag,
            "Requesting Valhalla trace_route for \"$name\": url=$requestUrl, " +
                "points=${points.size}, shapeMatch=$shapeMatch, timestamps=${points.any { it.time != null }}"
        )

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    Log.w(
                        logTag,
                        "Valhalla trace_route failed for \"$name\": url=$requestUrl, " +
                            "http=${response.code}, body=$errorBody"
                    )
                    return@withContext null
                }

                val payload = response.body?.source()?.let { responseAdapter.fromJson(it) }
                if (payload == null) {
                    Log.w(
                        logTag,
                        "Valhalla trace_route returned an empty or unreadable body for \"$name\": url=$requestUrl"
                    )
                    return@withContext null
                }

                payload.toNavigablePath(name, requestUrl)
            }
        }.onFailure { error ->
            if (error is IOException) {
                Log.w(
                    logTag,
                    "Valhalla request failed for \"$name\": url=$requestUrl, error=${error.message}"
                )
            } else {
                Log.e(logTag, "Unexpected Valhalla failure for \"$name\": url=$requestUrl", error)
            }
        }.getOrNull()
    }

    private fun baseUrl(): String = BuildConfig.VALHALLA_BASE_URL.trim().trimEnd('/')

    private fun ValhallaOsrmResponse.toNavigablePath(name: String, requestUrl: String): NavigablePath? {
        if (!code.equals("Ok", ignoreCase = true)) {
            Log.w(
                logTag,
                "Valhalla response rejected for \"$name\": url=$requestUrl, code=$code"
            )
            return null
        }
        val route = routes.firstOrNull() ?: matchings.firstOrNull()
        if (route == null) {
            Log.w(
                logTag,
                "Valhalla response rejected for \"$name\": url=$requestUrl, no routes or matchings returned"
            )
            return null
        }
        val routePoints = decodePolyline6(route.geometry.orEmpty())
        if (routePoints.size < 2) {
            Log.w(
                logTag,
                "Valhalla response rejected for \"$name\": url=$requestUrl, decoded route has ${routePoints.size} points"
            )
            return null
        }

        val navigablePoints = routePoints.toNavigablePoints()
        val cues = mutableListOf<NavigationCue>()
        var searchStartIndex = 0

        route.legs.orEmpty().forEach { leg ->
            leg.steps.orEmpty().forEach { step ->
                val maneuver = step.maneuver?.toNavigationDirection() ?: return@forEach
                if (maneuver == ManeuverDirection.STRAIGHT && cues.isNotEmpty()) return@forEach

                val stepPoints = decodePolyline6(step.geometry.orEmpty())
                val pointIndex = locateStepStartIndex(
                    routePoints = routePoints,
                    stepPoints = stepPoints,
                    searchStartIndex = searchStartIndex
                ) ?: searchStartIndex.coerceIn(navigablePoints.indices)

                searchStartIndex = pointIndex
                val instructionText = step.maneuver.instruction?.trim().orEmpty().ifBlank {
                    step.defaultInstruction()
                }

                if (maneuver == ManeuverDirection.FINISH &&
                    pointIndex == navigablePoints.lastIndex &&
                    cues.lastOrNull()?.maneuver == ManeuverDirection.FINISH
                ) {
                    return@forEach
                }

                cues += NavigationCue(
                    maneuver = maneuver,
                    pointIndex = pointIndex,
                    distanceFromStartMeters = navigablePoints[pointIndex].cumulativeDistanceMeters,
                    instructionText = instructionText,
                    turnAngleDegrees = computeTurnAngleDegrees(routePoints, pointIndex)
                )
            }
        }

        if (cues.none { it.maneuver == ManeuverDirection.FINISH }) {
            cues += NavigationCue(
                maneuver = ManeuverDirection.FINISH,
                pointIndex = navigablePoints.lastIndex,
                distanceFromStartMeters = navigablePoints.last().cumulativeDistanceMeters,
                instructionText = "Destination"
            )
        }

        val normalizedCues = cues
            .sortedBy { it.distanceFromStartMeters }
            .distinctBy { Triple(it.pointIndex, it.maneuver, it.instructionText) }

        Log.i(
            logTag,
            "Valhalla trace_route accepted for \"$name\": url=$requestUrl, " +
                "routePoints=${routePoints.size}, cues=${normalizedCues.size}, legs=${route.legs.orEmpty().size}, " +
                "topLevel=${if (routes.isNotEmpty()) "routes" else "matchings"}"
        )

        return NavigablePath(
            name = name,
            points = navigablePoints,
            totalDistanceMeters = navigablePoints.last().cumulativeDistanceMeters,
            cues = normalizedCues,
            source = NavigationEngineSource.VALHALLA
        )
    }

    private fun computeTurnAngleDegrees(
        routePoints: List<Pair<Double, Double>>,
        pointIndex: Int,
    ): Double? {
        if (pointIndex !in 1 until routePoints.lastIndex) return null

        val current = routePoints[pointIndex]
        val previous = routePoints[pointIndex - 1]
        val nextIndex = minOf(routePoints.lastIndex, pointIndex + 3)
        val next = routePoints[nextIndex]
        val incomingBearing = bearingDegrees(previous, current)
        val outgoingBearing = bearingDegrees(current, next)
        return normalizeDegrees(outgoingBearing - incomingBearing)
    }

    private fun List<Pair<Double, Double>>.toNavigablePoints(): List<NavigablePoint> {
        val navigablePoints = mutableListOf<NavigablePoint>()
        forEachIndexed { index, point ->
            val cumulativeDistance = if (index == 0) {
                0.0
            } else {
                val previous = navigablePoints.last()
                previous.cumulativeDistanceMeters + distanceMeters(
                    previous.latitude,
                    previous.longitude,
                    point.first,
                    point.second
                )
            }
            navigablePoints += NavigablePoint(
                latitude = point.first,
                longitude = point.second,
                cumulativeDistanceMeters = cumulativeDistance
            )
        }
        return navigablePoints
    }

    private fun locateStepStartIndex(
        routePoints: List<Pair<Double, Double>>,
        stepPoints: List<Pair<Double, Double>>,
        searchStartIndex: Int,
    ): Int? {
        val target = stepPoints.firstOrNull() ?: return null
        var bestIndex: Int? = null
        var bestDistance = Double.POSITIVE_INFINITY

        for (index in searchStartIndex until routePoints.size) {
            val routePoint = routePoints[index]
            val distance = distanceMeters(
                routePoint.first,
                routePoint.second,
                target.first,
                target.second
            )
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
            if (distance <= STEP_MATCH_THRESHOLD_METERS) {
                return index
            }
        }

        return bestIndex
    }

    private fun ValhallaStep.defaultInstruction(): String? = when (maneuver?.toNavigationDirection()) {
        ManeuverDirection.STRAIGHT -> if (name.isNullOrBlank()) "Continue straight" else "Continue on $name"
        ManeuverDirection.SLIGHT_LEFT -> if (name.isNullOrBlank()) "Keep left" else "Keep left on $name"
        ManeuverDirection.LEFT -> if (name.isNullOrBlank()) "Turn left" else "Turn left onto $name"
        ManeuverDirection.SHARP_LEFT -> if (name.isNullOrBlank()) "Sharp left" else "Sharp left onto $name"
        ManeuverDirection.U_TURN -> "Turn around"
        ManeuverDirection.SLIGHT_RIGHT -> if (name.isNullOrBlank()) "Keep right" else "Keep right on $name"
        ManeuverDirection.RIGHT -> if (name.isNullOrBlank()) "Turn right" else "Turn right onto $name"
        ManeuverDirection.SHARP_RIGHT -> if (name.isNullOrBlank()) "Sharp right" else "Sharp right onto $name"
        ManeuverDirection.FINISH -> "Destination"
        null -> null
    }

    private fun ValhallaOsrmManeuver.toNavigationDirection(): ManeuverDirection = when (type?.lowercase(Locale.US)) {
        "arrive" -> ManeuverDirection.FINISH
        "depart", "continue", "notification", "new name" -> modifier.toDirectionOrStraight()
        "turn", "fork", "merge", "on ramp", "off ramp", "end of road", "use lane" -> modifier.toDirectionOrStraight()
        "roundabout", "rotary", "roundabout turn" -> modifier.toDirectionOrStraight(default = ManeuverDirection.RIGHT)
        "uturn" -> ManeuverDirection.U_TURN
        else -> modifier.toDirectionOrStraight()
    }

    private fun String?.toDirectionOrStraight(default: ManeuverDirection = ManeuverDirection.STRAIGHT): ManeuverDirection {
        return when (this?.lowercase(Locale.US)) {
            "slight left" -> ManeuverDirection.SLIGHT_LEFT
            "left" -> ManeuverDirection.LEFT
            "sharp left" -> ManeuverDirection.SHARP_LEFT
            "uturn" -> ManeuverDirection.U_TURN
            "slight right" -> ManeuverDirection.SLIGHT_RIGHT
            "right" -> ManeuverDirection.RIGHT
            "sharp right" -> ManeuverDirection.SHARP_RIGHT
            "straight" -> ManeuverDirection.STRAIGHT
            else -> default
        }
    }

    private fun decodePolyline6(encoded: String): List<Pair<Double, Double>> {
        if (encoded.isBlank()) return emptyList()

        val coordinates = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var latitude = 0
        var longitude = 0

        while (index < encoded.length) {
            val latitudeResult = decodeNextValue(encoded, index)
            latitude += latitudeResult.first
            index = latitudeResult.second

            val longitudeResult = decodeNextValue(encoded, index)
            longitude += longitudeResult.first
            index = longitudeResult.second

            coordinates += Pair(latitude / 1e6, longitude / 1e6)
        }

        return coordinates
    }

    private fun decodeNextValue(encoded: String, startIndex: Int): Pair<Int, Int> {
        var index = startIndex
        var shift = 0
        var result = 0
        var byte: Int

        do {
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)

        val value = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        return Pair(value, index)
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

    private fun bearingDegrees(
        start: Pair<Double, Double>,
        end: Pair<Double, Double>,
    ): Double {
        val results = FloatArray(2)
        Location.distanceBetween(
            start.first,
            start.second,
            end.first,
            end.second,
            results
        )
        return results[1].toDouble()
    }

    private fun normalizeDegrees(value: Double): Double {
        var normalized = value
        while (normalized <= -180.0) normalized += 360.0
        while (normalized > 180.0) normalized -= 360.0
        return normalized
    }
}

private data class ValhallaTraceRouteRequest(
    val shape: List<ValhallaShapePoint>,
    val costing: String,
    val format: String,
    @Json(name = "shape_format") val shapeFormat: String,
    @Json(name = "shape_match") val shapeMatch: String,
    @Json(name = "roundabout_exits") val roundaboutExits: Boolean,
    val language: String,
    @Json(name = "use_timestamps") val useTimestamps: Boolean,
    @Json(name = "trace_options") val traceOptions: ValhallaTraceOptions,
)

private data class ValhallaShapePoint(
    val lat: Double,
    val lon: Double,
    val time: Long? = null,
)

private data class ValhallaRouteLocation(
    val lat: Double,
    val lon: Double,
    val type: String = "break",
    val heading: Float? = null,
    @Json(name = "heading_tolerance") val headingTolerance: Int? = null,
    val radius: Int? = null,
)

private data class ValhallaRouteRequest(
    val locations: List<ValhallaRouteLocation>,
    val costing: String,
    val format: String,
    @Json(name = "shape_format") val shapeFormat: String,
    @Json(name = "roundabout_exits") val roundaboutExits: Boolean,
    val language: String,
)

private data class ValhallaTraceOptions(
    @Json(name = "search_radius") val searchRadius: Int,
    @Json(name = "gps_accuracy") val gpsAccuracy: Int,
    @Json(name = "breakage_distance") val breakageDistance: Int,
    @Json(name = "interpolation_distance") val interpolationDistance: Int,
)

private data class ValhallaOsrmResponse(
    val code: String? = null,
    val routes: List<ValhallaRoute> = emptyList(),
    val matchings: List<ValhallaRoute> = emptyList(),
)

private data class ValhallaRoute(
    val geometry: String? = null,
    val legs: List<ValhallaLeg> = emptyList(),
)

private data class ValhallaLeg(
    val steps: List<ValhallaStep> = emptyList(),
)

private data class ValhallaStep(
    val name: String? = null,
    val geometry: String? = null,
    val maneuver: ValhallaOsrmManeuver? = null,
)

private data class ValhallaOsrmManeuver(
    val type: String? = null,
    val modifier: String? = null,
    val instruction: String? = null,
    val exit: Int? = null,
)
