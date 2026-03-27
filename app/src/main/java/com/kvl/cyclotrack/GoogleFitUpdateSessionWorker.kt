package com.kvl.cyclotrack

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kvl.cyclotrack.data.CadenceSpeedMeasurementRepository
import com.kvl.cyclotrack.data.HeartRateMeasurementRepository
import com.kvl.cyclotrack.util.hasFitnessPermissions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject

@HiltWorker
class GoogleFitUpdateSessionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    val logTag = "GoogleFitUpdateSessWkr"

    @Inject
    lateinit var tripsRepository: TripsRepository

    @Inject
    lateinit var measurementsRepository: MeasurementsRepository

    @Inject
    lateinit var bikeRepository: BikeRepository

    @Inject
    lateinit var cadenceSpeedMeasurementRepository: CadenceSpeedMeasurementRepository

    @Inject
    lateinit var heartRateMeasurementRepository: HeartRateMeasurementRepository

    @Inject
    lateinit var timeStateRepository: TimeStateRepository

    @Inject
    lateinit var googleFitApiService: GoogleFitApiService

    override suspend fun doWork(): Result {
        inputData.getLong("tripId", -1).takeIf { it >= 0 }?.let { tripId ->
            Log.i(logTag, "Syncing data with Google Fit for trip $tripId")
            try {
                if (!hasFitnessPermissions(applicationContext)) {
                    Log.i(logTag, "Skipping Google Fit update for trip $tripId: permissions not granted")
                    return Result.success()
                }

                val trip = tripsRepository.get(tripId)
                val timeStates = timeStateRepository.getTimeStates(tripId)
                val measurements = measurementsRepository.get(tripId)
                val speedMeasurements = cadenceSpeedMeasurementRepository.getSpeedMeasurements(tripId)
                val cadenceMeasurements = cadenceSpeedMeasurementRepository.getCadenceMeasurements(tripId)
                val heartRateMeasurements = heartRateMeasurementRepository.get(tripId)
                Log.i(
                    logTag,
                    "Preparing Google Fit update payload for trip $tripId: measurements=${measurements.size}, " +
                        "heartRate=${heartRateMeasurements.size}, speed=${speedMeasurements.size}, cadence=${cadenceMeasurements.size}, " +
                        "timeStates=${timeStates.size}"
                )

                googleFitApiService.updateDatasets(
                    measurements = measurements,
                    heartRateMeasurements = heartRateMeasurements,
                    speedMeasurements = speedMeasurements,
                    cadenceMeasurements = cadenceMeasurements,
                    wheelCircumference = getEffectiveCircumference(trip, speedMeasurements)
                        ?: userCircumferenceToMeters(bikeRepository.get(trip.bikeId)?.wheelCircumference)
                        ?: 0f
                )

                timeStates.takeIf { it.isNotEmpty() }?.let {
                    googleFitApiService.updateSession(trip, it)
                } ?: googleFitApiService.updateSession(
                    trip,
                    measurements.first().time,
                    measurements.last().time
                )

                tripsRepository.setGoogleFitSyncStatus(tripId, GoogleFitSyncStatusEnum.SYNCED)
                Log.i(logTag, "Google Fit update marked SYNCED for trip $tripId")
            } catch (e: Exception) {
                Log.e(logTag, "Failed to update trip $tripId", e)
                tripsRepository.setGoogleFitSyncStatus(tripId, GoogleFitSyncStatusEnum.FAILED)
                return Result.failure()
            }
        }
        return Result.success()
    }
}
