// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.buge.appmanager.databinding.ActivityRestoreAppsBinding
import com.buge.appmanager.shizuku.ShizukuManager
import com.buge.appmanager.util.LogManager
import com.buge.appmanager.util.SnackbarHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UninstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val icon: Drawable? = null
)

class RestoreAppsActivity : BaseActivity() {

    private lateinit var binding: ActivityRestoreAppsBinding
    private lateinit var adapter: RestoreAppsAdapter
    private var uninstalledApps: List<UninstalledAppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRestoreAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadUninstalledApps()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.title_restore_apps)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        adapter = RestoreAppsAdapter { app ->
            showRestoreConfirmDialog(app)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadUninstalledApps() {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.emptyState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val apps = getUninstalledApps()
                uninstalledApps = apps
                adapter.submitList(apps)
                if (apps.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                LogManager.error(this@RestoreAppsActivity, "Failed to load uninstalled apps", e.message)
                SnackbarHelper.showSnackbar(binding.root, "Failed to load: ${e.message}")
            } finally {
                binding.loadingOverlay.visibility = View.GONE
            }
        }
    }

    private suspend fun getUninstalledApps(): List<UninstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = packageManager
        val apps = mutableListOf<UninstalledAppInfo>()

        val result = ShizukuManager.executeCommand("pm list packages -u")
        if (!result.success) return@withContext emptyList()

        val allPkgs = result.output.lines()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }

        val user0Result = ShizukuManager.executeCommand("pm list packages --user 0")
        val user0Pkgs = if (user0Result.success) {
            user0Result.output.lines()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } else {
            emptySet()
        }

        val uninstalledPkgs = allPkgs.filter { pkg ->
            pkg !in user0Pkgs
        }

        for (pkg in uninstalledPkgs) {
            val appName = when (pkg) {
                "com.android.browser" -> "Mi Browser"
                else -> {
                    try {
                        val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: Exception) {
                        pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                    }
                }
            }
            
            var appIcon: Drawable? = null
            var isSystem = false
            try {
                val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                appIcon = pm.getApplicationIcon(appInfo)
                isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Exception) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    appIcon = pm.getApplicationIcon(appInfo)
                    isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                } catch (_: Exception) {}
            }

            apps.add(
                UninstalledAppInfo(
                    packageName = pkg,
                    appName = appName,
                    isSystemApp = isSystem,
                    icon = appIcon
                )
            )
        }

        apps.sortedBy { it.appName.lowercase() }
    }

    private fun showRestoreConfirmDialog(app: UninstalledAppInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore)
            .setMessage(getString(R.string.restore_confirm, app.appName))
            .setPositiveButton(R.string.restore) { _, _ ->
                restoreApp(app)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restoreApp(app: UninstalledAppInfo) {
        if (!checkShizuku()) return

        binding.loadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val res = ShizukuManager.executeCommand("pm install-existing --user 0 ${app.packageName}")
                if (res.success) {
                    SnackbarHelper.showSnackbar(binding.root, getString(R.string.restore_success))
                    LogManager.success(this@RestoreAppsActivity, "App restored", "Package: ${app.packageName}")
                    loadUninstalledApps()
                } else {
                    val err = res.error.ifEmpty { "Unknown error" }
                    SnackbarHelper.showSnackbar(binding.root, "${getString(R.string.restore_failed)}: $err")
                }
            } catch (e: Exception) {
                SnackbarHelper.showSnackbar(binding.root, "${getString(R.string.restore_failed)}: ${e.message}")
            } finally {
                binding.loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun checkShizuku(): Boolean {
        if (!ShizukuManager.isAuthorized()) {
            SnackbarHelper.showSnackbar(
                binding.root,
                getString(R.string.error_no_privilege),
                getString(R.string.request_auth),
                { ShizukuManager.requestAuthorization() }
            )
            return false
        }
        return true
    }

    inner class RestoreAppsAdapter(
        private val onRestoreClick: (UninstalledAppInfo) -> Unit
    ) : RecyclerView.Adapter<RestoreAppsAdapter.ViewHolder>() {

        private var items: List<UninstalledAppInfo> = emptyList()

        fun submitList(newItems: List<UninstalledAppInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_restore_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])

            val container = holder.itemView.findViewById<FrameLayout>(R.id.item_container)
            val size = items.size

            val background = when {
                size == 1 -> R.drawable.bg_setting_item_single
                position == 0 -> R.drawable.bg_setting_item_top
                position == size - 1 -> R.drawable.bg_setting_item_bottom
                else -> R.drawable.bg_setting_item_middle
            }
            container.setBackgroundResource(background)
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
            private val appName: TextView = itemView.findViewById(R.id.app_name)
            private val packageName: TextView = itemView.findViewById(R.id.package_name)
            private val systemAppBadge: View = itemView.findViewById(R.id.system_app_badge)
            private val btnRestore: MaterialButton = itemView.findViewById(R.id.btn_restore)

            fun bind(app: UninstalledAppInfo) {
                if (app.icon != null) {
                    appIcon.setImageDrawable(app.icon)
                } else {
                    appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                appName.text = app.appName
                packageName.text = app.packageName

                if (app.isSystemApp) {
                    systemAppBadge.visibility = View.VISIBLE
                } else {
                    systemAppBadge.visibility = View.GONE
                }

                btnRestore.setOnClickListener {
                    onRestoreClick(app)
                }
            }
        }
    }
}
