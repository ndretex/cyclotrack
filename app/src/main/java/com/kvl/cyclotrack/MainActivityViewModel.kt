package com.kvl.cyclotrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    private val tripsRepository: TripsRepository,
) : ViewModel() {
    val latestTrip = tripsRepository.observeNewest()

    suspend fun createTrip(): Long = tripsRepository.createNewTrip()

    fun removeTrip(tripId: Long) {
        viewModelScope.launch {
            tripsRepository.removeTrip(tripId)
        }
    }
}
