package com.kvl.cyclotrack

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RoutesViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
    private val routePointRepository: RoutePointRepository,
    private val routeImportService: RouteImportService,
) : ViewModel() {
    val allRoutes = routeRepository.observeAll()

    suspend fun getRoutePoints(routeId: Long) = routePointRepository.get(routeId)

    suspend fun importRoute(uri: Uri) = routeImportService.import(uri)
}
