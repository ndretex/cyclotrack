package com.kvl.cyclotrack

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvl.cyclotrack.events.TripProgressEvent
import com.kvl.cyclotrack.util.SystemUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Timer
import javax.inject.Inject
import kotlin.concurrent.timerTask

@HiltViewModel
class TripInProgressViewModel @Inject constructor(
    private val bikeRepository: BikeRepository,
    private val tripsRepository: TripsRepository,
    private val measurementsRepository: MeasurementsRepository,
    private val routeRepository: RouteRepository,
    private val routePointRepository: RoutePointRepository,
    private val valhallaNavigationService: ValhallaNavigationService,
    private val timeStateRepository: TimeStateRepository,
    private val splitRepository: SplitRepository,
    private val gpsService: GpsService,
    weatherRepository: WeatherRepository,
) : ViewModel() {
    private val logTag = "TripInProgressViewModel"

    companion object {
        private const val OFF_ROUTE_REROUTE_GRACE_COUNT = 3
        private const val REROUTE_COOLDOWN_MS = 15_000L
        private const val REROUTE_REJOIN_DISTANCE_METERS = 350.0
        private const val REROUTE_MIN_CUE_AHEAD_METERS = 40.0
    }

    var bikeWheelCircumference: Float? = null

    var burnInReductionUserPref = false;
    fun burnInReductionEnabled() =
        burnInReductionUserPref || (_currentProgress.value?.duration ?: 0.0) > 7200.0

    init {
        EventBus.getDefault().register(this)
        viewModelScope.launch(Dispatchers.IO) {
            bikeWheelCircumference =
                userCircumferenceToMeters(bikeRepository.getDefaultBike().wheelCircumference)
            Log.d(logTag, bikeWheelCircumference.toString())
        }
    }

    var currentState: TimeStateEnum = TimeStateEnum.STOP
    suspend fun getCurrentTimeState(tripId: Long) =
        timeStateRepository.getLatest(tripId).let { newTimeState ->
            currentState = newTimeState.state
            newTimeState
        }

    private var accumulatedDuration = 0.0
    private var startTime = Double.NaN
    private var _lastCompleteSplitLive = MutableLiveData<Split>()
    private val clockTick = Timer()
    private val _currentProgress = MutableLiveData<TripProgress>()
    private val _currentTime = MutableLiveData<Double>()
    private val _guidanceSnapshot = MutableLiveData<GuidanceSnapshot?>()
    private val currentTimeStateObserver: Observer<TimeState> = Observer { timeState ->
        Log.d(logTag, "onChanged current time state observer: ${timeState.state}")
        currentState = timeState.state
    }
    private var navigationConfig = DashboardNavigationConfig()
    private var navigationPath: NavigablePath? = null
    private var lastMatchedIndex = -1
    private var activeNavigationName: String? = null
    private var rerouteAttemptCount = 0
    private var lastRerouteAttemptAt = 0L
    private var rerouteJob: Job? = null

    val burnInReductionActive = MutableLiveData(burnInReductionUserPref)

    private data class NavigationSourcePayload(
        val name: String,
        val routePoints: Array<RoutePoint>? = null,
        val measurements: Array<Measurements>? = null,
    ) {
        fun buildLocalPath(): NavigablePath? =
            routePoints?.let { buildNavigablePath(name, it) }
                ?: measurements?.let { buildNavigablePath(name, it) }

        suspend fun buildValhallaPath(service: ValhallaNavigationService): NavigablePath? =
            routePoints?.let { service.buildPath(name, it) }
                ?: measurements?.let { service.buildPath(name, it) }
    }

    private fun tripInProgress() = isTripInProgress(currentState)

    private fun snapshotForPath(path: NavigablePath?): GuidanceSnapshot? {
        if (path == null) return null
        val currentLocation = gpsService.value
        return if (currentLocation != null) {
            computeGuidanceSnapshot(path, currentLocation, -1)
        } else {
            initialGuidanceSnapshot(path)
        }
    }

    val gpsEnabled: LiveData<Boolean>
        get() = gpsService.accessGranted

    val location: LiveData<Location>
        get() = gpsService

    val hrmSensor = MutableLiveData(HrmData(null, null))
    val cadenceSensor = MutableLiveData(CadenceData(null, null, null, null))
    val speedSensor = MutableLiveData(SpeedData(null, null, null, null))

    val latestWeather = weatherRepository.observeLatest()

    suspend fun getFastestDistance(distance: Int, conversionFactor: Double, limit: Int = 10) =
        splitRepository.getFastestDistance(
            distance = distance,
            conversionFactor = conversionFactor,
            limit = limit
        )

    suspend fun getFastestSplit(distance: Int, conversionFactor: Double, limit: Int = 10) =
        splitRepository.getFastestSplit(
            distance = distance,
            conversionFactor = conversionFactor,
            limit = limit
        )

    @Subscribe
    fun onHrmData(hrm: HrmData) {
        hrmSensor.postValue(hrm)
    }

    @Subscribe
    fun onCadenceData(cadence: CadenceData) {
        cadenceSensor.postValue(cadence)
    }

    @Subscribe
    fun onSpeedData(speed: SpeedData) {
        speedSensor.postValue(speed)
    }

    val currentProgress: LiveData<TripProgress>
        get() = _currentProgress

    val currentTime: LiveData<Double>
        get() = _currentTime

    val lastCompleteSplit: LiveData<Split>
        get() = _lastCompleteSplitLive

    val guidanceSnapshot: LiveData<GuidanceSnapshot?>
        get() = _guidanceSnapshot

    var tripId: Long? = null

    fun configureNavigation(config: DashboardNavigationConfig) {
        if (navigationConfig == config && (config.isNavigation == false || navigationPath != null)) {
            return
        }

        navigationConfig = config
        rerouteAttemptCount = 0
        lastRerouteAttemptAt = 0L
        rerouteJob?.cancel()
        rerouteJob = null
        if (!config.isNavigation) {
            Log.d(logTag, "Navigation disabled; clearing active guidance path")
            navigationPath = null
            lastMatchedIndex = -1
            activeNavigationName = null
            _guidanceSnapshot.value = null
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val sourcePayload = runCatching {
                when (config.sourceType) {
                    NavigationSourceType.ROUTE -> {
                        val route = routeRepository.get(config.sourceId) ?: return@runCatching null
                        NavigationSourcePayload(
                            name = route.name,
                            routePoints = routePointRepository.get(config.sourceId)
                        )
                    }

                    NavigationSourceType.TRIP -> {
                        val trip = tripsRepository.get(config.sourceId)
                        NavigationSourcePayload(
                            name = trip.name ?: getDefaultTripName(),
                            measurements = measurementsRepository.get(config.sourceId)
                        )
                    }

                    else -> null
                }
            }.getOrNull()

            if (sourcePayload == null) {
                Log.w(
                    logTag,
                    "No navigation source payload for mode=${config.mode}, sourceType=${config.sourceType}, sourceId=${config.sourceId}"
                )
            } else {
                Log.i(
                    logTag,
                    "Configuring navigation for \"${sourcePayload.name}\": sourceType=${config.sourceType}, sourceId=${config.sourceId}"
                )
            }

            activeNavigationName = sourcePayload?.name

            navigationPath = sourcePayload?.buildLocalPath()
            lastMatchedIndex = -1
            Log.i(
                logTag,
                if (navigationPath != null) {
                    "Using local navigation path for \"${sourcePayload?.name}\": points=${navigationPath?.points?.size}, cues=${navigationPath?.cues?.size}"
                } else {
                    "Local navigation path unavailable for \"${sourcePayload?.name}\""
                }
            )
            _guidanceSnapshot.postValue(snapshotForPath(navigationPath))

            val matchedPath = sourcePayload?.buildValhallaPath(valhallaNavigationService)
            if (matchedPath != null) {
                navigationPath = matchedPath
                lastMatchedIndex = -1
                Log.i(
                    logTag,
                    "Switched to Valhalla navigation path for \"${sourcePayload.name}\": points=${matchedPath.points.size}, cues=${matchedPath.cues.size}"
                )
                _guidanceSnapshot.postValue(snapshotForPath(matchedPath))
            } else if (sourcePayload != null) {
                Log.w(
                    logTag,
                    "Valhalla navigation unavailable for \"${sourcePayload.name}\"; staying on ${navigationPath?.source ?: NavigationEngineSource.LOCAL}"
                )
            }
        }
    }

    fun updateNavigation(location: Location) {
        val path = navigationPath ?: return
        val snapshot = computeGuidanceSnapshot(path, location, lastMatchedIndex)
        lastMatchedIndex = snapshot.matchedPointIndex
        _guidanceSnapshot.value = snapshot
        maybeReroute(path, snapshot, location)
    }

    private fun maybeReroute(
        path: NavigablePath,
        snapshot: GuidanceSnapshot,
        location: Location,
    ) {
        if (!navigationConfig.isNavigation || !valhallaNavigationService.isConfigured()) return
        if (snapshot.state == GuidanceUiState.GPS_WEAK || snapshot.state == GuidanceUiState.ARRIVAL) {
            rerouteAttemptCount = 0
            return
        }
        if (snapshot.state != GuidanceUiState.OFF_ROUTE) {
            rerouteAttemptCount = 0
            return
        }

        rerouteAttemptCount += 1
        val now = SystemUtils.currentTimeMillis()
        if (rerouteAttemptCount < OFF_ROUTE_REROUTE_GRACE_COUNT) return
        if (rerouteJob?.isActive == true) return
        if (now - lastRerouteAttemptAt < REROUTE_COOLDOWN_MS) return

        val destination = selectRerouteDestination(path, snapshot) ?: return
        val routeName = activeNavigationName ?: path.name
        lastRerouteAttemptAt = now
        rerouteJob = viewModelScope.launch(Dispatchers.IO) {
            val reroutedPath = valhallaNavigationService.reroute(
                name = routeName,
                currentLocation = location,
                destination = destination
            ) ?: return@launch

            navigationPath = reroutedPath
            lastMatchedIndex = -1
            rerouteAttemptCount = 0
            Log.i(
                logTag,
                "Applied Valhalla reroute for \"$routeName\": points=${reroutedPath.points.size}, cues=${reroutedPath.cues.size}"
            )
            _guidanceSnapshot.postValue(computeGuidanceSnapshot(reroutedPath, location, -1))
        }
    }

    private fun selectRerouteDestination(
        path: NavigablePath,
        snapshot: GuidanceSnapshot,
    ): NavigablePoint? {
        if (path.points.isEmpty() || snapshot.matchedPointIndex !in path.points.indices) return null

        val matchedIndex = snapshot.matchedPointIndex
        val matchedPoint = path.points[matchedIndex]
        val nextCueIndex = path.cues.firstOrNull {
            it.distanceFromStartMeters >=
                matchedPoint.cumulativeDistanceMeters + REROUTE_MIN_CUE_AHEAD_METERS
        }?.pointIndex
        val distanceAheadIndex = path.points.indexOfFirst {
            it.cumulativeDistanceMeters >=
                matchedPoint.cumulativeDistanceMeters + REROUTE_REJOIN_DISTANCE_METERS
        }.let { if (it >= 0) it else path.points.lastIndex }

        val destinationIndex = maxOf(
            matchedIndex + 1,
            nextCueIndex ?: distanceAheadIndex,
            distanceAheadIndex
        ).coerceAtMost(path.points.lastIndex)

        if (isLoopRoute(path) &&
            destinationIndex == path.points.lastIndex &&
            progressFractionAt(path, matchedIndex) < 0.9
        ) {
            Log.w(
                logTag,
                "Skipping reroute-to-finish for loop route \"${path.name}\": matchedIndex=$matchedIndex, progress=${progressFractionAt(path, matchedIndex)}"
            )
            return null
        }

        return path.points[destinationIndex]
    }

    suspend fun removeTripWhenFinished(tripId: Long): Boolean {
        val trip = runCatching { tripsRepository.get(tripId) }.getOrNull() ?: return true
        if (trip.inProgress) return false
        tripsRepository.removeTrip(tripId)
        return true
    }

    suspend fun forceRemoveTrip(tripId: Long) {
        tripsRepository.removeTrip(tripId)
    }

    private fun accumulateDuration(timeStates: Array<TimeState>?) {
        timeStates?.let { ts ->
            getTripInProgressIntervals(ts).takeIf { it.isNotEmpty() }?.let { intervals ->
                accumulatedDuration = accumulateTripTime(intervals)
                startTime = intervals.last().last / 1e3
            }
        }
        Log.v(
            logTag,
            "accumulatedDuration = ${accumulatedDuration}; startTime = ${startTime}"
        )
    }

    private val accumulateDurationObserver: Observer<Array<TimeState>> =
        Observer { accumulateDuration(it) }

    private val lastCompleteSplitObserver: Observer<Split?> = Observer { newSplit ->
        newSplit?.let { n -> _lastCompleteSplitLive.value = n };
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTripProgressEvent(event: TripProgressEvent) {
        Log.d(logTag, "Received trip progress event")
        _currentProgress.value = event.tripProgress
    }

    fun currentTimeState(tripId: Long) = timeStateRepository.observeLatest(tripId)

    private fun getDuration() =
        if (startTime.isFinite() && tripInProgress()) accumulatedDuration + (SystemUtils.currentTimeMillis() / 1e3) - startTime else accumulatedDuration

    private fun startObserving(tripId: Long, lifecycleOwner: LifecycleOwner) {
        Log.d(logTag, "Start observing trip ID $tripId $currentTimeStateObserver")
        timeStateRepository.observeLatest(tripId)
            .observe(lifecycleOwner, currentTimeStateObserver)
        timeStateRepository.observeTimeStates(tripId)
            .observe(lifecycleOwner, accumulateDurationObserver)
        splitRepository.observeLastCompleteSplit(tripId)
            .observe(lifecycleOwner, lastCompleteSplitObserver)
    }

    fun startTrip(tripId: Long, lifecycleOwner: LifecycleOwner) {
        this.tripId = tripId
        startObserving(tripId, lifecycleOwner)
        startClock()
    }

    private fun startClock() {
        clockTick.scheduleAtFixedRate(timerTask {
            val timeHandler = Handler(Looper.getMainLooper())
            timeHandler.post {
                getDuration().let {
                    Log.v("TIP_TIME_TICK", it.toString())
                    _currentTime.value = it
                }
            }
        }, 1000 - SystemUtils.currentTimeMillis() % 1000, 500)
    }

    fun resumeTrip(tripId: Long, lifecycleOwner: LifecycleOwner) {
        Log.d(logTag, "Resuming trip $tripId")
        startObserving(tripId, lifecycleOwner)
        startClock()
    }

    private fun cleanup() {
        EventBus.getDefault().unregister(this)
        clockTick.cancel()
    }

    override fun onCleared() {
        Log.d(logTag, "Called onCleared")
        super.onCleared()
        cleanup()
    }
}
