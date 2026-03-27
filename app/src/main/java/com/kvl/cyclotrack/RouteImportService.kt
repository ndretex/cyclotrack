package com.kvl.cyclotrack

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.room.withTransaction
import com.garmin.fit.CourseMesg
import com.garmin.fit.FitDecoder
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.RecordMesg
import com.kvl.cyclotrack.util.getFileName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.inject.Inject
import kotlin.math.abs

class RouteImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TripsDatabase,
    private val routeRepository: RouteRepository,
    private val routePointRepository: RoutePointRepository,
) {
    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val fileName = getFileName(context.contentResolver, uri)
        val importedRoute = when (detectFormat(uri, fileName)) {
            ImportFormat.GPX -> parseGpx(uri, fileName)
            ImportFormat.FIT -> parseFit(uri, fileName)
        }

        val routePoints = importedRoute.points
        if (routePoints.isEmpty()) {
            throw IllegalArgumentException(
                context.getString(R.string.route_import_error_no_points)
            )
        }

        val routeSummaryPoints = routePoints.mapIndexed { index, point ->
            RoutePoint(
                routeId = 0L,
                sequence = index,
                latitude = point.latitude,
                longitude = point.longitude,
                elevation = point.elevation,
                timestamp = point.timestamp
            )
        }.toTypedArray()
        val distance = getRouteDistance(routeSummaryPoints)
        val ascentDescent = getRouteAscentDescent(routeSummaryPoints)

        database.withTransaction {
            val routeId = routeRepository.save(
                Route(
                    name = importedRoute.name,
                    description = importedRoute.description,
                    source = importedRoute.source,
                    distance = distance.takeIf { it > 0.0 },
                    ascent = ascentDescent?.first,
                    descent = ascentDescent?.second?.let(::abs),
                    hasTimestamps = importedRoute.hasTimestamps
                )
            )
            routePointRepository.save(
                routePoints.mapIndexed { index, point ->
                    RoutePoint(
                        routeId = routeId,
                        sequence = index,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        elevation = point.elevation,
                        timestamp = point.timestamp
                    )
                }.toTypedArray()
            )
            routeId
        }
    }

    private fun detectFormat(uri: Uri, fileName: String?): ImportFormat {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        when (extension) {
            "gpx" -> return ImportFormat.GPX
            "fit" -> return ImportFormat.FIT
        }

        val mimeType = context.contentResolver.getType(uri)?.lowercase()
        when {
            mimeType?.contains("gpx") == true || mimeType?.contains("xml") == true ->
                return ImportFormat.GPX

            mimeType?.contains("fit") == true ->
                return ImportFormat.FIT
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(16)
            val bytesRead = input.read(header)
            if (bytesRead >= 12) {
                val signature = String(header, 8, 4, StandardCharsets.US_ASCII)
                if (signature == ".FIT") return ImportFormat.FIT
            }
            if (bytesRead > 0) {
                val prefix = String(header, 0, bytesRead, StandardCharsets.UTF_8).trimStart()
                if (prefix.startsWith("<")) return ImportFormat.GPX
            }
        }

        throw IllegalArgumentException(
            context.getString(R.string.route_import_error_unsupported_format)
        )
    }

    private fun parseGpx(uri: Uri, fileName: String?): ImportedRoute {
        try {
            val parser = Xml.newPullParser()
            context.contentResolver.openInputStream(uri)?.use { input ->
                parser.setInput(input, null)
                return parseGpxDocument(parser, fileName)
            }
            throw IllegalArgumentException(
                context.getString(R.string.route_import_error_unreadable_file)
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException(
                context.getString(R.string.route_import_error_invalid_gpx),
                e
            )
        }
    }

    private fun parseGpxDocument(parser: XmlPullParser, fileName: String?): ImportedRoute {
        var metadataName: String? = null
        var metadataDescription: String? = null
        var trackName: String? = null
        var trackDescription: String? = null
        var routeName: String? = null
        var routeDescription: String? = null
        var inMetadata = false
        var inTrack = false
        var inRoute = false
        var currentPoint: ImportedRoutePointBuilder? = null
        val trackPoints = mutableListOf<ImportedRoutePoint>()
        val routePoints = mutableListOf<ImportedRoutePoint>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.localTagName()) {
                    "metadata" -> inMetadata = true
                    "trk" -> inTrack = true
                    "rte" -> inRoute = true
                    "trkpt", "rtept" -> {
                        currentPoint = ImportedRoutePointBuilder(
                            latitude = parser.getAttributeValue(null, "lat")?.toDoubleOrNull(),
                            longitude = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        )
                    }

                    "name" -> {
                        val value = parser.nextText().trim().takeIf { it.isNotBlank() }
                        when {
                            currentPoint != null -> Unit
                            inMetadata -> metadataName = metadataName ?: value
                            inTrack -> trackName = trackName ?: value
                            inRoute -> routeName = routeName ?: value
                        }
                    }

                    "desc" -> {
                        val value = parser.nextText().trim().takeIf { it.isNotBlank() }
                        when {
                            currentPoint != null -> Unit
                            inMetadata -> metadataDescription = metadataDescription ?: value
                            inTrack -> trackDescription = trackDescription ?: value
                            inRoute -> routeDescription = routeDescription ?: value
                        }
                    }

                    "ele" -> currentPoint?.let {
                        it.elevation = parser.nextText().trim().toDoubleOrNull()
                    }

                    "time" -> currentPoint?.let {
                        it.timestamp = parser.nextText().trim().toEpochMillisOrNull()
                    }
                }

                XmlPullParser.END_TAG -> when (parser.localTagName()) {
                    "metadata" -> inMetadata = false
                    "trk" -> inTrack = false
                    "rte" -> inRoute = false
                    "trkpt" -> {
                        currentPoint?.build()?.let(trackPoints::add)
                        currentPoint = null
                    }

                    "rtept" -> {
                        currentPoint?.build()?.let(routePoints::add)
                        currentPoint = null
                    }
                }
            }
            eventType = parser.next()
        }

        val selectedPoints = trackPoints.ifEmpty { routePoints }
        if (selectedPoints.isEmpty()) {
            throw IllegalArgumentException(
                context.getString(R.string.route_import_error_no_points)
            )
        }

        return ImportedRoute(
            name = metadataName
                ?: trackName
                ?: routeName
                ?: fileName.toDefaultRouteName(),
            description = metadataDescription ?: trackDescription ?: routeDescription,
            source = ImportFormat.GPX.displayName,
            hasTimestamps = selectedPoints.any { it.timestamp != null },
            points = selectedPoints
        )
    }

    private fun parseFit(uri: Uri, fileName: String?): ImportedRoute {
        val fitMessages = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FitDecoder().decode(input)
            } ?: throw IllegalArgumentException(
                context.getString(R.string.route_import_error_unreadable_file)
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException(
                context.getString(R.string.route_import_error_invalid_fit),
                e
            )
        }

        val fileType = fitMessages.fileIdMesgs.firstOrNull()?.type
        val points = fitMessages.recordMesgs.mapNotNull(::toImportedRoutePoint)
        if (points.isEmpty()) {
            throw IllegalArgumentException(
                context.getString(R.string.route_import_error_no_fit_geometry)
            )
        }

        val routeName = fitMessages.courseMesgs.firstNonBlankName()
            ?: fitMessages.fileIdMesgs.firstNonBlankProductName()
            ?: fileName.toDefaultRouteName()

        return ImportedRoute(
            name = routeName,
            description = null,
            source = when (fileType) {
                File.COURSE -> context.getString(R.string.route_import_source_fit_course)
                else -> ImportFormat.FIT.displayName
            },
            hasTimestamps = points.any { it.timestamp != null },
            points = points
        )
    }

    private fun toImportedRoutePoint(record: RecordMesg): ImportedRoutePoint? {
        val latitude = record.positionLat?.toDegrees() ?: return null
        val longitude = record.positionLong?.toDegrees() ?: return null
        return ImportedRoutePoint(
            latitude = latitude,
            longitude = longitude,
            elevation = (record.enhancedAltitude ?: record.altitude)?.toDouble(),
            timestamp = record.timestamp?.date?.time
        )
    }
}

private enum class ImportFormat(val displayName: String) {
    GPX("GPX"),
    FIT("FIT"),
}

private data class ImportedRoute(
    val name: String,
    val description: String?,
    val source: String,
    val hasTimestamps: Boolean,
    val points: List<ImportedRoutePoint>,
)

private data class ImportedRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val timestamp: Long? = null,
)

private data class ImportedRoutePointBuilder(
    val latitude: Double?,
    val longitude: Double?,
    var elevation: Double? = null,
    var timestamp: Long? = null,
) {
    fun build(): ImportedRoutePoint? =
        if (latitude == null || longitude == null) {
            null
        } else {
            ImportedRoutePoint(
                latitude = latitude,
                longitude = longitude,
                elevation = elevation,
                timestamp = timestamp
            )
        }
}

private fun XmlPullParser.localTagName(): String = name?.substringAfter(':') ?: ""

private fun String?.toDefaultRouteName(): String = this
    ?.substringBeforeLast('.')
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: "Imported route"

private fun String.toEpochMillisOrNull(): Long? = runCatching {
    Instant.parse(this).toEpochMilli()
}.getOrNull()

private fun Int.toDegrees(): Double = this * 180.0 / 2147483648.0

private fun List<CourseMesg>.firstNonBlankName(): String? =
    firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }

private fun List<FileIdMesg>.firstNonBlankProductName(): String? =
    firstNotNullOfOrNull { it.productName?.takeIf(String::isNotBlank) }
