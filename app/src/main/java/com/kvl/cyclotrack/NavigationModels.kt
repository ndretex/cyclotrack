package com.kvl.cyclotrack

enum class DashboardMode(val wireValue: String) {
    RECORDING("recording"),
    NAVIGATION("navigation");

    companion object {
        fun fromWireValue(value: String?): DashboardMode =
            entries.firstOrNull { it.wireValue == value } ?: RECORDING
    }
}

enum class NavigationSourceType(val wireValue: String) {
    NONE("none"),
    ROUTE("route"),
    TRIP("trip");

    companion object {
        fun fromWireValue(value: String?): NavigationSourceType =
            entries.firstOrNull { it.wireValue == value } ?: NONE
    }
}

enum class ManeuverDirection {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    U_TURN,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    FINISH,
}

enum class GuidanceUiState {
    GUIDANCE,
    TURN_GUIDANCE,
    OFF_ROUTE,
    ARRIVAL,
    GPS_WEAK,
}

enum class NavigationEngineSource {
    LOCAL,
    VALHALLA,
}

data class DashboardNavigationConfig(
    val mode: DashboardMode = DashboardMode.RECORDING,
    val sourceType: NavigationSourceType = NavigationSourceType.NONE,
    val sourceId: Long = -1L,
) {
    val isNavigation: Boolean
        get() = mode == DashboardMode.NAVIGATION &&
                sourceType != NavigationSourceType.NONE &&
                sourceId >= 0L
}

data class NavigablePoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val cumulativeDistanceMeters: Double = 0.0,
)

data class NavigablePath(
    val name: String,
    val points: List<NavigablePoint>,
    val totalDistanceMeters: Double,
    val cues: List<NavigationCue>,
    val source: NavigationEngineSource = NavigationEngineSource.LOCAL,
)

data class NavigationCue(
    val maneuver: ManeuverDirection,
    val pointIndex: Int,
    val distanceFromStartMeters: Double,
    val instructionText: String? = null,
    val turnAngleDegrees: Double? = null,
)

data class GuidancePreviewPoint(
    val latitude: Double,
    val longitude: Double,
)

data class GuidanceSnapshot(
    val state: GuidanceUiState,
    val routeName: String,
    val maneuver: ManeuverDirection,
    val nextCueDistanceMeters: Double?,
    val remainingDistanceMeters: Double,
    val instructionText: String = "",
    val engineSource: NavigationEngineSource = NavigationEngineSource.LOCAL,
    val turnAngleDegrees: Double? = null,
    val previewPoints: List<GuidancePreviewPoint> = emptyList(),
    val previewCurrentIndex: Int = -1,
    val previewCueIndex: Int = -1,
    val matchedPointIndex: Int = -1,
)

data class ActiveNavigationSession(
    val sourceType: NavigationSourceType,
    val sourceId: Long,
    val recordingTripId: Long = -1L,
)
