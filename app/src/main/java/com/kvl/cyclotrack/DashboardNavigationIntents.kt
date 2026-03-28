package com.kvl.cyclotrack

import android.content.Context
import android.content.Intent

fun createDashboardIntent(
    context: Context,
    tripId: Long = -1L,
    navigationConfig: DashboardNavigationConfig? = null,
): Intent = Intent(context, DashboardActivity::class.java).apply {
    putExtra("tripId", tripId)
    putExtra("dashboardMode", navigationConfig?.mode?.wireValue ?: DashboardMode.RECORDING.wireValue)
    putExtra(
        "navigationSourceType",
        navigationConfig?.sourceType?.wireValue ?: NavigationSourceType.NONE.wireValue
    )
    putExtra("navigationSourceId", navigationConfig?.sourceId ?: -1L)
}
