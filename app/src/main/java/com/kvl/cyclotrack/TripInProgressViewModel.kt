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
    private val timeStateRepository: TimeStateRepository,
    private val splitRepository: SplitRepository,
    private val gpsService: GpsService,
    weatherRepository: WeatherRepository,
) : ViewModel() {
    private val logTag = "TripInProgressViewModel"

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

    val burnInReductionActive = MutableLiveData(burnInReductionUserPref)

    private fun tripInProgress() = isTripInProgress(currentState)

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
        if (!config.isNavigation) {
            navigationPath = null
            lastMatchedIndex = -1
            _guidanceSnapshot.value = null
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            navigationPath = runCatching {
                when (config.sourceType) {
                    NavigationSourceType.ROUTE -> {
                        val route = routeRepository.get(config.sourceId)
                        val routePoints = routePointRepository.get(config.sourceId)
                        route?.let {
                            buildNavigablePath(it.name, routePoints)
                        }
                    }

                    NavigationSourceType.TRIP -> {
                        val trip = tripsRepository.get(config.sourceId)
                        val measurements = measurementsRepository.get(config.sourceId)
                        buildNavigablePath(
                            trip.name ?: getDefaultTripName(),
                            measurements
                        )
                    }

                    else -> null
                }
            }.getOrNull()

            lastMatchedIndex = -1
            _guidanceSnapshot.postValue(navigationPath?.let(::initialGuidanceSnapshot))
        }
    }

    fun updateNavigation(location: Location) {
        val path = navigationPath ?: return
        val snapshot = computeGuidanceSnapshot(path, location, lastMatchedIndex)
        lastMatchedIndex = snapshot.matchedPointIndex
        _guidanceSnapshot.value = snapshot
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
