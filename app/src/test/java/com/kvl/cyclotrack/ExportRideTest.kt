package com.kvl.cyclotrack

import com.kvl.cyclotrack.data.CadenceSpeedMeasurement
import com.kvl.cyclotrack.data.HeartRateMeasurement
import com.kvl.cyclotrack.data.SensorType
import org.junit.Assert
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class ExportRideTest {
    @Test
    fun getDataCsv_noData() {
        val testArray = ArrayList<Measurements>()
        val result = getDataCsv(measurements = testArray.toTypedArray())
        Assert.assertEquals(1, result.size)
        Assert.assertArrayEquals(
            arrayOf("accuracy,altitude,bearing,bearingAccuracyDegrees,elapsedRealtimeNanos,elapsedRealtimeUncertaintyNanos,id,latitude,longitude,speed,speedAccuracyMetersPerSecond,time,tripId,verticalAccuracyMeters,"),
            result
        )
    }

    @Test
    fun getDataCsv_basic() {
        val testMeasurements = Measurements(
            tripId = 1,
            accuracy = 1.0f,
            altitude = 100.0,
            bearing = 0.0f,
            elapsedRealtimeNanos = 123456,
            latitude = 123.123,
            longitude = 321.321,
            speed = 11.11f,
            time = 98765432,
            bearingAccuracyDegrees = 0.1f,
            elapsedRealtimeUncertaintyNanos = 1.0,
            speedAccuracyMetersPerSecond = 0.01f,
            verticalAccuracyMeters = 0.01f,
            id = 1
        )

        val testArray = ArrayList<Measurements>()

        testArray.add(testMeasurements)
        var result = getDataCsv(measurements = testArray.toTypedArray())
        Assert.assertEquals(2, result.size)
        Assert.assertArrayEquals(
            arrayOf(
                "accuracy,altitude,bearing,bearingAccuracyDegrees,elapsedRealtimeNanos,elapsedRealtimeUncertaintyNanos,id,latitude,longitude,speed,speedAccuracyMetersPerSecond,time,tripId,verticalAccuracyMeters,",
                "1.0,100.0,0.0,0.1,123456,1.0,1,123.123,321.321,11.11,0.01,98765432,1,0.01,"
            ),
            result
        )

        testArray.clear()
        for (i in 0..9) {
            testArray.add(testMeasurements.copy(id = i.toLong()))
        }
        result = getDataCsv(measurements = testArray.toTypedArray())
        Assert.assertEquals(11, result.size)
        Assert.assertArrayEquals(
            arrayOf(
                "accuracy,altitude,bearing,bearingAccuracyDegrees,elapsedRealtimeNanos,elapsedRealtimeUncertaintyNanos,id,latitude,longitude,speed,speedAccuracyMetersPerSecond,time,tripId,verticalAccuracyMeters,",
                "1.0,100.0,0.0,0.1,123456,1.0,0,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,1,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,2,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,3,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,4,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,5,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,6,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,7,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,8,123.123,321.321,11.11,0.01,98765432,1,0.01,",
                "1.0,100.0,0.0,0.1,123456,1.0,9,123.123,321.321,11.11,0.01,98765432,1,0.01,",
            ),
            result
        )
    }

    @Test
    fun buildGpx_createsSegmentedTrackAndEscapesMetadata() {
        val tripId = 7L
        val exportData = TripDetailsViewModel.ExportData(
            summary = Trip(
                id = tripId,
                bikeId = 1,
                inProgress = false,
                name = "Morning & coffee",
                notes = "Climb <fast>",
                autoWheelCircumference = 2.0f,
                timestamp = 1_000L,
                duration = 3.0
            ),
            measurements = arrayOf(
                Measurements(
                    tripId = tripId,
                    accuracy = 1.0f,
                    altitude = 10.5,
                    bearing = 0.0f,
                    elapsedRealtimeNanos = 0L,
                    latitude = 48.8566,
                    longitude = 2.3522,
                    speed = 5.2f,
                    time = 1_500L,
                ),
                Measurements(
                    tripId = tripId,
                    accuracy = 1.0f,
                    altitude = 11.0,
                    bearing = 0.0f,
                    elapsedRealtimeNanos = 0L,
                    latitude = 48.857,
                    longitude = 2.353,
                    speed = 5.4f,
                    time = 2_000L,
                ),
                Measurements(
                    tripId = tripId,
                    accuracy = 1.0f,
                    altitude = 12.0,
                    bearing = 0.0f,
                    elapsedRealtimeNanos = 0L,
                    latitude = 48.858,
                    longitude = 2.354,
                    speed = 5.6f,
                    time = 3_500L,
                )
            ),
            timeStates = arrayOf(
                TimeState(tripId = tripId, state = TimeStateEnum.START, timestamp = 1_000L),
                TimeState(tripId = tripId, state = TimeStateEnum.PAUSE, timestamp = 2_500L),
                TimeState(tripId = tripId, state = TimeStateEnum.RESUME, timestamp = 3_000L),
                TimeState(tripId = tripId, state = TimeStateEnum.STOP, timestamp = 4_000L),
            ),
            splits = emptyArray(),
            onboardSensors = emptyArray(),
            weather = emptyArray(),
            heartRateMeasurements = arrayOf(
                HeartRateMeasurement(tripId = tripId, timestamp = 1_500L, heartRate = 120, energyExpended = null, rrIntervals = null),
                HeartRateMeasurement(tripId = tripId, timestamp = 2_000L, heartRate = 121, energyExpended = null, rrIntervals = null),
                HeartRateMeasurement(tripId = tripId, timestamp = 3_500L, heartRate = 122, energyExpended = null, rrIntervals = null)
            ),
            speedMeasurements = arrayOf(
                CadenceSpeedMeasurement(
                    tripId = tripId,
                    timestamp = 1_500L,
                    revolutions = 0,
                    lastEvent = 0,
                    rpm = 180f,
                    sensorType = SensorType.SPEED
                ),
                CadenceSpeedMeasurement(
                    tripId = tripId,
                    timestamp = 2_000L,
                    revolutions = 0,
                    lastEvent = 0,
                    rpm = 183f,
                    sensorType = SensorType.SPEED
                ),
                CadenceSpeedMeasurement(
                    tripId = tripId,
                    timestamp = 3_500L,
                    revolutions = 0,
                    lastEvent = 0,
                    rpm = 186f,
                    sensorType = SensorType.SPEED
                )
            ),
            cadenceMeasurements = arrayOf(
                CadenceSpeedMeasurement(
                    tripId = tripId,
                    timestamp = 1_500L,
                    revolutions = 0,
                    lastEvent = 0,
                    rpm = 80f,
                    sensorType = SensorType.CADENCE
                ),
                CadenceSpeedMeasurement(
                    tripId = tripId,
                    timestamp = 2_000L,
                    revolutions = 0,
                    lastEvent = 0,
                    rpm = 81f,
                    sensorType = SensorType.CADENCE
                ),
                CadenceSpeedMeasurement(
                    tripId = tripId,
                    timestamp = 3_500L,
                    revolutions = 0,
                    lastEvent = 0,
                    rpm = 82f,
                    sensorType = SensorType.CADENCE
                )
            )
        )

        val result = buildGpx(exportData)
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(InputSource(StringReader(result)))

        Assert.assertTrue(result.contains("Morning &amp; coffee"))
        Assert.assertTrue(result.contains("Climb &lt;fast&gt;"))
        Assert.assertEquals("gpx", document.documentElement.localName)
        Assert.assertEquals(2, document.getElementsByTagNameNS("*", "trkseg").length)
        Assert.assertEquals(3, document.getElementsByTagNameNS("*", "trkpt").length)
        Assert.assertEquals(3, document.getElementsByTagNameNS("*", "TrackPointExtension").length)
        Assert.assertEquals(3, document.getElementsByTagNameNS("*", "hr").length)
        Assert.assertEquals(3, document.getElementsByTagNameNS("*", "cad").length)
        Assert.assertEquals(3, document.getElementsByTagNameNS("*", "speed").length)
        Assert.assertEquals("120", document.getElementsByTagNameNS("*", "hr").item(0).textContent)
        Assert.assertEquals("80", document.getElementsByTagNameNS("*", "cad").item(0).textContent)
        Assert.assertEquals("6", document.getElementsByTagNameNS("*", "speed").item(0).textContent)
        Assert.assertEquals("cycling", document.getElementsByTagNameNS("*", "type").item(0).textContent)
        Assert.assertEquals(
            "1970-01-01T00:00:01Z",
            document.getElementsByTagNameNS("*", "metadata").item(0)
                .childNodes
                .let { nodes ->
                    (0 until nodes.length)
                        .map(nodes::item)
                        .first { it.localName == "time" }
                        .textContent
                }
        )
    }
}
