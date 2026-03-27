package com.kvl.cyclotrack

import android.content.Context
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.model.RoundCap
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.kvl.cyclotrack.util.SystemUtils
import kotlinx.coroutines.launch

class RoutesAdapter(
    private val routes: Array<Route>,
    private val viewModel: RoutesViewModel,
    private val viewLifecycleOwner: LifecycleOwner,
    private val context: Context,
) : RecyclerView.Adapter<RoutesAdapter.RouteViewHolder>() {
    private val logTag = "ROUTES_ADAPTER"

    class RouteViewHolder(val routeSummaryView: TripSummaryCard) :
        RecyclerView.ViewHolder(routeSummaryView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        return RouteViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_trip_summary_card_wrapper, parent, false) as TripSummaryCard
        )
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val route = routes[position]
        val routeId = route.id ?: return

        holder.routeSummaryView.tripId = routeId
        holder.routeSummaryView.title = route.name
        holder.routeSummaryView.date = DateUtils.getRelativeTimeSpanString(
            route.createdAt,
            SystemUtils.currentTimeMillis(),
            DateUtils.DAY_IN_MILLIS
        ).toString()
        holder.routeSummaryView.startTime = getRouteSourceText(route)
        holder.routeSummaryView.duration = context.getString(R.string.route_summary_loading)
        holder.routeSummaryView.onResumeMap()
        holder.routeSummaryView.clearMap()

        holder.routeSummaryView.setOnClickListener { view ->
            try {
                view.findNavController()
                    .navigate(RoutesFragmentDirections.actionViewRouteDetails(routeId))
            } catch (e: IllegalArgumentException) {
                Log.e(logTag, "Cannot find route $routeId", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            } catch (e: IllegalStateException) {
                Log.e(logTag, "Failed to navigate", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val routePoints = viewModel.getRoutePoints(routeId)
            if (holder.routeSummaryView.tripId != routeId) return@launch

            holder.routeSummaryView.duration = getRouteDistanceText(route, routePoints)
            val mapData = plotPath(routePoints)
            if (holder.routeSummaryView.tripId != routeId || mapData.bounds == null) return@launch

            mapData.paths.forEach { path ->
                path.startCap(RoundCap())
                path.endCap(RoundCap())
                path.width(5f)
                path.color(
                    ResourcesCompat.getColor(
                        context.resources,
                        R.color.accentColor,
                        null
                    )
                )
                holder.routeSummaryView.drawPath(path, mapData.bounds)
            }
        }
    }

    override fun getItemCount() = routes.size

    private fun getRouteDistanceText(route: Route, routePoints: Array<RoutePoint>): String {
        val distance = route.distance ?: getRouteDistance(routePoints)
        return if (distance > 0.0) {
            context.getString(
                R.string.route_summary_distance_format,
                getUserDistance(context, distance),
                getUserDistanceUnitShort(context)
            )
        } else {
            context.getString(R.string.route_summary_points_format, routePoints.size)
        }
    }

    private fun getRouteSourceText(route: Route): String = route.source?.let {
        context.getString(R.string.route_source_format, it)
    } ?: when (route.hasTimestamps) {
        true -> context.getString(R.string.route_type_timed)
        false -> context.getString(R.string.route_type_saved)
    }
}
