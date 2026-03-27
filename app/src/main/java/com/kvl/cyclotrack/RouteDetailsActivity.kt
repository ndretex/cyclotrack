package com.kvl.cyclotrack

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.findNavController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RouteDetailsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_route_details)
        findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBar_routeDetails)
            .let { appBar ->
                val initialTopPadding = appBar.paddingTop
                ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
                    val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                    view.updatePadding(top = initialTopPadding + topInset)
                    insets
                }
                ViewCompat.requestApplyInsets(appBar)
            }
        findNavController(R.id.nav_host_fragment_routeDetails).setGraph(
            R.navigation.route_details_nav_graph,
            intent.extras
        )
        setSupportActionBar(findViewById(R.id.toolbar_routeDetails))
    }
}
