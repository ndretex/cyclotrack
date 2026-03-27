package com.kvl.cyclotrack

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RouteDetailsViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
    private val routePointRepository: RoutePointRepository,
) : ViewModel() {
    var routeId: Long = -1L
        set(value) {
            routeOverview = routeRepository.observe(value)
            routePoints = routePointRepository.observe(value)
            field = value
        }

    lateinit var routeOverview: LiveData<Route?> private set
    lateinit var routePoints: LiveData<Array<RoutePoint>> private set

    fun renameRoute(newName: String) {
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty()) return

        viewModelScope.launch {
            val route = routeRepository.get(routeId) ?: return@launch
            if (route.name != trimmedName) {
                routeRepository.update(route.copy(name = trimmedName))
            }
        }
    }
}
