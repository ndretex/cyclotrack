package com.kvl.cyclotrack

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorkerFactory
import androidx.preference.PreferenceManager
import androidx.work.Configuration
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import javax.inject.Inject

@HiltAndroidApp
class CyclotrackApp : Application(), Configuration.Provider {
    companion object {
        lateinit var instance: CyclotrackApp private set
    }

    private fun createNotificationChannel() {
        val exportCompleteChannel =
            NotificationChannel(
                getString(R.string.notification_export_trip_id),
                getString(R.string.notification_export_trip_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_description_export_trip)
            }
        val exportInProgressChannel =
            NotificationChannel(
                getString(R.string.notification_export_trip_in_progress_id),
                getString(R.string.notification_export_trip_in_progress_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description_export_trip)
            }
        // Register the channel with the system
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(exportCompleteChannel)
        notificationManager.createNotificationChannel(exportInProgressChannel)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        MapLibre.getInstance(this)
        configureMapLibreNetworking()
        createNotificationChannel()
        /*PreferenceManager.getDefaultSharedPreferences(this).edit {
            putBoolean(getString(R.string.preference_key_analytics_opt_in_presented),
                false)
            commit()
        }*/
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(
            PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(getString(R.string.preferences_key_enable_analytics), false)
        )
    }

    private fun configureMapLibreNetworking() {
        val apiKey = BuildConfig.VALHALLA_API_KEY.trim()
        val styleHost = Uri.parse(BuildConfig.MAPLIBRE_STYLE_URL.trim()).host?.trim().orEmpty()
        if (apiKey.isBlank() || styleHost.isBlank()) return

        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val updatedRequest = if (request.url.host.equals(styleHost, ignoreCase = true)) {
                        Request.Builder()
                            .url(request.url)
                            .headers(request.headers)
                            .method(request.method, request.body)
                            .addHeader("x-api-key", apiKey)
                            .build()
                    } else {
                        request
                    }
                    chain.proceed(updatedRequest)
                }
                .build()
        )
    }

    @Inject
    lateinit var workFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workFactory).build()
}
