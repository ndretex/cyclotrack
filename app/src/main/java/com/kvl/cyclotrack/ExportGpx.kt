package com.kvl.cyclotrack

import android.content.ContentResolver
import android.net.Uri
import com.kvl.cyclotrack.data.CadenceSpeedMeasurement
import com.kvl.cyclotrack.data.HeartRateMeasurement
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private const val trackPointExtensionNamespace =
    "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
private const val trackPointExtensionSchema =
    "https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd"
private const val gpxSchemaLocation =
    "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd " +
            "$trackPointExtensionNamespace $trackPointExtensionSchema"
private const val maxSensorGapMillis = 5_000L

private data class GpxPointExtensions(
    val heartRate: Short? = null,
    val cadence: Int? = null,
    val speed: Double? = null,
) {
    fun hasValues(): Boolean = heartRate != null || cadence != null || speed != null
}

private class TimestampMatcher<T>(
    items: Array<T>,
    private val timestampOf: (T) -> Long,
) {
    private val sortedItems = items.sortedBy(timestampOf)
    private var index = 0

    fun nearest(timestamp: Long, maxGapMillis: Long = maxSensorGapMillis): T? {
        if (sortedItems.isEmpty()) return null

        while (index + 1 < sortedItems.size &&
            timestampOf(sortedItems[index + 1]) <= timestamp
        ) {
            index++
        }

        return listOf(index, index + 1)
            .filter { it in sortedItems.indices }
            .map { sortedItems[it] }
            .minByOrNull { abs(timestampOf(it) - timestamp) }
            ?.takeIf { abs(timestampOf(it) - timestamp) <= maxGapMillis }
    }
}

private fun escapeXml(value: String): String = buildString(value.length) {
    value.forEach { char ->
        append(
            when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> char
            }
        )
    }
}

private fun formatGpxNumber(value: Double): String =
    BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

private fun formatGpxTimestamp(timestamp: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp))

private fun appendOptionalTag(
    builder: StringBuilder,
    indent: String,
    tagName: String,
    value: String?,
) {
    if (!value.isNullOrBlank()) {
        builder.append(indent)
            .append('<')
            .append(tagName)
            .append('>')
            .append(escapeXml(value))
            .append("</")
            .append(tagName)
            .append(">\n")
    }
}

fun getGpxTrackSegments(exportData: TripDetailsViewModel.ExportData): Array<Array<Measurements>> {
    val sortedMeasurements = exportData.measurements
        ?.sortedBy { it.time }
        ?.toTypedArray()
        ?: return emptyArray()

    if (sortedMeasurements.isEmpty()) return emptyArray()

    val tripLegs = getTripLegs(
        sortedMeasurements,
        getTripIntervals(exportData.timeStates, sortedMeasurements)
    ).filter { it.isNotEmpty() }

    return if (tripLegs.isEmpty()) {
        arrayOf(sortedMeasurements)
    } else {
        tripLegs.toTypedArray()
    }
}

private fun Measurements.getGpxPointExtensions(
    heartRateMatcher: TimestampMatcher<HeartRateMeasurement>,
    cadenceMatcher: TimestampMatcher<CadenceSpeedMeasurement>,
    speedMatcher: TimestampMatcher<CadenceSpeedMeasurement>,
    circumference: Float?,
): GpxPointExtensions {
    val matchedHeartRate = heartRateMatcher.nearest(time)?.heartRate
    val matchedCadence = cadenceMatcher.nearest(time)?.rpm?.roundToInt()
    val matchedSpeed = speedMatcher.nearest(time)?.rpm?.let { rpm ->
        circumference?.let { rpm * it / 60.0 }
    } ?: speed.toDouble()

    return GpxPointExtensions(
        heartRate = matchedHeartRate,
        cadence = matchedCadence,
        speed = matchedSpeed
    )
}

fun buildGpx(exportData: TripDetailsViewModel.ExportData): String {
    val summary = requireNotNull(exportData.summary) { "Trip summary required for GPX export" }
    val startedAt = exportData.timeStates?.let(::getStartTime)
        ?: exportData.measurements?.minOfOrNull { it.time }
        ?: summary.timestamp
    val trackName = summary.name?.takeIf { it.isNotBlank() } ?: getDefaultTripName()
    val trackSegments = getGpxTrackSegments(exportData)
    val circumference = summary.autoWheelCircumference ?: summary.userWheelCircumference
    val heartRateMatcher = TimestampMatcher(exportData.heartRateMeasurements ?: emptyArray()) { it.timestamp }
    val cadenceMatcher =
        TimestampMatcher(exportData.cadenceMeasurements ?: emptyArray()) { it.timestamp }
    val speedMatcher = TimestampMatcher(exportData.speedMeasurements ?: emptyArray()) { it.timestamp }

    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Cyclotrack\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:gpxtpx=\"")
        append(trackPointExtensionNamespace)
        append("\" ")
        append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        append("xsi:schemaLocation=\"")
        append(gpxSchemaLocation)
        append("\">\n")
        append("  <metadata>\n")
        appendOptionalTag(this, "    ", "name", trackName)
        appendOptionalTag(this, "    ", "desc", summary.notes)
        append("    <time>")
        append(formatGpxTimestamp(startedAt))
        append("</time>\n")
        append("  </metadata>\n")
        append("  <trk>\n")
        appendOptionalTag(this, "    ", "name", trackName)
        appendOptionalTag(this, "    ", "desc", summary.notes)
        append("    <type>cycling</type>\n")
        trackSegments.forEach { segment ->
            append("    <trkseg>\n")
            segment.forEach { measurement ->
                val extensions = measurement.getGpxPointExtensions(
                    heartRateMatcher = heartRateMatcher,
                    cadenceMatcher = cadenceMatcher,
                    speedMatcher = speedMatcher,
                    circumference = circumference
                )
                append("      <trkpt lat=\"")
                append(formatGpxNumber(measurement.latitude))
                append("\" lon=\"")
                append(formatGpxNumber(measurement.longitude))
                append("\">\n")
                append("        <ele>")
                append(formatGpxNumber(measurement.altitude))
                append("</ele>\n")
                append("        <time>")
                append(formatGpxTimestamp(measurement.time))
                append("</time>\n")
                if (extensions.hasValues()) {
                    append("        <extensions>\n")
                    append("          <gpxtpx:TrackPointExtension>\n")
                    extensions.heartRate?.let { heartRate ->
                        append("            <gpxtpx:hr>")
                        append(heartRate)
                        append("</gpxtpx:hr>\n")
                    }
                    extensions.cadence?.let { cadence ->
                        append("            <gpxtpx:cad>")
                        append(cadence)
                        append("</gpxtpx:cad>\n")
                    }
                    extensions.speed?.let { speed ->
                        append("            <gpxtpx:speed>")
                        append(formatGpxNumber(speed))
                        append("</gpxtpx:speed>\n")
                    }
                    append("          </gpxtpx:TrackPointExtension>\n")
                    append("        </extensions>\n")
                }
                append("      </trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }
}

fun exportRideToGpx(
    contentResolver: ContentResolver,
    filePath: Uri,
    exportData: TripDetailsViewModel.ExportData,
) {
    contentResolver.openFileDescriptor(filePath, "w")?.use {
        FileOutputStream(it.fileDescriptor).use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writer.write(buildGpx(exportData))
            }
        }
    }
}
