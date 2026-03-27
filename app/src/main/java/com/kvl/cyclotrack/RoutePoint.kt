package com.kvl.cyclotrack

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    foreignKeys = [ForeignKey(
        entity = Route::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("routeId"),
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["routeId"]),
        Index(value = ["routeId", "sequence"], unique = true)
    ]
)
@Keep
data class RoutePoint(
    val routeId: Long,
    val sequence: Int,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val timestamp: Long? = null,
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,
)
