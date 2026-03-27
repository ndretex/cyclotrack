package com.kvl.cyclotrack

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface RouteDao {
    @Insert
    suspend fun save(route: Route): Long

    @Update
    suspend fun update(vararg routes: Route)

    @Query("SELECT * FROM Route ORDER BY createdAt DESC")
    fun subscribeAll(): LiveData<Array<Route>>

    @Query("SELECT * FROM Route ORDER BY createdAt DESC")
    suspend fun loadAll(): Array<Route>

    @Query("SELECT * FROM Route WHERE id = :routeId")
    fun subscribe(routeId: Long): LiveData<Route?>

    @Query("SELECT * FROM Route WHERE id = :routeId")
    suspend fun load(routeId: Long): Route?

    @Delete(entity = Route::class)
    suspend fun removeRoute(id: RouteId)
}
