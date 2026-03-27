package com.kvl.cyclotrack

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RoutePointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(routePoint: RoutePoint): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(routePoints: Array<RoutePoint>)

    @Query("SELECT * FROM RoutePoint WHERE routeId = :routeId ORDER BY sequence ASC")
    fun subscribe(routeId: Long): LiveData<Array<RoutePoint>>

    @Query("SELECT * FROM RoutePoint WHERE routeId = :routeId ORDER BY sequence ASC")
    suspend fun load(routeId: Long): Array<RoutePoint>

    @Query("DELETE FROM RoutePoint WHERE routeId = :routeId")
    suspend fun removeRoutePoints(routeId: Long)
}
