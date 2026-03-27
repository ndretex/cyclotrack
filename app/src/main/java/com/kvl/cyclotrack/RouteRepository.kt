package com.kvl.cyclotrack

import javax.inject.Inject

class RouteRepository @Inject constructor(private val routeDao: RouteDao) {
    fun observeAll() = routeDao.subscribeAll()

    fun observe(routeId: Long) = routeDao.subscribe(routeId)

    suspend fun get(routeId: Long) = routeDao.load(routeId)

    suspend fun getAll() = routeDao.loadAll()

    suspend fun save(route: Route) = routeDao.save(route)

    suspend fun update(vararg routes: Route) = routeDao.update(*routes)

    suspend fun remove(routeId: Long) = routeDao.removeRoute(RouteId(routeId))
}
