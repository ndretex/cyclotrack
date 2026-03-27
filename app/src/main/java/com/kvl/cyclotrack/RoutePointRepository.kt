package com.kvl.cyclotrack

import javax.inject.Inject

class RoutePointRepository @Inject constructor(private val routePointDao: RoutePointDao) {
    fun observe(routeId: Long) = routePointDao.subscribe(routeId)

    suspend fun get(routeId: Long) = routePointDao.load(routeId)

    suspend fun save(routePoint: RoutePoint) = routePointDao.save(routePoint)

    suspend fun save(routePoints: Array<RoutePoint>) = routePointDao.save(routePoints)

    suspend fun removeRoutePoints(routeId: Long) = routePointDao.removeRoutePoints(routeId)
}
