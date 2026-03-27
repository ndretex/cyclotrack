package com.kvl.cyclotrack

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kvl.cyclotrack.util.SystemUtils

@Entity
@Keep
data class Route(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val createdAt: Long = SystemUtils.currentTimeMillis(),
    val distance: Double? = null,
    val ascent: Double? = null,
    val descent: Double? = null,
    val hasTimestamps: Boolean = false,
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,
)

data class RouteId(
    val id: Long,
)
