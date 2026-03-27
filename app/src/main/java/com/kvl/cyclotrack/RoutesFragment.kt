package com.kvl.cyclotrack

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RoutesFragment : Fragment() {
    private val logTag = "RoutesFragment"
    private val viewModel: RoutesViewModel by viewModels()
    private val importRouteDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument(), ::handleImportSelection)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_routes, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = ""
        addMenuProvider()

        val emptyView: TextView = view.findViewById(R.id.routes_empty_view)
        val routeListView: RecyclerView = view.findViewById(R.id.routes_list)
        val viewManager = LinearLayoutManager(activity)

        viewModel.allRoutes.observe(viewLifecycleOwner) { routes ->
            val safeRoutes = routes ?: emptyArray()
            emptyView.visibility = if (safeRoutes.isEmpty()) View.VISIBLE else View.GONE
            routeListView.apply {
                setHasFixedSize(true)
                layoutManager = viewManager
                adapter = RoutesAdapter(
                    routes = safeRoutes,
                    viewModel = viewModel,
                    viewLifecycleOwner = viewLifecycleOwner,
                    context = requireContext()
                )
            }
        }
    }

    private fun addMenuProvider() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_routes, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                when (menuItem.itemId) {
                    R.id.action_import_route -> {
                        importRouteDocument.launch(arrayOf("*/*"))
                        true
                    }

                    else -> false
                }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun handleImportSelection(uri: Uri?) {
        if (uri == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not offer persistable permissions, which is fine for immediate import.
            }

            try {
                val routeId = viewModel.importRoute(uri)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.route_import_success),
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().navigate(RoutesFragmentDirections.actionViewRouteDetails(routeId))
            } catch (e: Exception) {
                Log.e(logTag, "Unable to import route from $uri", e)
                Toast.makeText(
                    requireContext(),
                    e.message ?: getString(R.string.route_import_error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
