package com.kvl.cyclotrack

import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.MapStyleOptions
import com.kvl.cyclotrack.util.SystemUtils
import com.kvl.cyclotrack.widgets.AxisLabels
import com.kvl.cyclotrack.widgets.BordersEnum
import com.kvl.cyclotrack.widgets.HeadingView
import com.kvl.cyclotrack.widgets.LineGraph
import com.kvl.cyclotrack.widgets.LineGraphAreaDataset
import com.kvl.cyclotrack.widgets.LineGraphDataset
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

@AndroidEntryPoint
class RouteDetailsFragment : Fragment() {
    private val viewModel: RouteDetailsViewModel by viewModels()
    private val args: RouteDetailsFragmentArgs by navArgs()

    private lateinit var mapView: MapView
    private lateinit var map: GoogleMap
    private lateinit var titleView: TextView
    private lateinit var importedAtView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var sourceView: TextView
    private lateinit var distanceHeading: HeadingView
    private lateinit var pointsHeading: HeadingView
    private lateinit var typeHeading: HeadingView
    private lateinit var ascentHeading: HeadingView
    private lateinit var descentHeading: HeadingView
    private lateinit var elevationRangeHeading: HeadingView
    private lateinit var steepestClimbHeading: HeadingView
    private lateinit var steepestDescentHeading: HeadingView
    private lateinit var elevationChartHeading: TextView
    private lateinit var elevationChartView: ImageView
    private lateinit var gradeChartHeading: TextView
    private lateinit var gradeChartView: ImageView
    private var pendingMapPath: MapPath? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewModel.routeId = args.routeId
        return inflater.inflate(R.layout.fragment_route_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = ""

        mapView = view.findViewById(R.id.route_details_map_view)
        titleView = view.findViewById(R.id.route_details_title)
        importedAtView = view.findViewById(R.id.route_details_imported_at)
        descriptionView = view.findViewById(R.id.route_details_description)
        sourceView = view.findViewById(R.id.route_details_source_value)
        distanceHeading = view.findViewById(R.id.route_details_distance_heading)
        pointsHeading = view.findViewById(R.id.route_details_points_heading)
        typeHeading = view.findViewById(R.id.route_details_type_heading)
        ascentHeading = view.findViewById(R.id.route_details_ascent_heading)
        descentHeading = view.findViewById(R.id.route_details_descent_heading)
        elevationRangeHeading = view.findViewById(R.id.route_details_elevation_range_heading)
        steepestClimbHeading = view.findViewById(R.id.route_details_steepest_climb_heading)
        steepestDescentHeading = view.findViewById(R.id.route_details_steepest_descent_heading)
        elevationChartHeading = view.findViewById(R.id.route_details_elevation_chart_heading)
        elevationChartView = view.findViewById(R.id.route_details_elevation_chart)
        gradeChartHeading = view.findViewById(R.id.route_details_grade_chart_heading)
        gradeChartView = view.findViewById(R.id.route_details_grade_chart)
        view.findViewById<ImageButton>(R.id.route_details_edit_button).setOnClickListener {
            showRenameDialog()
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { googleMap ->
            map = googleMap
            map.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(
                    requireContext(),
                    R.raw.summary_map_style
                )
            )
            map.uiSettings.setAllGesturesEnabled(false)
            map.uiSettings.isMapToolbarEnabled = false
            pendingMapPath?.let(::renderMapPath)
        }

        val strokeStyle = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 5F
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ResourcesCompat.getColor(resources, R.color.accentColor, null)
        }
        val fillStyle = Paint(strokeStyle).apply {
            style = Paint.Style.FILL_AND_STROKE
            alpha = 50
        }
        val secondaryStyle = Paint(strokeStyle).apply {
            color = ResourcesCompat.getColor(resources, R.color.secondaryGraphColor, null)
        }

        viewModel.routeOverview.observe(viewLifecycleOwner) { route ->
            if (route == null) return@observe

            titleView.text = route.name
            importedAtView.text = DateUtils.getRelativeTimeSpanString(
                route.createdAt,
                SystemUtils.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS
            )
            descriptionView.visibility =
                if (route.description.isNullOrBlank()) View.GONE else View.VISIBLE
            descriptionView.text = route.description
            sourceView.text = route.source?.let {
                getString(R.string.route_source_format, it)
            } ?: getRouteTypeText(route)
        }

        zipLiveData(viewModel.routeOverview, viewModel.routePoints).observe(viewLifecycleOwner) { pair ->
            val route = pair.first ?: return@observe
            val routePoints = pair.second
            val stats = getRouteDerivedStats(routePoints)

            distanceHeading.value = if (stats.distanceMeters > 0.0) {
                getString(
                    R.string.route_detail_distance_format,
                    getUserDistance(requireContext(), stats.distanceMeters),
                    getUserDistanceUnitShort(requireContext())
                )
            } else {
                getString(R.string.route_detail_unavailable)
            }
            pointsHeading.value =
                getString(R.string.route_detail_points_format, stats.pointCount)
            typeHeading.value = getRouteTypeText(route)
            ascentHeading.value = formatAltitude(stats.ascentMeters)
            descentHeading.value = formatAltitude(stats.descentMeters)
            elevationRangeHeading.value = if (stats.minElevationMeters != null &&
                stats.maxElevationMeters != null
            ) {
                getString(
                    R.string.route_detail_elevation_range_format,
                    getUserAltitude(requireContext(), stats.minElevationMeters),
                    getUserAltitude(requireContext(), stats.maxElevationMeters),
                    getUserAltitudeUnitShort(requireContext())
                )
            } else {
                getString(R.string.route_detail_unavailable)
            }
            steepestClimbHeading.value = formatGradeAndAngle(
                stats.steepestClimbPercent,
                stats.steepestClimbAngleDegrees
            )
            steepestDescentHeading.value = formatGradeAndAngle(
                stats.steepestDescentPercent,
                stats.steepestDescentAngleDegrees
            )

            renderElevationChart(stats, strokeStyle, fillStyle)
            renderGradeChart(stats, secondaryStyle)

            viewLifecycleOwner.lifecycleScope.launch {
                pendingMapPath = plotPath(routePoints)
                if (this@RouteDetailsFragment::map.isInitialized) {
                    pendingMapPath?.let(::renderMapPath)
                }
            }
        }
    }

    private fun renderElevationChart(
        stats: RouteDerivedStats,
        strokeStyle: Paint,
        fillStyle: Paint,
    ) {
        val data = stats.elevationProfile.map {
            Pair(
                it.distanceMeters.toFloat(),
                getUserAltitude(requireContext(), it.elevationMeters).toFloat()
            )
        }
        if (data.size < 2) {
            elevationChartHeading.visibility = View.GONE
            elevationChartView.visibility = View.GONE
            return
        }

        elevationChartHeading.visibility = View.VISIBLE
        elevationChartView.visibility = View.VISIBLE

        val xMin = data.first().first
        val xMax = data.last().first
        val yMin = data.minBy { it.second }.second
        val yMax = data.maxBy { it.second }.second
        val yRangePadding = max((yMax - yMin) * 0.2f, 1f)
        val yViewMin = max(yMin - yRangePadding, 0f)
        val yViewMax = yMax + yRangePadding

        elevationChartView.setImageDrawable(
            LineGraph(
                areas = listOf(
                    LineGraphAreaDataset(
                        points1 = data,
                        points2 = listOf(Pair(xMin, yViewMin), Pair(xMax, yViewMin)),
                        xRange = Pair(xMin, xMax),
                        yRange = Pair(yViewMin, yViewMax),
                        xAxisWidth = xMax - xMin,
                        yAxisHeight = yViewMax - yViewMin,
                        paint = fillStyle
                    )
                ),
                datasets = listOf(
                    LineGraphDataset(
                        points = data,
                        xRange = Pair(xMin, xMax),
                        yRange = Pair(yViewMin, yViewMax),
                        xAxisWidth = xMax - xMin,
                        yAxisHeight = yViewMax - yViewMin,
                        paint = strokeStyle
                    )
                ),
                borders = BordersEnum.BOTTOM.value,
                xLabels = getAxisLabelsDistanceX(xMin, xMax),
                yLabels = AxisLabels(
                    labels = listOf(
                        Pair(yMin, "${yMin.roundToInt()} ${getUserAltitudeUnitShort(requireContext())}"),
                        Pair(yMax, "${yMax.roundToInt()} ${getUserAltitudeUnitShort(requireContext())}")
                    ),
                    range = Pair(yViewMin, yViewMax),
                    lines = true,
                    background = getChartLabelBackground()
                )
            )
        )
    }

    private fun renderGradeChart(stats: RouteDerivedStats, strokeStyle: Paint) {
        val data = stats.gradeProfile.map {
            Pair(it.distanceMeters.toFloat(), it.gradePercent.toFloat())
        }
        if (data.isEmpty()) {
            gradeChartHeading.visibility = View.GONE
            gradeChartView.visibility = View.GONE
            return
        }

        gradeChartHeading.visibility = View.VISIBLE
        gradeChartView.visibility = View.VISIBLE

        val xMin = data.first().first
        val xMax = data.last().first
        val yMin = data.minBy { it.second }.second
        val yMax = data.maxBy { it.second }.second
        val maxAbs = maxOf(kotlin.math.abs(yMin), kotlin.math.abs(yMax), 1f)
        val yViewMin = -maxAbs * 1.2f
        val yViewMax = maxAbs * 1.2f

        gradeChartView.setImageDrawable(
            LineGraph(
                datasets = listOf(
                    LineGraphDataset(
                        points = data,
                        xRange = Pair(xMin, xMax),
                        yRange = Pair(yViewMin, yViewMax),
                        xAxisWidth = xMax - xMin,
                        yAxisHeight = yViewMax - yViewMin,
                        paint = strokeStyle
                    )
                ),
                borders = BordersEnum.BOTTOM.value,
                xLabels = getAxisLabelsDistanceX(xMin, xMax),
                yLabels = AxisLabels(
                    labels = listOf(
                        Pair(yMin, "${yMin.roundToInt()}%"),
                        Pair(0f, "0%"),
                        Pair(yMax, "${yMax.roundToInt()}%")
                    ),
                    range = Pair(yViewMin, yViewMax),
                    lines = true,
                    background = getChartLabelBackground()
                )
            )
        )
    }

    private fun renderMapPath(mapPath: MapPath) {
        if (!this::map.isInitialized) return

        map.clear()
        mapPath.paths.forEach { path ->
            path.width(8f)
            path.color(ResourcesCompat.getColor(resources, R.color.accentColor, null))
            map.addPolyline(path)
        }

        mapPath.bounds?.let { bounds ->
            mapView.post {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
            }
        }
    }

    private fun formatAltitude(value: Double?): String = value?.let {
        getString(
            R.string.route_detail_altitude_format,
            getUserAltitude(requireContext(), it),
            getUserAltitudeUnitShort(requireContext())
        )
    } ?: getString(R.string.route_detail_unavailable)

    private fun formatGradeAndAngle(gradePercent: Double?, angleDegrees: Double?): String =
        if (gradePercent != null && angleDegrees != null) {
            getString(
                R.string.route_detail_grade_and_angle_format,
                gradePercent,
                angleDegrees
            )
        } else {
            getString(R.string.route_detail_unavailable)
        }

    private fun getRouteTypeText(route: Route): String = when (route.hasTimestamps) {
        true -> getString(R.string.route_type_timed)
        false -> getString(R.string.route_type_saved)
    }

    private fun getAxisLabelsDistanceX(xMin: Float, xMax: Float) = AxisLabels(
        labels = listOf(0.25f, 0.5f, 0.75f).map { ratio ->
            (xMin + (xMax - xMin) * ratio).roundToInt().toFloat().let {
                Pair(
                    it,
                    "${getUserDistance(requireContext(), it.toDouble()).roundToInt()} ${
                        getUserDistanceUnitShort(requireContext())
                    }"
                )
            }
        }
    )

    private fun getChartLabelBackground(): Int =
        (view?.background as? ColorDrawable)?.color ?: ResourcesCompat.getColor(
            resources,
            R.color.backgroundColor,
            null
        )

    private fun showRenameDialog() {
        val route = viewModel.routeOverview.value ?: return
        val editText = EditText(requireContext()).apply {
            setText(route.name)
            hint = getString(R.string.route_detail_rename_hint)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.route_detail_rename_title)
            .setView(editText)
            .setPositiveButton(R.string.route_detail_rename_confirm) { _, _ ->
                viewModel.renameRoute(editText.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.route_detail_rename_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (this::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (this::mapView.isInitialized) mapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (this::mapView.isInitialized) mapView.onLowMemory()
    }

    override fun onDestroyView() {
        if (this::mapView.isInitialized) mapView.onDestroy()
        super.onDestroyView()
    }
}
