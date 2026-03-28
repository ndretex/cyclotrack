package com.kvl.cyclotrack.util

import android.content.Context
import androidx.core.content.edit
import com.kvl.cyclotrack.ActiveNavigationSession
import com.kvl.cyclotrack.DashboardNavigationConfig
import com.kvl.cyclotrack.NavigationSourceType

private const val PREF_ACTIVE_NAVIGATION_SOURCE_TYPE = "active_navigation_source_type"
private const val PREF_ACTIVE_NAVIGATION_SOURCE_ID = "active_navigation_source_id"
private const val PREF_ACTIVE_NAVIGATION_RECORDING_TRIP_ID = "active_navigation_recording_trip_id"

fun putActiveNavigationSession(
    context: Context,
    config: DashboardNavigationConfig,
) {
    if (!config.isNavigation) {
        clearActiveNavigationSession(context)
        return
    }

    getPreferences(context).edit {
        putString(PREF_ACTIVE_NAVIGATION_SOURCE_TYPE, config.sourceType.wireValue)
        putLong(PREF_ACTIVE_NAVIGATION_SOURCE_ID, config.sourceId)
        putLong(PREF_ACTIVE_NAVIGATION_RECORDING_TRIP_ID, -1L)
    }
}

fun updateActiveNavigationRecordingTripId(
    context: Context,
    tripId: Long,
) {
    val session = getActiveNavigationSession(context) ?: return
    getPreferences(context).edit {
        putString(PREF_ACTIVE_NAVIGATION_SOURCE_TYPE, session.sourceType.wireValue)
        putLong(PREF_ACTIVE_NAVIGATION_SOURCE_ID, session.sourceId)
        putLong(PREF_ACTIVE_NAVIGATION_RECORDING_TRIP_ID, tripId)
    }
}

fun getActiveNavigationSession(context: Context): ActiveNavigationSession? {
    val prefs = getPreferences(context)
    val sourceType = NavigationSourceType.fromWireValue(
        prefs.getString(PREF_ACTIVE_NAVIGATION_SOURCE_TYPE, null)
    )
    if (sourceType == NavigationSourceType.NONE) return null

    return ActiveNavigationSession(
        sourceType = sourceType,
        sourceId = prefs.getLong(PREF_ACTIVE_NAVIGATION_SOURCE_ID, -1L),
        recordingTripId = prefs.getLong(PREF_ACTIVE_NAVIGATION_RECORDING_TRIP_ID, -1L)
    ).takeIf { it.sourceId >= 0L }
}

fun clearActiveNavigationSession(context: Context) {
    getPreferences(context).edit {
        remove(PREF_ACTIVE_NAVIGATION_SOURCE_TYPE)
        remove(PREF_ACTIVE_NAVIGATION_SOURCE_ID)
        remove(PREF_ACTIVE_NAVIGATION_RECORDING_TRIP_ID)
    }
}
