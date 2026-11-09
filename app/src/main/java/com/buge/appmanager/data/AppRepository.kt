// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager.data

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.buge.appmanager.model.AppFilter
import com.buge.appmanager.model.AppInfo
import com.buge.appmanager.model.AppSortOrder
import com.buge.appmanager.model.PermissionInfo
import com.buge.appmanager.shizuku.ShizukuManager
import com.buge.appmanager.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    companion object {
        private const val TAG = "AppRepository"

        private var sizeCache: MutableMap<String, Long> = mutableMapOf()
        private var cacheTimestamp: Long = 0
        private val CACHE_TTL_MS = 5000L

        val PERMISSION_MICROPHONE = listOf("android.permission.RECORD_AUDIO")
        val PERMISSION_CAMERA = listOf("android.permission.CAMERA")
        val PERMISSION_LOCATION = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        val PERMISSION_BACKGROUND_LOCATION = listOf("android.permission.ACCESS_BACKGROUND_LOCATION")
        val PERMISSION_CONTACTS = listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "android.permission.GET_ACCOUNTS")
        val PERMISSION_STORAGE = listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE")
        val PERMISSION_PHONE = listOf("android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE", "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.ADD_VOICEMAIL", "android.permission.USE_SIP", "android.permission.PROCESS_OUTGOING_CALLS")
        val PERMISSION_SMS = listOf("android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS", "android.permission.RECEIVE_WAP_PUSH", "android.permission.RECEIVE_MMS")
        val PERMISSION_CALENDAR = listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")
        val PERMISSION_SENSORS = listOf("android.permission.BODY_SENSORS", "android.permission.BODY_SENSORS_BACKGROUND")
        val PERMISSION_ACTIVITY = listOf("android.permission.ACTIVITY_RECOGNITION")
        val PERMISSION_NEARBY = listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE", "android.permission.UWB_RANGING")
        val PERMISSION_NOTIFICATIONS = listOf("android.permission.POST_NOTIFICATIONS")
        val PERMISSION_MEDIA_IMAGES = listOf("android.permission.READ_MEDIA_IMAGES")
        val PERMISSION_MEDIA_VIDEO = listOf("android.permission.READ_MEDIA_VIDEO")
        val PERMISSION_MEDIA_AUDIO = listOf("android.permission.READ_MEDIA_AUDIO")
        val PERMISSION_OVERLAY = listOf("android.permission.SYSTEM_ALERT_WINDOW")
        val PERMISSION_INSTALL_UNKNOWN_APPS = listOf("android.permission.REQUEST_INSTALL_PACKAGES")
        val PERMISSION_MANAGE_STORAGE = listOf("android.permission.MANAGE_EXTERNAL_STORAGE")
        val PERMISSION_WRITE_SETTINGS = listOf("android.permission.WRITE_SETTINGS")
        
        val ALL_DANGEROUS_PERMISSIONS = (
            PERMISSION_MICROPHONE + PERMISSION_CAMERA + PERMISSION_LOCATION +
            PERMISSION_BACKGROUND_LOCATION + PERMISSION_CONTACTS + PERMISSION_STORAGE +
            PERMISSION_PHONE + PERMISSION_SMS + PERMISSION_CALENDAR + PERMISSION_SENSORS +
            PERMISSION_ACTIVITY + PERMISSION_NEARBY + PERMISSION_NOTIFICATIONS +
            PERMISSION_MEDIA_IMAGES + PERMISSION_MEDIA_VIDEO + PERMISSION_MEDIA_AUDIO +
            PERMISSION_OVERLAY + PERMISSION_INSTALL_UNKNOWN_APPS + PERMISSION_MANAGE_STORAGE +
            PERMISSION_WRITE_SETTINGS
        ).toSet()

        val APPOP_PERMISSIONS = setOf("android.permission.SYSTEM_ALERT_WINDOW", "android.permission.REQUEST_INSTALL_PACKAGES")
        val APPOP_ONLY_PERMISSIONS = setOf("android.permission.MANAGE_EXTERNAL_STORAGE")
        val DHIZUKU_UNSUPPORTED_PERMISSIONS = setOf("android.permission.SYSTEM_ALERT_WINDOW", "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.WRITE_SETTINGS", "android.permission.MANAGE_EXTERNAL_STORAGE")
    }

    private fun getSpecialPermissionStatus(packageName: String, permission: String): Boolean? {
        return try {
            when (permission) {
                "android.permission.SYSTEM_ALERT_WINDOW" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val appOps = context.getSystemService(AppOpsManager::class.java)
                        val uid = pm.getApplicationInfo(packageName, 0).uid
                        appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName) == AppOpsManager.MODE_ALLOWED
                    } else true
                }
                "android.permission.REQUEST_INSTALL_PACKAGES" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val appOps = context.getSystemService(AppOpsManager::class.java)
                        val uid = pm.getApplicationInfo(packageName, 0).uid
                        appOps.checkOpNoThrow("android:request_install_packages", uid, packageName) == AppOpsManager.MODE_ALLOWED
                    } else true
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    suspend fun getManageExternalStorageStatus(packageName: String): Boolean? {
        return try { ShizukuManager.getManageExternalStorageStatus(packageName) } catch (e: Exception) { null }
    }

    private suspend fun getAppTotalSize(packageName: String): Long = withContext(Dispatchers.IO) {
        try {
            withTimeout(3000L) {
                var totalSize = 0L
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val apkFile = File(appInfo.sourceDir)
                    if (apkFile.exists()) totalSize += apkFile.length()
                } catch (e: Exception) {}

                try {
                    val result = ShizukuManager.executeCommand("du -s /data/data/$packageName 2>/dev/null | cut -f1")
                    if (result.success && result.output.isNotEmpty()) {
                        val sizeKB = result.output.trim().toLongOrNull()
                        if (sizeKB != null && sizeKB > 0) totalSize += sizeKB * 1024
                    }
                } catch (e: Exception) {}
                totalSize
            }
        } catch (e: Exception) { 0L }
    }

    private suspend fun getAppSizesBatch(apps: List<AppInfo>): Map<String, Long> = withContext(Dispatchers.IO) {
        if (apps.isEmpty()) return@withContext emptyMap()
        val deferred = apps.map { app -> async { app.packageName to getAppTotalSize(app.packageName) } }
        try { deferred.awaitAll().toMap() } catch (e: Exception) { emptyMap() }
    }

    fun clearSizeCache() {
        sizeCache.clear()
        cacheTimestamp = 0
    }

    suspend fun getInstalledApps(
        filter: AppFilter = AppFilter.ALL,
        sortOrder: AppSortOrder = AppSortOrder.NAME,
        searchQuery: String = "",
        showSystemApps: Boolean = false,
        showDisabledApps: Boolean = true
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        try {
            val apps = mutableMapOf<String, AppInfo>()

            // 1. Standard API packages
            try {
                val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(flags)
                }

                for (pkg in packages) {
                    val appInfo = pkg.applicationInfo ?: continue
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    val isEnabled = appInfo.enabled

                    val appName = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) { pkg.packageName }
                    val versionName = pkg.versionName ?: "Unknown"
                    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong()
                    val icon = try { pm.getApplicationIcon(pkg.packageName) } catch (e: Exception) { null }

                    apps[pkg.packageName] = AppInfo(
                        packageName = pkg.packageName,
                        appName = if (appName.isBlank()) pkg.packageName else appName,
                        versionName = versionName,
                        versionCode = versionCode,
                        icon = icon,
                        isSystemApp = isSystem,
                        isEnabled = isEnabled,
                        installTime = pkg.firstInstallTime,
                        updateTime = pkg.lastUpdateTime,
                        targetSdkVersion = appInfo.targetSdkVersion,
                        minSdkVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0,
                        apkPath = appInfo.sourceDir ?: ""
                    )
                }
            } catch (e: Exception) {}

            // 2. Fallback via Shizuku command to catch hidden/protected system apps like mibrain.speech
            try {
                val cmdResult = ShizukuManager.executeCommand("pm list packages -u -f")
                if (cmdResult.success) {
                    for (line in cmdResult.output.lines()) {
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("package:")) continue
                        // Format: package:/system/app/MiBrain/MiBrain.apk=com.xiaomi.mibrain.speech
                        val eqIndex = trimmed.lastIndexOf('=')
                        if (eqIndex == -1) continue
                        val apkPath = trimmed.substring(8, eqIndex).trim()
                        val pkgName = trimmed.substring(eqIndex + 1).trim()

                        if (pkgName.isNotEmpty() && !apps.containsKey(pkgName)) {
                            var appName = pkgName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                            var isSys = true
                            var isEn = true
                            var targetSdk = 33
                            var minSdk = 21
                            var installTime = System.currentTimeMillis()
                            var updateTime = System.currentTimeMillis()
                            var versionName = "Unknown"
                            var versionCode = 1L
                            var icon: android.graphics.drawable.Drawable? = null

                            try {
                                val appInfo = pm.getApplicationInfo(pkgName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                                isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 || apkPath.startsWith("/system") || apkPath.startsWith("/product") || apkPath.startsWith("/vendor")
                                isEn = appInfo.enabled
                                targetSdk = appInfo.targetSdkVersion
                                val label = pm.getApplicationLabel(appInfo).toString()
                                if (label.isNotBlank()) appName = label
                                icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                                
                                val pInfo = pm.getPackageInfo(pkgName, 0)
                                versionName = pInfo.versionName ?: "Unknown"
                                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
                                installTime = pInfo.firstInstallTime
                                updateTime = pInfo.lastUpdateTime
                            } catch (e: Exception) {}

                            apps[pkgName] = AppInfo(
                                packageName = pkgName,
                                appName = appName,
                                versionName = versionName,
                                versionCode = versionCode,
                                icon = icon,
                                isSystemApp = isSys,
                                isEnabled = isEn,
                                installTime = installTime,
                                updateTime = updateTime,
                                targetSdkVersion = targetSdk,
                                minSdkVersion = minSdk,
                                apkPath = apkPath
                            )
                        }
                    }
                }
            } catch (e: Exception) {}

            var appList = apps.values.toMutableList()

            appList = when (filter) {
                AppFilter.ALL -> if (showSystemApps) appList else appList.filter { !it.isSystemApp }.toMutableList()
                AppFilter.USER -> appList.filter { !it.isSystemApp }.toMutableList()
                AppFilter.SYSTEM -> appList.filter { it.isSystemApp }.toMutableList()
                AppFilter.FAVORITE -> appList.filter { PreferencesManager.isFavoriteApp(context, it.packageName) }.toMutableList()
                AppFilter.DISABLED -> appList.filter { !it.isEnabled }.toMutableList()
            }

            if (!showDisabledApps) appList = appList.filter { it.isEnabled }.toMutableList()

            if (searchQuery.isNotEmpty()) {
                appList = appList.filter { it.appName.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }.toMutableList()
            }

            appList = when (sortOrder) {
                AppSortOrder.NAME -> appList.sortedBy { it.appName.lowercase() }.toMutableList()
                AppSortOrder.INSTALL_DATE -> appList.sortedByDescending { it.installTime }.toMutableList()
                AppSortOrder.SIZE -> {
                    val sizeMap = getAppSizesBatch(appList)
                    appList.sortedByDescending { sizeMap[it.packageName] ?: 0L }.toMutableList()
                }
            }

            appList
        } catch (e: Exception) { emptyList() }
    }

    suspend fun getAppPermissions(packageName: String): List<PermissionInfo> = withContext(Dispatchers.IO) {
        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
            val requestedPermissions = pkgInfo?.requestedPermissions ?: return@withContext emptyList()
            val permissionFlags = pkgInfo.requestedPermissionsFlags ?: return@withContext emptyList()
            val permissions = mutableListOf<PermissionInfo>()
            val isDhizuku = ShizukuManager.isDhizukuMode()

            for (index in requestedPermissions.indices) {
                val permName = requestedPermissions[index]
                if (isDhizuku && permName in DHIZUKU_UNSUPPORTED_PERMISSIONS) continue

                val isGranted = when {
                    permName == "android.permission.MANAGE_EXTERNAL_STORAGE" -> getManageExternalStorageStatus(packageName) ?: false
                    permName in APPOP_PERMISSIONS -> getSpecialPermissionStatus(packageName, permName) ?: false
                    else -> (permissionFlags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                }

                val isDangerous = try {
                    val permInfo = @Suppress("DEPRECATION") pm.getPermissionInfo(permName, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) permInfo.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS else false
                } catch (e: Exception) { false }

                permissions.add(PermissionInfo(name = permName, isGranted = isGranted, isRuntime = isDangerous, isDangerous = isDangerous))
            }
            permissions.sortedWith(compareByDescending<PermissionInfo> { it.isDangerous }.thenBy { it.name })
        } catch (e: Exception) { emptyList() }
    }

    suspend fun hasPermissionInManifest(packageName: String, permission: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
            pkgInfo?.requestedPermissions?.contains(permission) == true
        } catch (e: Exception) { false }
    }

    suspend fun getAppsWithPermission(permission: String): List<Pair<AppInfo, Boolean>> = withContext(Dispatchers.IO) {
        try {
            val allApps = getInstalledApps()
            val result = mutableListOf<Pair<AppInfo, Boolean>>()
            for (app in allApps) {
                result.add(Pair(app, true))
            }
            result.sortedBy { it.first.appName.lowercase() }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun getAppsWithPermissionCategory(permissions: List<String>, showSystemApps: Boolean = false): List<Pair<AppInfo, Map<String, Boolean>>> = withContext(Dispatchers.IO) {
        emptyList()
    }
}
