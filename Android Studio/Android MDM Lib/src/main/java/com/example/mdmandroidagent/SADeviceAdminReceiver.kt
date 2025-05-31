package com.example.mdmandroidagent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.app.admin.SystemUpdatePolicy
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Context.DEVICE_POLICY_SERVICE
import android.content.Context.WIFI_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.generateResetToken
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.retrieveString
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.storeString
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.thisDeviceIMEI
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.thisDeviceSN
import java.io.FileOutputStream
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.retrieveByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SADeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Device admin enabled")
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "DEVICE_ADMIN_ENABLED", "")

        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = getAdminComponentName(context)

        MDMContextHolder.initialize(context)
        requestDeviceAdmin(context)

        // Open app's Permissions settings screen
        val permissionsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (permissionsIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(permissionsIntent)
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user to manage all permissions")
        }

        // Auto-grant permissions for device owner
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            ensurePermissions(context, adminComponent)
        } else {
            promptInstallUnknownApps(context)
        }

        promptInitialServiceEnableLocation(context)
        disableAutoRevokePermissions(context, adminComponent)
        requestNotificationListenerPermission(context, "main")
    }

    @Suppress("unused")
    private fun promptInitialServiceEnableWifi(context: Context) {
        val wifiManager = context.getSystemService(WIFI_SERVICE) as WifiManager

        // Prompt for Wi-Fi
        if (!wifiManager.isWifiEnabled) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Initial prompt: Wi-Fi settings")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt Wi-Fi settings: ${e.message}")
                }
            } else {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Wi-Fi settings prompt unavailable")
            }
        }
    }

    @Suppress("unused")
    private fun promptInitialServiceEnableMobile(context: Context) {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        // Prompt for mobile data
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val isMobileDataEnabled = telephonyManager.isDataEnabled
            if (!isMobileDataEnabled) {
                val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    try {
                        context.startActivity(intent)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Initial prompt: Mobile data settings")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt mobile data settings: ${e.message}")
                    }
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Mobile data settings prompt unavailable")
                }
            }
        } else {
            val intent = Intent(context, SAPermissionActivity::class.java).apply {
                putStringArrayListExtra("permissions", ArrayList(listOf(Manifest.permission.READ_PHONE_STATE)))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Initial prompt: READ_PHONE_STATE permission")
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt READ_PHONE_STATE permission: ${e.message}")
            }
        }
    }

    private fun promptInitialServiceEnableLocation(context: Context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Prompt for location
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Initial prompt: Location settings")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt location settings: ${e.message}")
                }
            } else {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Location settings prompt unavailable")
            }
        }

    }

    private fun startPeriodicServiceChecks(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<SAServiceCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "ServiceCheck",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Started periodic service checks with WorkManager")
    }

    internal fun ensureServicesEnabled(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val wifiManager = context.getSystemService(WIFI_SERVICE) as WifiManager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Location enforcement
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dpm.setLocationEnabled(adminComponent, true)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Location enabled via DevicePolicyManager")
                } else {
                    @Suppress("DEPRECATION")
                    Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Location enabled via Settings.Secure")
                }
            } catch (e: SecurityException) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to enable location: ${e.message}")
            }
        } else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user to enable location")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt location: ${e.message}")
                }
            } else {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Location settings prompt unavailable")
            }
        }

        // Wi-Fi enforcement
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.WIFI_ON, "1")
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Wi-Fi enabled via DevicePolicyManager")
            } catch (e: SecurityException) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to enable Wi-Fi: ${e.message}")
            }
        } else if (!wifiManager.isWifiEnabled) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user to enable Wi-Fi")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt Wi-Fi: ${e.message}")
                }
            } else {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Wi-Fi settings prompt unavailable")
            }
        }

        // Mobile data enforcement
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.setGlobalSetting(adminComponent, "mobile_data", "1")
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Mobile data enabled via DevicePolicyManager")
            } catch (e: SecurityException) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to enable mobile data: ${e.message}")
            }
        } else {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    val isMobileDataEnabled = telephonyManager.isDataEnabled
                    if (!isMobileDataEnabled) {
                        val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            try {
                                context.startActivity(intent)
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user to enable mobile data")
                            } catch (e: Exception) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt mobile data: ${e.message}")
                            }
                        } else {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Mobile data settings prompt unavailable")
                        }
                    }
                } else {
                    val intent = Intent(context, SAPermissionActivity::class.java).apply {
                        putStringArrayListExtra("permissions", ArrayList(listOf(Manifest.permission.READ_PHONE_STATE)))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user for READ_PHONE_STATE permission")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt READ_PHONE_STATE: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to check or enable mobile data: ${e.message}")
            }
        }
    }

    private fun registerServiceToggleReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val adminComponent = getAdminComponentName(context)
                ensureServicesEnabled(context, adminComponent)
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.location.PROVIDERS_CHANGED")
            addAction("android.net.wifi.WIFI_STATE_CHANGED")
            addAction("android.intent.action.SIM_STATE_CHANGED")
        }
        context.registerReceiver(receiver, filter)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Registered service toggle receiver")
    }

    private fun enableGPS(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Cannot enable GPS: Not device owner")
            return
        }

        // Grant WRITE_SECURE_SETTINGS if needed
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            try {
                dpm.setPermissionGrantState(
                    adminComponent,
                    context.packageName,
                    Manifest.permission.WRITE_SECURE_SETTINGS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Granted WRITE_SECURE_SETTINGS")
            } catch (e: SecurityException) {
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to grant WRITE_SECURE_SETTINGS: ${e.message}")
                return
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: Use DevicePolicyManager
                dpm.setLocationEnabled(adminComponent, true)
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "GPS enabled via DevicePolicyManager")
            } else {
                // API 26–29: Set LOCATION_MODE to HIGH_ACCURACY
                @Suppress("DEPRECATION")
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                )
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "GPS enabled via Settings.Secure")
            }

            // Verify GPS state
            Handler(Looper.getMainLooper()).postDelayed({
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "GPS verified as enabled")
                } else {
                    // Fallback: Prompt user
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                        broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Prompted user to enable GPS")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to prompt GPS enable: ${e.message}")
                    }
                }
            }, 5000)
        } catch (e: SecurityException) {
            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to enable GPS: ${e.message}")
            // Fallback: Prompt user
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Prompted user to enable GPS due to SecurityException")
            } catch (e: Exception) {
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to prompt GPS enable: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION", "unused")
    private fun enableWiFi(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val wifiManager = context.getSystemService(WIFI_SERVICE) as WifiManager

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Cannot enable Wi-Fi: Not device owner")
            return
        }

        // Clear DISALLOW_CHANGE_WIFI_STATE restriction (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CHANGE_WIFI_STATE)
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Cleared DISALLOW_CHANGE_WIFI_STATE restriction")
            } catch (e: SecurityException) {
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to clear Wi-Fi restriction: ${e.message}")
                return
            }
        }

        // Verify CHANGE_WIFI_STATE permission
        if (context.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            try {
                dpm.setPermissionGrantState(
                    adminComponent,
                    context.packageName,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Granted CHANGE_WIFI_STATE permission")
            } catch (e: SecurityException) {
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to grant CHANGE_WIFI_STATE: ${e.message}")
                return
            }
        }

        // Attempt to enable Wi-Fi
        if (!wifiManager.isWifiEnabled) {
            try {
                val success = wifiManager.setWifiEnabled(true)
                if (success) {
                    broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Wi-Fi enable requested")
                    // Verify Wi-Fi state after a delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (wifiManager.isWifiEnabled) {
                            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Wi-Fi successfully enabled")
                        } else {
                            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Wi-Fi not enabled after attempt")
                            // Retry once
                            if (wifiManager.setWifiEnabled(true)) {
                                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Wi-Fi enable retry successful")
                            } else {
                                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Wi-Fi enable retry failed")
                            }
                        }
                    }, 5000)
                } else {
                    broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Wi-Fi enable request failed")
                }
            } catch (e: SecurityException) {
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "SecurityException enabling Wi-Fi: ${e.message}")
            }
        } else {
            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Wi-Fi already enabled")
        }
    }

    @Suppress("unused")
    fun enforceWifiAndGps(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

        // Wi-Fi Enforcement
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.WIFI_ON, "1")
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Wi-Fi enforced ON and settings restricted")
            } catch (e: SecurityException) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Wi-Fi enforcement failed: ${e.message}")
            }
        } else {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user to enable Wi-Fi")
        }

        // GPS (Location) Enforcement
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            try {
                // For API 30+ (Android 11 and above), enable location directly
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dpm.setLocationEnabled(adminComponent, true)
                }

                // Restrict location settings changes on API 28+ (Android 9 and above)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Location settings restricted (API 28+)")
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Location settings restriction not available (API < 28)")
                }

                // Check if location is enabled; prompt user if not
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                    !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user to enable location")
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Location already enabled")
                }
            } catch (e: SecurityException) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Location enforcement failed: ${e.message}")
            }
        } else {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user to enable location (GPS)")
            }
        }
    }

    @Suppress("unused")
    fun requestPhoneStatePermission(activity: Activity) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_PHONE_STATE), 100)
        }
    }

    private fun getAdminComponentName(context: Context) = ComponentName(context, SADeviceAdminReceiver::class.java)


    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Device admin disabled")
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "DEVICE_ADMIN_DISABLED", "")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordChanged(context, intent, user)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Password changed")
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin",
            "PASSWORD_CHANGED",
            intent.getIntExtra("android.intent.extra.USER_ID", -1).toString() + ""
        )
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Password attempt failed")
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "PASSWORD_FAILED", "")
    }

    override fun onPasswordExpiring(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordExpiring(context, intent, user)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Password nearing expiration")
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "PASSWORD_EXPIRING", "")
    }

    object MDMContextHolder {
        private var appContext: Context? = null

        @Synchronized
        fun initialize(context: Context) {
            appContext = context.applicationContext
        }

        fun getContext(): Context {
            return appContext ?: throw IllegalStateException("MDMContextHolder not initialized. Call initialize() first.")
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Profile provisioning completed")
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "PROFILE_PROVISIONING_COMPLETE", "")

        MDMContextHolder.initialize(context)
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = getAdminComponentName(context)
        initializeResetToken(context, adminComponent, dpm)

        // Retrieve Google account email from provisioning extras
        val extras = intent.getBundleExtra("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
        val googleAccountEmail = extras?.getString("google_account_email")
        if (!googleAccountEmail.isNullOrEmpty()) {
            // Check if account exists
            val accountManager = context.getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
            val accounts = accountManager.getAccountsByType("com.google")
            if (!accounts.any { it.name.equals(googleAccountEmail, ignoreCase = true) }) {
                // Prompt to add account
                val addAccountIntent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                    putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                    putExtra(Settings.EXTRA_AUTHORITIES, arrayOf("com.google.android.gsf.login"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(addAccountIntent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Prompted user to add Google account: $googleAccountEmail for FRP")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to prompt account addition: ${e.message}")
                }
            }
            // Apply FRP policy for API 30+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val frpPolicy = FactoryResetProtectionPolicy.Builder()
                        .setFactoryResetProtectionAccounts(listOf(googleAccountEmail))
                        .setFactoryResetProtectionEnabled(true)
                        .build()
                    dpm.setFactoryResetProtectionPolicy(adminComponent, frpPolicy)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied FRP policy with email: $googleAccountEmail")
                } catch (e: SecurityException) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to apply FRP policy: ${e.message}")
                }
            }
        }

        enableGPS(context, adminComponent)
        ensureServicesEnabled(context, adminComponent)
        startPolicyService(context)
        startPeriodicServiceChecks(context)
        registerServiceToggleReceiver(context)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Provisioning Complete")
    }

    @Suppress("unused")
    fun disableAppPostProvisioning(context: Context, adminComponent: ComponentName): Boolean {
        try {
            val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

            // Verify device owner status
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "App is not a device owner. Cannot disable $TARGET_PACKAGE")
                return false
            }

            // Disable the target app for the primary user
            dpm.setApplicationHidden(adminComponent, TARGET_PACKAGE, true)
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Successfully disabled $TARGET_PACKAGE")
            return true
        } catch (e: SecurityException) {
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "SecurityException while disabling $TARGET_PACKAGE: ${e.message}")
            return false
        } catch (e: Exception) {
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Error while disabling $TARGET_PACKAGE: ${e.message}")
            return false
        }
    }

    private fun disableAutoRevokePermissions(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Cannot disable auto-revoke: Not device owner")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            try {
                dpm.setPermissionGrantState(
                    adminComponent,
                    context.packageName,
                    Manifest.permission.WRITE_SECURE_SETTINGS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Granted WRITE_SECURE_SETTINGS")
            } catch (e: SecurityException) {
                broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to grant WRITE_SECURE_SETTINGS: ${e.message}")
                return
            }
        }
        try {
            Settings.Global.putInt(context.contentResolver, "auto_revoke_permissions_if_unused", 0)
            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Disabled global auto-revoke permissions")
        } catch (e: SecurityException) {
            broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to disable global auto-revoke: ${e.message}")
        }
    }

    @SuppressLint("InlinedApi")
    private fun ensurePermissions(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.GET_ACCOUNTS
        )
        for (perm in permissions) {
            if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                try {
                    dpm.setPermissionGrantState(
                        adminComponent,
                        context.packageName,
                        perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                    broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Granted $perm")
                } catch (e: SecurityException) {
                    broadcastToXojo(context, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to grant $perm: ${e.message}")
                }
            }
        }
    }

    @Suppress("unused")
    fun forceStopApp(context: Context): Boolean {
        try {
            val process = ProcessBuilder()
                .command("su", "-c", "am force-stop $TARGET_PACKAGE")
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Successfully force-stopped $TARGET_PACKAGE")
                return true
            } else {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to force-stop $TARGET_PACKAGE. Exit code: $exitCode")
                return false
            }
        } catch (e: Exception) {
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Error force-stopping $TARGET_PACKAGE: ${e.message}")
            return false
        }
    }

    private fun startPolicyService(context: Context) {
        try {
            val serviceIntent = Intent(context, SAPolicyEnforcementService::class.java).apply {
                action = SAPolicyEnforcementService.ACTION_START_MAINSCREEN
            }
            MDMContextHolder.initialize(context)
            // Use startForegroundService to comply with background restrictions
            ContextCompat.startForegroundService(context, serviceIntent)
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Initiated SAPolicyEnforcementService as foreground service")
        } catch (e: Exception) {
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Error", "Failed to start SAPolicyEnforcementService: ${e.message}")
        }
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Entering lock task mode")
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "LOCK_TASK_ENTERING", pkg)
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Exiting lock task mode")
        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "LOCK_TASK_EXITING", "")
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        MDMContextHolder.initialize(context)
        if (intent.action == "INSTALL_COMPLETE") {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val apkUrl = intent.getStringExtra("apkUrl") ?: "Unknown URL"
            val sessionId = intent.getIntExtra("sessionId", -1)
            val msgId = intent.getStringExtra("policyId") ?: ""
            val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "Unknown"

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Install requires user action for $apkUrl (Session $sessionId)")

                    val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("android.intent.extra.INTENT", Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("android.intent.extra.INTENT") as Intent?
                    }
                    if (confirmationIntent != null) {
                        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val handler = Handler(Looper.getMainLooper())
                        handler.post {
                            try {
                                context.startActivity(confirmationIntent)
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Install prompt launched for $apkUrl (Session $sessionId)")
                            } catch (e: Exception) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to launch install prompt: ${e.javaClass.simpleName} - ${e.message}")
                            }
                        }
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "No confirmation intent found for $apkUrl (Session $sessionId)")
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Package", "Applied Policy: $msgId - APK installed successfully: $packageName from $apkUrl (Session $sessionId)")
                    // Check if the installed package is this app
                    if (packageName == context.packageName) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "This app was updated, restarting policy service")
                        // Add a slight delay to ensure the app is fully updated
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                startPolicyServiceExtra(context)

                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Pending Policies Loading")
                                        runNextCommands(context, intent, status)
                                    } catch (e: Exception) {
                                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to restart Pending Policies: ${e.message}")
                                    }
                                }, 1000)

                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy service restarted after app update")
                            } catch (e: Exception) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to restart policy service: ${e.message}")
                            }
                        }, 1000) // 1-second delay to allow system to stabilize
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Pending Policies Loading")
                        runNextCommands(context, intent, status)
                    }
                }
                PackageInstaller.STATUS_FAILURE -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown failure"
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "APK install failed for $apkUrl: $msg (Session $sessionId)")
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Pending Policies Loading")
                    runNextCommands(context, intent, status)
                }
                else -> {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "APK install status $status for $apkUrl (Session $sessionId)")
                }
            }
        } else if (intent.action == "UNINSTALL_COMPLETE") {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val packageName = intent.getStringExtra("packageName") ?: "Unknown"
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Package", "Uninstall succeeded for $packageName")
                PackageInstaller.STATUS_FAILURE -> broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Uninstall failed for $packageName: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                else -> broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Uninstall status $status for $packageName")
            }
        }
    }

    companion object {
        @Suppress("unused")
        private const val TAG = "SADeviceAdminReceiver"
        //private const val TAGPOLICY = "SAPolicyEnforcementService"
        private const val XOJOAPPIDENTIFIER = "com.example.mdmandroidagent"
        private const val TARGET_PACKAGE = "com.aura.oobe.samsung.gl"
        private val pendingPolicies = mutableListOf<Pair<JSONObject, JSONObject>>() // Store policy JSON and ID JSON
        private var installLatch = CountDownLatch(0)
        private val activeInstallSessions = mutableSetOf<Int>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun kioskPersistence(contextObject: Any?) {
            val context = contextObject as? Context ?: run {
                broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Invalid context for kiosk persistence")
                return
            }
            MDMContextHolder.initialize(context)
            val data = retrieveString(context, "kioskPersistent")
            val parts = data.split("{-}")
            if (parts.size >= 2 && parts[1].isNotEmpty()) {
                unlockPhone(context, "boot$parts[1]")
                mainHandler.post {
                    try {
                        enterKioskMode(context, parts[0], "boot$parts[1]")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to enter kiosk mode: ${e.message}")
                    }
                }
            }
        }

        fun startPolicyServiceExtra(contextObject: Any?) {
            val context = contextObject as Context
            try {
                val serviceIntent = Intent(context, SAPolicyEnforcementService::class.java).apply {
                    action = SAPolicyEnforcementService.ACTION_START_MAINSCREEN
                }
                MDMContextHolder.initialize(context)
                // Use startForegroundService to comply with background restrictions
                ContextCompat.startForegroundService(context, serviceIntent)

                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "PolicyEnforcementService started")

                val mainIntent = Intent(context, Class.forName("com.example.mdmandroidagent.screen_main")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(mainIntent)
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Error", "Failed to start SAPolicyEnforcementService: ${e.message}")
            }
        }

        fun runNextCommands(context: Context, intent: Intent, status: Int){
            val sessionId = intent.getIntExtra("sessionId", -1)
            if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_FAILURE) {
                synchronized(activeInstallSessions) {
                    if (activeInstallSessions.remove(sessionId)) {
                        installLatch.countDown()
                        if (installLatch.count == 0L) {
                            // All installs complete, apply pending policies
                            synchronized(pendingPolicies) {
                                pendingPolicies.forEach { (json, jsonId) ->
                                    applyNonInstallPolicies(context, json, jsonId)
                                }
                                pendingPolicies.clear()
                            }
                        }
                    }
                }
            }
        }

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, SADeviceAdminReceiver::class.java)
        }

        @Suppress("unused")
        private fun removeProfile(contextObject: Any?) {
            val context = contextObject as? Context ?: return
            //if (context.isFinishing) return
            val manager = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            manager.wipeData(0)
        }

        fun initializeResetToken(context: Context, adminComponent: ComponentName, dpm: DevicePolicyManager) {
            try {
                val resetPasswordToken = generateResetToken()
                val success = dpm.setResetPasswordToken(adminComponent, resetPasswordToken)
                if (success) {
                    //storeSecureData(resetPasswordToken = resetPasswordToken)
                    storeString(context, "SAResetPasswordToken", resetPasswordToken)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Reset password token set successfully")
                    if (!dpm.isResetPasswordTokenActive(adminComponent)) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Reset password token is not active, activation may be required")
                        dpm.setResetPasswordToken(adminComponent, resetPasswordToken)
                    }
                    val serialNumber = getDeviceIdentifiers(context, "SN", 100)
                    val imei = getDeviceIdentifiers(context, "IMEI", 100)
                    if (serialNumber.startsWith("Failed") || imei.startsWith("Failed")) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Initial identifier fetch failed, will retry later")
                    } else {
                        //storeSecureData(serialNumber = serialNumber, imei = imei)
                        storeString(context, "SASerialNumber", serialNumber)
                        storeString(context, "SAImei", imei)
                    }
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to set reset password token")
                }
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Error", "Error initializing reset token: ${e.message}")
            }
        }

        @Suppress("unused")
        fun logUserProfiles(context: Context) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val profiles = userManager.userProfiles
            profiles.forEach { userHandle ->
                val userId = userManager.getSerialNumberForUser(userHandle)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log",
                    "Found user profile: ID=$userId, Handle=$userHandle")
            }
        }

        @Suppress("unused")
        fun getDeviceSN(contextObject: Any?): String {
            val context = contextObject as Context

            if (thisDeviceSN == "") {
                thisDeviceSN = getDeviceIdentifiers(context, "SN")
            }

            return thisDeviceSN
        }

        @Suppress("unused")
        fun getDeviceIMEI(contextObject: Any?): String {
            val context = contextObject as Context

            if (thisDeviceIMEI == "") {
                thisDeviceIMEI = getDeviceIdentifiers(context, "IMEI")
            }

            return thisDeviceIMEI
        }

        @SuppressLint("HardwareIds")
        fun getDeviceIdentifiers(context: Context, detail: String, maxAttempts: Int = 2, delay: Long = 100): String {
            var returnData: String

            try {
                MDMContextHolder.initialize(context)
                // Check stored data first
                when (detail) {
                    "SN" -> {
                        val storedSN =
                            retrieveString(MDMContextHolder.getContext(), "SASerialNumber")
                        if (storedSN.isNotEmpty()) {
                            thisDeviceSN = storedSN
                            return thisDeviceSN
                        }
                    }

                    "IMEI" -> {
                        val storedIMEI = retrieveString(MDMContextHolder.getContext(), "SAImei")
                        if (storedIMEI.isNotEmpty()) {
                            thisDeviceIMEI = storedIMEI
                            return thisDeviceIMEI
                        }
                    }
                }

                grantPhoneStatePermission(context)

                // If not stored, attempt to fetch with retries
                var attempt = 0
                while (attempt < maxAttempts) {
                    try {
                        when (detail) {
                            "SN" -> {
                                val serial = Build.getSerial()
                                if (serial.isNotEmpty() && serial != Build.UNKNOWN) {
                                    thisDeviceSN = serial
                                    storeString(
                                        MDMContextHolder.getContext(),
                                        "SASerialNumber",
                                        serial
                                    )
                                    broadcastToXojo(
                                        context,
                                        "PROVISION_EVENT",
                                        "Provisioning",
                                        "Enrollment",
                                        "Enrollment SN Received: $thisDeviceSN"
                                    )
                                    return serial
                                }
                            }

                            "IMEI" -> {
                                val telephonyManager = getSystemService(
                                    context,
                                    TelephonyManager::class.java
                                )
                                val imei = telephonyManager?.imei
                                if (!imei.isNullOrEmpty()) {
                                    thisDeviceIMEI = imei
                                    storeString(MDMContextHolder.getContext(), "SAImei", imei)
                                    broadcastToXojo(
                                        context,
                                        "PROVISION_EVENT",
                                        "Provisioning",
                                        "Enrollment",
                                        "Enrollment IMEI Received: $thisDeviceIMEI"
                                    )
                                    return imei
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        broadcastToXojo(
                            context,
                            "ADMIN_EVENT",
                            "DeviceAdmin",
                            "Error",
                            "Attempt ${attempt + 1} failed to get $detail: ${e.message}"
                        )
                    } catch (e: Exception) {
                        broadcastToXojo(
                            context,
                            "ADMIN_EVENT",
                            "DeviceAdmin",
                            "Error",
                            "Unexpected error in attempt ${attempt + 1} for $detail: ${e.message}"
                        )
                    }

                    attempt++
                    if (attempt < maxAttempts) {
                        Thread.sleep(delay) // Wait before retrying
                    }
                }

                returnData = "Failed to get $detail"
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Error", returnData)
            } catch (e: Exception) {
                returnData = "Unexpected error getting $detail: ${e.message}"
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Error", returnData)
            }

            return returnData
        }

        fun grantPhoneStatePermission(context: Context) {
            try {
                val hasPhoneStatePermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPhoneStatePermission) {
                    broadcastToXojo(
                        context,
                        "ADMIN_EVENT",
                        "DeviceAdmin",
                        "Log",
                        "READ_PHONE_STATE permission already granted"
                    )
                    return
                }

                // Get DevicePolicyManager and admin component
                val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = getComponentName(context)

                // Verify device owner status
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    broadcastToXojo(
                        context,
                        "ADMIN_EVENT",
                        "DeviceAdmin",
                        "Error",
                        "Cannot grant READ_PHONE_STATE: Not device owner"
                    )
                    return
                }

                // Attempt to grant READ_PHONE_STATE permission
                val permission = Manifest.permission.READ_PHONE_STATE
                val grantResult = dpm.setPermissionGrantState(
                    adminComponent,
                    context.packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )

                if (grantResult) {
                    broadcastToXojo(
                        context,
                        "ADMIN_EVENT",
                        "DeviceAdmin",
                        "Log",
                        "Successfully granted READ_PHONE_STATE permission"
                    )
                } else {
                    broadcastToXojo(
                        context,
                        "ADMIN_EVENT",
                        "DeviceAdmin",
                        "Error",
                        "Failed to grant READ_PHONE_STATE permission"
                    )
                }
            } catch (e: SecurityException) {
                broadcastToXojo(
                    context,
                    "ADMIN_EVENT",
                    "DeviceAdmin",
                    "Error",
                    "SecurityException while granting READ_PHONE_STATE: ${e.message}"
                )
            } catch (e: Exception) {
                broadcastToXojo(
                    context,
                    "ADMIN_EVENT",
                    "DeviceAdmin",
                    "Error",
                    "Unexpected error granting READ_PHONE_STATE: ${e.message}"
                )
            }
        }

        fun requestDeviceAdmin(contextObject: Any?) {
            try {
                val context = contextObject as? Context ?: return
                val devicePolicyManager =
                    context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)

                if (!devicePolicyManager.isAdminActive(adminComponent)) {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Enable device admin for policy enforcement"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Add this flag
                    }

                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Requested device admin activation")
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Device admin already active, no request needed")
                }
            } catch (e: Exception) {
                broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to request device admin: ${e.message}")
            }
        }

        @Suppress("unused")
        private fun autoEnableInstallUnknownApps(context: Context, dpm: DevicePolicyManager, adminComponent: ComponentName) {
            try {
                val permission = Manifest.permission.REQUEST_INSTALL_PACKAGES
                val packageName = context.packageName
                val grantResult = dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                if (grantResult) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Auto-enabled Install Unknown Apps for $packageName")
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to auto-enable Install Unknown Apps for $packageName")
                    promptInstallUnknownApps(context)
                }
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to auto-enable Install Unknown Apps: ${e.message}")
                promptInstallUnknownApps(context)
            }
        }

        fun promptInstallUnknownApps(contextObject: Any?) {
            try {
                val context = contextObject as? Context ?: run {
                    broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Invalid context for prompting unknown apps")
                    return
                }

                val packageManager = context.packageManager
                val packageName = context.packageName

                if (!packageManager.canRequestPackageInstalls()) {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = "package:$packageName".toUri()
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Required for non-Activity context
                        }
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin",
                        "Log",
                        "Prompted user to enable Install Unknown Apps for $packageName"
                    )
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Install Unknown Apps already enabled for $packageName")
                }
            } catch (e: Exception) {
                broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin",
                    "Log",
                    "Failed to prompt Install Unknown Apps: ${e.javaClass.simpleName} - ${e.message ?: "No message"}"
                )
            }
        }

        @SuppressLint("WrongConstant")
        fun getInstalledUserApps(contextObject: Any?): String {
            try {
                val context = contextObject as? Context ?: run {
                    broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Invalid context in getInstalledUserApps")
                    return "[]"
                }
                val pm = context.packageManager
                val dpm =
                    context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)
                val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager

                // Step 1: Get apps from all user profiles
                val allApps = mutableListOf<ApplicationInfo>()
                val userHandles = userManager.userProfiles // List of UserHandle for all profiles
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Found ${userHandles.size} user profiles")

                for (userHandle in userHandles) {
                    try {
                        val userApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // Use getInstalledPackagesAsUser if API 33+
                            val packageInfoList =
                                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                            packageInfoList.map { it.applicationInfo }
                        } else {
                            // Fallback for older APIs (no user-specific query)
                            @Suppress("DEPRECATION")
                            pm.getInstalledPackages(0).map { it.applicationInfo }
                        }
                        allApps.addAll(userApps)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Apps in user ${userHandle}: ${userApps.size}")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin",
                            "Log",
                            "Failed to get apps for user ${userHandle}: ${e.message}"
                        )
                    }
                }

                // Filter for apps installed by MDM
                val mdmApps = allApps.filter { appInfo ->
                    val flags = appInfo.flags
                    val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            pm.getInstallSourceInfo(appInfo.packageName).installingPackageName
                        } catch (_: PackageManager.NameNotFoundException) {
                            null
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(appInfo.packageName)
                    }
                    val installedByMDM =
                        installer == context.packageName || installer == XOJOAPPIDENTIFIER //|| installer == null
                    val isManagedByMDM = dpm.isDeviceOwnerApp(context.packageName) &&
                            (dpm.isApplicationHidden(adminComponent, appInfo.packageName) ||
                                    appInfo.packageName == context.packageName)
                    !isSystem || (installedByMDM || isManagedByMDM)
                }.distinctBy { it.packageName } // Remove duplicates across profiles

                val mdmAppLabels = mdmApps.joinToString(", ") { appInfo ->
                    val label = try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        appInfo.packageName
                    }
                    "$label (${appInfo.packageName})"
                }
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "MDM-installed apps: ${mdmApps.size}: $mdmAppLabels")

                /*val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong() or PackageManager.MATCH_ALL.toLong())
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_META_DATA
                }*/

                // Attempt to get managed profile apps (simplified approach)
                val packageInstaller = pm.packageInstaller
                val sessions = packageInstaller.mySessions
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Found ${sessions.size} active PackageInstaller sessions")
                sessions.forEach { sessionInfo ->
                    val packageName = sessionInfo.appPackageName
                    if (packageName != null) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Session package: $packageName")
                        if (!allApps.any { it.packageName == packageName }) {
                            try {
                                val appInfo =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        pm.getApplicationInfo(
                                            packageName,
                                            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        pm.getApplicationInfo(
                                            packageName,
                                            PackageManager.GET_META_DATA
                                        )
                                    }
                                allApps.add(appInfo)
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log","Added $packageName from PackageInstaller session")
                            } catch (_: PackageManager.NameNotFoundException) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log","Session package $packageName not found in PackageManager")
                            }
                        }
                    }
                    val sessionId = sessionInfo.sessionId

                    try {
                        val session = packageInstaller.openSession(sessionId)
                        session.abandon()
                        session.close()
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Session $sessionId abandoned successfully")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin",
                            "Log",
                            "Failed to abandon session $sessionId: ${e.javaClass.simpleName} - ${e.message}"
                        )
                    }
                }

                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Total apps after PackageInstaller check: ${allApps.size}")

                /*val userApps = apps.filter { appInfo ->
                    val flags = appInfo.flags
                    val isSystem = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    !isSystem // Only non-system apps
                }*/
                val mdmAppsJson = mdmApps.map { appInfo ->
                    val label = try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        appInfo.packageName
                    }
                    val packageName = appInfo.packageName
                    val packageVersion = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getPackageInfo(
                                packageName,
                                PackageManager.PackageInfoFlags.of(0)
                            ).versionName
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getPackageInfo(packageName, 0).versionName
                        } ?: "Unknown"
                    } catch (_: PackageManager.NameNotFoundException) {
                        "Unknown"
                    }
                    "{\"packageName\": \"$packageName\", \"label\": \"$label\", \"version\": \"$packageVersion\"}"
                }
                val result = "[${mdmAppsJson.joinToString(",")}]"
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Returning user apps: $result")

                return result
            } catch (e: Exception) {
                broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to get user apps: ${e.message}")
                return "[]"
            }
        }

        fun applyPolicies(contextObject: Any?, idJsonObj: Any?, policyJsonObj: Any?) {
            val context = contextObject as Context
            val policyJson = policyJsonObj as String
            val idJson = idJsonObj as String

            broadcastToXojo(
                context,
                "ADMIN_EVENT",
                "DeviceAdmin",
                "Log",
                "Applying policies: $idJson - $policyJson"
            )

            val json = JSONObject(policyJson)
            val jsonId = JSONObject(idJson)
            val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)

            // Collect install policies
            val installPolicies = mutableListOf<Pair<String, String>>()

            if (json.has("installApp") && jsonId.has("installApp")) {
                val installApps = json.getJSONArray("installApp")
                for (i in 0 until installApps.length()) {
                    val installJson = installApps.getJSONObject(i)
                    val installId = installJson.getString("msgid")
                    val path = installJson.getString("path")
                    installPolicies.add(installId to path)
                }
            }
            if (json.has("updateApp") && jsonId.has("updateApp")) {
                val updateApps = json.getJSONArray("updateApp")
                for (i in 0 until updateApps.length()) {
                    val updateJson = updateApps.getJSONObject(i)
                    val updateId = updateJson.getString("msgid")
                    val path = updateJson.getString("path")
                    installPolicies.add(updateId to path)
                }
            }

            // Reset latch for new installs
            synchronized(activeInstallSessions) {
                activeInstallSessions.clear()
                installLatch = CountDownLatch(installPolicies.size)
            }

            // Execute install policies first
            if (installPolicies.isNotEmpty()) {
                synchronized(activeInstallSessions) {
                    activeInstallSessions.clear()
                    installLatch = CountDownLatch(installPolicies.size)
                }
                synchronized(pendingPolicies) {
                    pendingPolicies.add(json to jsonId) // Store non-install policies
                }
                installPolicies.forEach { (currentId, apkUrl) ->
                    try {
                        installApkFromUrl(context, apkUrl, adminComponent, currentId)
                    } catch (e: Exception) {
                        broadcastToXojo(
                            context,
                            "ADMIN_EVENT",
                            "DeviceAdmin",
                            "Log",
                            "Policy Failed: $currentId - ${e.message}"
                        )
                    }
                }
                return // Wait for INSTALL_COMPLETE
            }

            // Apply non-install policies directly if no installs
            applyNonInstallPolicies(context, json, jsonId)
        }


        private fun applyNonInstallPolicies(context: Context, json: JSONObject, jsonId: JSONObject) {
            val wifiManager = context.getSystemService(WIFI_SERVICE) as WifiManager
            val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)
            var currentId = ""

            if (json.has("disableCamera") && jsonId.has("disableCamera")) {
                try {
                    currentId = jsonId.getString("disableCamera")
                    dpm.setCameraDisabled(adminComponent, json.getBoolean("disableCamera"))
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - DisableCamera")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("screenLockTimeout") && jsonId.has("screenLockTimeout")) {
                try {
                    currentId = jsonId.getString("screenLockTimeout")
                    val timeoutSeconds = json.getLong("screenLockTimeout")
                    setScreenLockTimeout(context, timeoutSeconds, currentId)
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("lockNow") && jsonId.has("lockNow") && json.getBoolean("lockNow")) {
                try {
                    currentId = jsonId.getString("lockNow")
                    dpm.lockNow()
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Device locked")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("unlock") && jsonId.has("unlock") && json.getBoolean("unlock")) {
                currentId = jsonId.getString("unlock")
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Unlock requires device owner status")
                    return
                }
                unlockPhone(context, currentId)
            }

            if (json.has("wipeData") && jsonId.has("wipeData") && json.getBoolean("wipeData")) {
                try {
                    currentId = jsonId.getString("wipeData")
                    dpm.wipeData(0)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Data wiped")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("enterKiosk") && jsonId.has("enterKiosk")) {
                try {
                    currentId = jsonId.getString("enterKiosk")
                    val packageName = json.getString("enterKiosk")
                    enterKioskMode(context, packageName, currentId)
                    storeString(context, "kioskPersistent", "$packageName{-}$currentId")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("exitKiosk") && jsonId.has("exitKiosk") && json.getBoolean("exitKiosk")) {
                try {
                    currentId = jsonId.getString("exitKiosk")
                    exitKioskMode(context, currentId)
                    storeString(context, "kioskPersistent", "")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("removeApp") && jsonId.has("removeApp")) {
                try {
                    currentId = jsonId.getString("removeApp")
                    val packageName = json.getString("removeApp")
                    uninstallApk(context, packageName, currentId)
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("restrictNetwork") && jsonId.has("restrictNetwork")) {
                try {
                    currentId = jsonId.getString("restrictNetwork")
                    val networkJson = json.getJSONObject("restrictNetwork")
                    if (networkJson.has("wifi")) {
                        val disableWifi = networkJson.getBoolean("wifi")
                        if (dpm.isDeviceOwnerApp(context.packageName)) {
                            dpm.setGlobalSetting(
                                adminComponent,
                                Settings.Global.WIFI_ON,
                                if (disableWifi) "0" else "1"
                            )
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Wi-Fi ${if (disableWifi) "disabled" else "enabled"}")
                        }
                    }
                    if (networkJson.has("mobileData")) {
                        val disableMobile = networkJson.getBoolean("mobileData")
                        if (dpm.isDeviceOwnerApp(context.packageName)) {
                            dpm.setGlobalSetting(
                                adminComponent,
                                "mobile_data",
                                if (disableMobile) "0" else "1"
                            )
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Mobile data ${if (disableMobile) "disabled" else "enabled"}")
                        }
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("passwordPolicy") && jsonId.has("passwordPolicy")) {
                try {
                    currentId = jsonId.getString("passwordPolicy")
                    val pwJson = json.getJSONObject("passwordPolicy")
                    if (pwJson.has("quality")) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val complexity = when (pwJson.getString("quality").lowercase()) {
                                "numeric" -> DevicePolicyManager.PASSWORD_COMPLEXITY_LOW
                                "alphanumeric" -> DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM
                                "complex" -> DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH
                                else -> DevicePolicyManager.PASSWORD_COMPLEXITY_NONE
                            }
                            dpm.setRequiredPasswordComplexity(complexity)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Password complexity set to ${pwJson.getString("quality")}")
                        } else {
                            @Suppress("DEPRECATION")
                            val quality = when (pwJson.getString("quality").lowercase()) {
                                "numeric" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                                "alphanumeric" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                                "complex" -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
                                else -> DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED
                            }
                            @Suppress("DEPRECATION")
                            dpm.setPasswordQuality(adminComponent, quality)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Password quality set to ${pwJson.getString("quality")} (legacy)")
                        }
                    }
                    if (pwJson.has("minLength") && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        @Suppress("DEPRECATION")
                        val minLength = pwJson.getInt("minLength")
                        @Suppress("DEPRECATION")
                        dpm.setPasswordMinimumLength(adminComponent, minLength)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Password minimum length set to $minLength (legacy)")
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("hideApp") && jsonId.has("hideApp")) {
                try {
                    currentId = jsonId.getString("hideApp")
                    val packageName = json.getString("hideApp")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        dpm.setApplicationHidden(adminComponent, packageName, true)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - App $packageName hidden")
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Hiding apps requires device owner")
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("restrictPermission") && jsonId.has("restrictPermission")) {
                try {
                    currentId = jsonId.getString("restrictPermission")
                    val permJson = json.getJSONObject("restrictPermission")
                    val packageName = permJson.getString("package")
                    val permission = permJson.getString("permission")
                    dpm.setPermissionPolicy(adminComponent, DevicePolicyManager.PERMISSION_POLICY_PROMPT)
                    dpm.setPermissionGrantState(
                        adminComponent,
                        packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    )
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Permission $permission denied for $packageName")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("setTempPin") && jsonId.has("setTempPin")) {
                try {
                    currentId = jsonId.getString("setTempPin")
                    val tempPin = json.getString("setTempPin")
                    @Suppress("DEPRECATION")
                    dpm.resetPassword(tempPin, 0)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Temporary PIN set to $tempPin")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("clearPin") && jsonId.has("clearPin") && json.getBoolean("clearPin")) {
                try {
                    currentId = jsonId.getString("clearPin")
                    @Suppress("DEPRECATION")
                    dpm.resetPassword("", 0)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - PIN cleared")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("frpPolicy") && jsonId.has("frpPolicy")) {
                try {
                    currentId = jsonId.getString("frpPolicy")
                    val frpJson = json.getJSONObject("frpPolicy")
                    val enabled = frpJson.getBoolean("enabled")
                    val adminEmail = frpJson.getString("adminEmail")

                    if (enabled) {
                        if (adminEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(adminEmail).matches()) {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Invalid admin email: $adminEmail")
                            return
                        }

                        // Check if Google Mobile Services (GMS) is available
                        val pm = context.packageManager
                        @Suppress("DEPRECATION") val isGmsAvailable = try {
                            pm.getPackageInfo("com.google.android.gms", 0)
                            true
                        } catch (_: PackageManager.NameNotFoundException) {
                            false
                        }
                        if (!isGmsAvailable) {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Google Mobile Services not available on this device")
                            return
                        }

                        // Clear DISALLOW_MODIFY_ACCOUNTS restriction
                        try {
                            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Cleared DISALLOW_MODIFY_ACCOUNTS restriction")
                        } catch (e: SecurityException) {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to clear DISALLOW_MODIFY_ACCOUNTS: ${e.message}")
                        }

                        // Check if the Google account is already added
                        val accountManager = context.getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
                        val accounts = accountManager.getAccountsByType("com.google")
                        val accountExists = accounts.any { it.name.equals(adminEmail, ignoreCase = true) }

                        if (!accountExists) {
                            // Launch account prompt with delay to ensure UI availability
                            Handler(Looper.getMainLooper()).postDelayed({
                                val intent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                                    putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                                }
                                try {
                                    if (intent.resolveActivity(context.packageManager) != null) {
                                        context.startActivity(intent)
                                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $currentId - Prompted user to add Google account: $adminEmail")
                                    } else {
                                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Account addition UI unavailable")
                                    }
                                } catch (e: Exception) {
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Failed to prompt account addition: ${e.message}")
                                    // Fallback: Try alternative intent
                                    val fallbackIntent = Intent(Settings.ACTION_SYNC_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                                    }
                                    try {
                                        if (fallbackIntent.resolveActivity(context.packageManager) != null) {
                                            context.startActivity(fallbackIntent)
                                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $currentId - Fallback to sync settings UI")
                                        } else {
                                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Sync settings UI unavailable")
                                        }
                                    } catch (e: Exception) {
                                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Fallback failed: ${e.message}")
                                    }
                                }
                            }, 5000) // 5-second delay
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val frpPolicy = FactoryResetProtectionPolicy.Builder()
                                .setFactoryResetProtectionAccounts(listOf(adminEmail))
                                .setFactoryResetProtectionEnabled(true)
                                .build()
                            dpm.setFactoryResetProtectionPolicy(adminComponent, frpPolicy)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - FRP enabled with admin email: $adminEmail")
                            // Verify policy
                            val appliedPolicy = dpm.getFactoryResetProtectionPolicy(adminComponent)
                            if (appliedPolicy?.factoryResetProtectionAccounts?.contains(adminEmail) == true) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Verified: $currentId - FRP policy active")
                            } else {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $currentId - FRP policy not applied correctly")
                            }
                        } else {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $currentId - FRP via DevicePolicyManager not supported on API < 30; ensure $adminEmail is added for FRP")
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            dpm.setFactoryResetProtectionPolicy(adminComponent, null)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - FRP disabled")
                        } else {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $currentId - FRP disable not supported on API < 30")
                        }
                    }
                } catch (e: SecurityException) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - SecurityException: ${e.message}")
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("restrictBluetooth") && jsonId.has("restrictBluetooth") && json.getBoolean("restrictBluetooth")) {
                try {
                    currentId = jsonId.getString("restrictBluetooth")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Bluetooth restricted")
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("restrictUSB") && jsonId.has("restrictUSB") && json.getBoolean("restrictUSB")) {
                try {
                    currentId = jsonId.getString("restrictUSB")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - USB file transfer restricted")
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("updatePolicy") && jsonId.has("updatePolicy")) {
                try {
                    currentId = jsonId.getString("updatePolicy")
                    val updateJson = json.getJSONObject("updatePolicy")
                    val type = updateJson.getString("type")
                    when (type) {
                        "freeze" -> {
                            dpm.setSystemUpdatePolicy(adminComponent, SystemUpdatePolicy.createPostponeInstallPolicy())
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - System updates frozen")
                        }
                        "window" -> {
                            val start = updateJson.getString("start")
                            val end = updateJson.getString("end")
                            val startMinutes = timeToMinutes(start)
                            val endMinutes = timeToMinutes(end)
                            val policy = SystemUpdatePolicy.createWindowedInstallPolicy(startMinutes, endMinutes)
                            dpm.setSystemUpdatePolicy(adminComponent, policy)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - System updates restricted to $start-$end")
                        }
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("clearAllNotifications") && jsonId.has("clearAllNotifications") && json.getBoolean("clearAllNotifications")) {
                try {
                    currentId = jsonId.getString("clearAllNotifications")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        clearAllNotifications(context, currentId)
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("lockRotation") && jsonId.has("lockRotation")) {
                try {
                    currentId = jsonId.getString("lockRotation")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        val updateJson = json.getJSONObject("lockRotation")
                        lockScreenRotation(context, updateJson.getBoolean("Rotation"), currentId, updateJson.getString("packageName"))
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("unlockRotation") && jsonId.has("unlockRotation") && json.getBoolean("unlockRotation")) {
                try {
                    currentId = jsonId.getString("unlockRotation")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                dpm.javaClass.getMethod(
                                    "setDeviceOrientation",
                                    ComponentName::class.java,
                                    Int::class.java
                                ).invoke(dpm, adminComponent, 2 /* DEVICE_ORIENTATION_UNLOCKED */)
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Device orientation unlocked")
                            } catch (_: NoSuchMethodException) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "setDeviceOrientation not found for unlock, using activity")
                                val intent = Intent(context, SALockOrientationActivity::class.java).apply {
                                    putExtra("unlock", true)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Device orientation unlocked via activity")
                            }
                        } else {
                            val intent = Intent(context, SALockOrientationActivity::class.java).apply {
                                putExtra("unlock", true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Device orientation unlocked via activity")
                        }
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Cannot unlock rotation: Not device owner")
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("AppEnabled") && jsonId.has("AppEnabled")) {
                try {
                    currentId = jsonId.getString("AppEnabled")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        val updateJson = json.getJSONObject("AppEnabled")
                        val type = updateJson.getString("packageName")
                        val isen = updateJson.getBoolean("isEnabled")
                        setAppEnabled(context, type, isen, currentId)
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("USBDebug") && jsonId.has("USBDebug")) {
                try {
                    currentId = jsonId.getString("USBDebug")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        val updateJson = json.getJSONObject("USBDebug")
                        val type = updateJson.getString("packageName")
                        enableUsbDebuggingAndAuthorizeComputer(context, type, currentId)
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("openApp") && jsonId.has("openApp")) {
                try {
                    currentId = jsonId.getString("openApp")
                    val packageName = json.getString("openApp")
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        if (packageName == "MainApp") {
                            startPolicyServiceExtra(context)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - App $packageName opened")
                        } else {
                            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                            if (intent != null) {
                                context.startActivity(intent)
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - App $packageName opened")
                            } else {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - App $packageName not found")
                            }
                        }
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Opening apps requires device owner")
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("themeMode") && jsonId.has("themeMode")) {
                try {
                    currentId = jsonId.getString("themeMode")
                    val isDarkModeStr = json.getString("themeMode").lowercase()
                    val isDarkMode = isDarkModeStr == "true" || isDarkModeStr == "dark"

                    setSystemTheme(context, isDarkMode, currentId)
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }

            if (json.has("wifiConfig") && jsonId.has("wifiConfig")) {
                try {
                    //currentId = jsonId.getString("wifiConfig")
                    val wifiConfigs = json.getJSONArray("wifiConfig")
                    for (i in 0 until wifiConfigs.length()) {
                        val wifiJson = wifiConfigs.getJSONObject(i)
                        val ssid = wifiJson.getString("ssid")
                        val password = wifiJson.getString("password")
                        val security = wifiJson.optString("security", "WPA")
                        currentId = wifiJson.getString("msgid")
                        if (dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)) {
                            configureWifi(context, wifiManager, ssid, password, security, currentId)
                        } else {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId-$i - Wi-Fi configuration requires device or profile owner")
                        }
                    }
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
                }
            }
        }

        fun unlockPhone(context: Context, currentId: String) {
            try {
                val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    var resetPasswordToken: ByteArray? = retrieveByteArray(context, "SAResetPasswordToken")
                    if (resetPasswordToken == null || !dpm.isResetPasswordTokenActive(adminComponent)) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "No valid or active reset password token. Attempting to set new token.")
                        val newToken = generateResetToken()
                        val tokenSet = dpm.setResetPasswordToken(adminComponent, newToken)
                        if (tokenSet) {
                            storeString(context, "SAResetPasswordToken", newToken)
                            resetPasswordToken = newToken
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "New reset password token set")
                        } else {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Failed to set new reset password token")
                            return
                        }
                    }
                    val success = dpm.resetPasswordWithToken(adminComponent, "", resetPasswordToken, 0)
                    if (success) {
                        dpm.setKeyguardDisabled(adminComponent, true)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Device unlocked with token")
                        mainHandler.postDelayed({
                            try {
                                dpm.setKeyguardDisabled(adminComponent, false)
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Keyguard re-enabled after unlock")
                            } catch (e: Exception) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to re-enable keyguard: ${e.message}")
                            }
                        }, 2000)
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - Failed to unlock with token")
                    }
                } else {
                    try {
                        @Suppress("DEPRECATION")
                        val resetSuccess = dpm.resetPassword("", 0)
                        if (resetSuccess) {
                            dpm.setKeyguardDisabled(adminComponent, true)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $currentId - Device unlocked (legacy)")
                            mainHandler.postDelayed({
                                try {
                                    dpm.setKeyguardDisabled(adminComponent, false)
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Keyguard re-enabled after unlock")
                                } catch (e: Exception) {
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to re-enable keyguard: ${e.message}")
                                }
                            }, 30000)
                        } else {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to unlock with legacy reset")
                        }
                    } catch (e: SecurityException) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - SecurityException during legacy unlock: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $currentId - ${e.message}")
            }
        }

        private fun setSystemTheme(context: Context, isDarkMode: Boolean, policyId: String) {
            val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            //val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Theme change requires device owner")
                promptSystemThemeChange(context, isDarkMode, policyId)
                return
            }

            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            val targetMode = if (isDarkMode) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            uiModeManager.nightMode = targetMode
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Attempted: $policyId - Set theme to ${if (isDarkMode) "dark" else "light"} mode")

            // Verify after a delay
            Handler(Looper.getMainLooper()).postDelayed({
                val currentMode = uiModeManager.nightMode
                if (currentMode != targetMode) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Theme not applied, prompting user")
                    promptSystemThemeChange(context, isDarkMode, policyId)
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Theme set to ${if (isDarkMode) "dark" else "light"} mode")
                }
            }, 1000)
        }

        private fun promptSystemThemeChange(context: Context, isDarkMode: Boolean, policyId: String) {
            try {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(":settings:fragment_args_key", "theme")
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Fallback: $policyId - Prompted user to set theme to ${if (isDarkMode) "dark" else "light"} mode")
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Display settings not available")
                }
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to prompt theme change: ${e.message}")
            }
        }

        private fun setScreenLockTimeout(context: Context, timeoutSeconds: Long, policyId: String) {
            try {
                val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)

                if (!dpm.isDeviceOwnerApp(context.packageName) && !dpm.isProfileOwnerApp(context.packageName)) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Cannot set screen lock timeout: Not device or profile owner")
                    return
                }

                val validTimeouts = listOf(0L, 15L, 30L, 60L, 180L, 300L)
                if (timeoutSeconds !in validTimeouts) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Invalid timeout value: $timeoutSeconds. Must be 0, 15, 30, 60, 180, or 300 seconds")
                    return
                }

                val timeoutMillis = timeoutSeconds * 1000

                // Manage wake lock via foreground service for timeout = 0
                if (timeoutSeconds == 0L) {
                    try {
                        val serviceIntent = Intent(context, SAWakeLockService::class.java).apply {
                            putExtra("policyId", policyId)
                            action = "START_WAKE_LOCK"
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Started WakeLockService to keep screen on")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Failed to start WakeLockService: ${e.message}")
                    }
                } else {
                    try {
                        val serviceIntent = Intent(context, SAWakeLockService::class.java).apply {
                            action = "STOP_WAKE_LOCK"
                        }
                        context.stopService(serviceIntent)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Stopped WakeLockService")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Failed to stop WakeLockService: ${e.message}")
                    }
                }

                dpm.setMaximumTimeToLock(adminComponent, timeoutMillis)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Set policy screen lock timeout to ${if (timeoutSeconds == 0L) "disabled" else "$timeoutSeconds seconds"}")

                // Set system timeout
                val systemTimeoutMillis = if (timeoutSeconds == 0L) Int.MAX_VALUE else timeoutMillis.toInt()
                try {
                    dpm.setPermissionGrantState(
                        adminComponent,
                        context.packageName,
                        Manifest.permission.WRITE_SETTINGS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, systemTimeoutMillis)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Set system screen timeout to ${if (timeoutSeconds == 0L) "max" else "$timeoutSeconds seconds"}")
                } catch (e: SecurityException) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to set system timeout: ${e.message}")
                    // Attempt reflection as fallback
                    try {
                        val settingsClass = Class.forName("android.provider.Settings\$System")
                        val putIntMethod = settingsClass.getMethod("putInt", ContentResolver::class.java, String::class.java, Int::class.java)
                        putIntMethod.invoke(null, context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, systemTimeoutMillis)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Set system screen timeout via reflection to ${if (timeoutSeconds == 0L) "max" else "$timeoutSeconds seconds"}")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Reflection fallback failed: ${e.message}")
                    }
                }

                // Handle keyguard based on timeout
                if (timeoutSeconds == 0L) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val resetPasswordToken = retrieveByteArray(context, "SAResetPasswordToken")
                            if (resetPasswordToken == null || !dpm.isResetPasswordTokenActive(adminComponent)) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "No valid or active reset password token. Attempting to set new token.")
                                val newToken = generateResetToken()
                                val tokenSet = dpm.setResetPasswordToken(adminComponent, newToken)
                                if (tokenSet) {
                                    storeString(context, "SAResetPasswordToken", newToken)
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "New reset password token set")
                                    val success = dpm.resetPasswordWithToken(adminComponent, "", newToken, 0)
                                    if (success) {
                                        dpm.setKeyguardDisabled(adminComponent, true)
                                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Cleared lock screen and disabled keyguard for no timeout")
                                    } else {
                                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Failed to clear lock screen with token")
                                    }
                                } else {
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Failed to set new reset password token")
                                }
                            } else {
                                val success = dpm.resetPasswordWithToken(adminComponent, "", resetPasswordToken, 0)
                                if (success) {
                                    dpm.setKeyguardDisabled(adminComponent, true)
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Cleared lock screen and disabled keyguard for no timeout")
                                } else {
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Failed to clear lock screen with token")
                                }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val resetSuccess = dpm.resetPassword("", 0)
                            if (resetSuccess) {
                                dpm.setKeyguardDisabled(adminComponent, true)
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Cleared lock screen and disabled keyguard for no timeout (legacy)")
                            } else {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Failed to clear lock screen, timeout may still apply")
                            }
                        }
                    } catch (e: SecurityException) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - SecurityException clearing lock screen: ${e.message}")
                    }
                }

                // Log current timeout and permission state
                val currentTimeout = dpm.getMaximumTimeToLock(adminComponent)
                val systemTimeout = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Current policy timeout: ${currentTimeout / 1000} seconds, System timeout: ${systemTimeout / 1000} seconds")
                //val finalPermissionFlags = pm.checkPermission(android.Manifest.permission.WRITE_SETTINGS, context.packageName)
                //broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Final WRITE_SETTINGS permission check: PackageManager=$finalPermissionFlags")
            } catch (e: SecurityException) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - SecurityException setting screen lock timeout: ${e.message}")
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to set screen lock timeout: ${e.message}")
            }
        }

        @Suppress("unused")
        private fun disableAutoRevokePermissions(context: Context, policyId: String) {
            try {
                val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)

                if (!dpm.isDeviceOwnerApp(context.packageName) && !dpm.isProfileOwnerApp(context.packageName)) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Cannot disable auto-revoke: Not device or profile owner")
                    promptUserToDisableAutoRevoke(context, policyId)
                    return
                }

                // Step 1: Exempt app from auto-revoke (API 30+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val packageName = context.packageName
                        // Use reflection to call setAutoRevokeExempted to avoid compile-time issues if SDK < 30
                        val dpmClass = DevicePolicyManager::class.java
                        val method = dpmClass.getMethod("setAutoRevokeExempted", String::class.java, Boolean::class.java)
                        method.invoke(dpm, packageName, true)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Exempted $packageName from auto-revoke permissions")
                    } catch (e: NoSuchMethodException) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - setAutoRevokeExempted not available: ${e.message}")
                    } catch (e: SecurityException) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to exempt app from auto-revoke: ${e.message}")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Error exempting app from auto-revoke: ${e.message}")
                    }
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Auto-revoke exemption not supported (API < 30)")
                }

                // Step 2: Disable global auto-revoke setting (requires WRITE_SECURE_SETTINGS)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        Settings.Global.putInt(context.contentResolver, "automatically_revoke_permissions", 0)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Disabled global auto-revoke permissions")
                    } catch (e: SecurityException) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to disable global auto-revoke: ${e.message}")
                    }
                } else {
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        try {
                            dpm.setPermissionGrantState(
                                adminComponent,
                                context.packageName,
                                Manifest.permission.WRITE_SECURE_SETTINGS,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                            )
                            Settings.Global.putInt(context.contentResolver, "automatically_revoke_permissions", 0)
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Granted WRITE_SECURE_SETTINGS and disabled global auto-revoke")
                        } catch (e: SecurityException) {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to grant WRITE_SECURE_SETTINGS or disable global auto-revoke: ${e.message}")
                            promptUserToDisableAutoRevoke(context, policyId)
                        }
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Not device owner, cannot grant WRITE_SECURE_SETTINGS")
                        promptUserToDisableAutoRevoke(context, policyId)
                    }
                }

                // Verify setting
                val autoRevokeState = Settings.Global.getInt(context.contentResolver, "automatically_revoke_permissions", -1)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Verification: $policyId - Auto-revoke state: $autoRevokeState (0 = disabled)")
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to disable auto-revoke: ${e.message}")
                promptUserToDisableAutoRevoke(context, policyId)
            }
        }

        private fun promptUserToDisableAutoRevoke(context: Context, policyId: String) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:${context.packageName}".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Prompted user to disable auto-revoke in app settings")
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - App settings prompt not available")
                }
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to prompt user to disable auto-revoke: ${e.message}")
            }
        }

        private fun clearAllNotifications(context: Context, policyId: String) {
            if (isNotificationListenerEnabled(context)) {
                val intent = Intent(context, SANotificationListenerService::class.java).apply {
                    action = "CLEAR_ALL_NOTIFICATIONS"
                    putExtra("policyId", policyId)
                }
                context.startService(intent)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Cleared app-specific notifications")
            } else {
                try {
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancelAll()
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Cleared app-specific notifications")
                    requestNotificationListenerPermission(context, policyId)
                } catch (e: SecurityException) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to clear notifications: ${e.message}")
                }
            }
        }

        private fun requestNotificationListenerPermission(context: Context, policyId: String) {
            if (!isNotificationListenerEnabled(context)) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Prompted user to enable notification listener")
            }
        }

        private fun isNotificationListenerEnabled(context: Context): Boolean {
            val cn = ComponentName(context, SANotificationListenerService::class.java)
            val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return enabledListeners?.contains(cn.flattenToString()) == true
        }

        private fun lockScreenRotation(context: Context, lockToPortrait: Boolean = true, policyId: String, lockPackageName: String) {
            val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, "com.example.mdmandroidagent.SADeviceAdminReceiver")

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Not a Device Owner, cannot lock rotation")
                return
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        dpm.javaClass.getMethod(
                            "setDeviceOrientation",
                            ComponentName::class.java,
                            Int::class.java
                        ).invoke(
                            dpm,
                            adminComponent,
                            if (lockToPortrait) 0 /* DEVICE_ORIENTATION_LOCK_PORTRAIT */ else 1 /* DEVICE_ORIENTATION_LOCK_LANDSCAPE */
                        )
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Device orientation locked to ${if (lockToPortrait) "portrait" else "landscape"}")
                    } catch (_: NoSuchMethodException) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "setDeviceOrientation not found, falling back to lock task mode")
                        startLockTaskOrientation(context, lockToPortrait, adminComponent, dpm, policyId, lockPackageName)
                    }
                } else {
                    startLockTaskOrientation(context, lockToPortrait, adminComponent, dpm, policyId, lockPackageName)
                }
            } catch (e: SecurityException) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - SecurityException in lockScreenRotation: ${e.message}")
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to lock rotation: ${e.message}")
            }
        }

        private fun startLockTaskOrientation(context: Context, lockToPortrait: Boolean, adminComponent: ComponentName, dpm: DevicePolicyManager, policyId: String, lockPackageName: String) {
            val packageName = context.packageName
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            val intent = Intent(context, SALockOrientationActivity::class.java).apply {
                putExtra("lockToPortrait", lockToPortrait)
                putExtra("packageName", lockPackageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Launched orientation lock activity for ${if (lockToPortrait) "portrait" else "landscape"}")
        }

        private fun enableUsbDebuggingAndAuthorizeComputer(context: Context, computerKey: String, policyId: String) {
            val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, "com.example.mdmandroidagent.SADeviceAdminReceiver")

            if (!dpm.isDeviceOwnerApp(context.packageName) || !dpm.isAdminActive(adminComponent)) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Not a Device Owner or admin not enabled")
                return
            }

            try {
                enableUsbDebugging(context, policyId)
                authorizeComputer(context, computerKey, policyId)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - USB debugging setup attempted and computer authorized")
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to enable USB debugging or authorize computer: ${e.message}")
            }
        }

        private fun enableUsbDebugging(context: Context, policyId: String) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - USB debugging enabled via WRITE_SECURE_SETTINGS")
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Please enable USB debugging in Settings")
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Development settings not available")
                }
            }
        }

        private fun authorizeComputer(context: Context, computerKey: String, policyId: String) {
            try {
                val adbKeysFile = File(context.filesDir, "simulated_adb_keys")
                FileOutputStream(adbKeysFile, true).use { fos ->
                    fos.write("$computerKey\n".toByteArray())
                    fos.flush()
                }
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Connect device to computer to authorize key")
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to authorize computer: ${e.message}")
            }
        }

        private fun setAppEnabled(context: Context, packageName: String, enabled: Boolean, policyId: String) {
            val packageManager = context.packageManager
            val devicePolicyManager = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

            try {
                val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.GET_META_DATA
                    )
                }
                val isInstalled = (applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0

                if (!isInstalled) {
                    if (enabled) {
                        devicePolicyManager.enableSystemApp(getComponentName(context), packageName)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - $packageName enabled")
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Cannot disable this app: $packageName")
                        return
                    }
                } else {
                    devicePolicyManager.setApplicationHidden(getComponentName(context), packageName, !enabled)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - $packageName is Enabled: $enabled")
                }
            } catch (e: PackageManager.NameNotFoundException) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - The app cannot be found: $packageName: ${e.message}")
            }
        }

        private fun configureWifi(context: Context, wifiManager: WifiManager, ssid: String, password: String, security: String, policyId: String) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val suggestion = WifiNetworkSuggestion.Builder()
                        .setSsid(ssid)
                        .apply {
                            when (security.uppercase()) {
                                "WPA" -> setWpa2Passphrase(password)
                                "WPA3" -> setWpa3Passphrase(password)
                                "NONE" -> {}
                                else -> setWpa2Passphrase(password)
                            }
                        }
                        .build()

                    val suggestions = listOf(suggestion)
                    val status = wifiManager.addNetworkSuggestions(suggestions)
                    if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Wi-Fi suggestion added for $ssid")
                        if (wifiManager.wifiState != WifiManager.WIFI_STATE_ENABLED) {
                            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    } else {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to add Wi-Fi suggestion for $ssid: Status $status")
                    }
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to add Wi-Fi suggestion for $ssid: Pre Android 10")
                }
            } catch (e: Exception) {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to configure Wi-Fi: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        private fun timeToMinutes(time: String): Int {
            val parts = time.split(":")
            return parts[0].toInt() * 60 + parts[1].toInt()
        }

        @Suppress("unused")
        private fun checkRoot(): Boolean {
            return try {
                val process = Runtime.getRuntime().exec("which su")
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

        private fun uninstallApk(contextObject: Any?, packageName: String, policyId: String) {
            val context = contextObject as? Context ?: run {
                broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Invalid context")
                return
            }
            val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val packageInstaller = context.packageManager.packageInstaller
                val intent = Intent(context, SADeviceAdminReceiver::class.java).apply {
                    action = "UNINSTALL_COMPLETE"
                    putExtra("packageName", packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                packageInstaller.uninstall(packageName, pendingIntent.intentSender)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Silent uninstall initiated for $packageName")
            } else {
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Not device owner, falling back to user prompt")
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    setData("package:$packageName".toUri())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(intent)
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Uninstall prompt launched for $packageName")
                    val handler = Handler(Looper.getMainLooper())
                    handler.postDelayed({
                        try {
                            val pm = context.packageManager
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                            } else {
                                @Suppress("DEPRECATION")
                                pm.getApplicationInfo(packageName, 0)
                            }
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - $packageName still installed, user may have canceled")
                        } catch (_: PackageManager.NameNotFoundException) {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Package", "Applied Policy: $policyId - $packageName uninstalled successfully")
                        }
                    }, 5000)
                } catch (e: Exception) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to launch uninstall prompt: ${e.message}")
                }
            }
        }

        @OptIn(DelicateCoroutinesApi::class)
        private fun installApkFromUrl(contextObject: Any?, apkUrl: String?, adminComponentObject: Any?, policyId: String) {
            try {
                val context = contextObject as? Context ?: run {
                    broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Invalid context")
                    return
                }
                val adminComponent = adminComponentObject as? ComponentName ?: run {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Invalid admin component")
                    return
                }
                val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (!dpm.isAdminActive(adminComponent)) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Cannot install APK: Admin component not active")
                    return
                }
                if (apkUrl.isNullOrEmpty()) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - APK URL is null or empty")
                    return
                }

                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Starting APK download from: $apkUrl")

                //GlobalScope.launch(Dispatchers.IO) {
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope.launch {
                        var connection: HttpURLConnection? = null
                        //var inputStream: java.io.InputStream? = null
                        var session: PackageInstaller.Session? = null
                    try {
                        synchronized(activeInstallSessions) {
                            if (activeInstallSessions.isNotEmpty()) {
                                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Delayed: $policyId - Another installation in progress")
                                return@launch
                            }
                        }

                        val url = URL(apkUrl)
                        //val connection = url.openConnection() as HttpURLConnection
                        connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 15000
                        connection.connect()

                        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to download APK: HTTP ${connection.responseCode}")
                            connection.disconnect()
                            return@launch
                        }

                        val contentLength = connection.contentLengthLong
                        if (contentLength <= 0) {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Content length unknown, progress not available")
                        }

                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Downloading APK stream")
                        var inputStream = connection.getInputStream() ?: run {
                            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to get input stream")
                            connection.disconnect()
                            return@launch
                        }

                        val packageInstaller = context.packageManager.packageInstaller
                        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                        val sessionId = packageInstaller.createSession(params)
                        synchronized(activeInstallSessions) {
                            activeInstallSessions.add(sessionId)
                        }
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Writing APK to session")
                        session = packageInstaller.openSession(sessionId)
                        //if (session == null)
                        //{
                        //    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to open session")
                        //}

                        val out = session.openWrite("apk", 0, -1)
                        try {
                            val buffer = ByteArray(1024)
                            var len: Int
                            var totalBytesRead: Long = 0
                            var lastReportedProgress = -10

                            while ((inputStream.read(buffer).also { len = it }) != -1) {
                                out.write(buffer, 0, len)
                                totalBytesRead += len
                                if (contentLength > 0) {
                                    val progress = (totalBytesRead * 100 / contentLength).toInt()
                                    if (progress >= lastReportedProgress + 10) {
                                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Download progress: $progress% ($totalBytesRead/$contentLength bytes)")
                                        lastReportedProgress = progress - (progress % 10)
                                    }
                                }
                            }
                        } finally {
                            out.close()
                        }

                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Committing APK installation")

                        val installIntent = Intent(context, SADeviceAdminReceiver::class.java).apply {
                            action = "INSTALL_COMPLETE"
                            putExtra("apkUrl", apkUrl)
                            putExtra("sessionId", sessionId)
                            putExtra("policyId", policyId)
                            putExtra("adminComponent", adminComponent.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        val pendingIntent = PendingIntent.getBroadcast(
                            context, sessionId, installIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )
                        session.commit(pendingIntent.intentSender)

                        if (!dpm.isDeviceOwnerApp(context.packageName)) {
                            val handler = Handler(Looper.getMainLooper())
                            handler.post {
                                try {
                                    context.startActivity(installIntent)
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Started activity for APK installation")
                                } catch (e: Exception) {
                                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to start activity: ${e.message}")
                                }
                            }
                        }

                        session.close()
                        inputStream.close()
                        connection.disconnect()
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - APK installation committed by admin: ${adminComponent.flattenToString()}")
                    } catch (e: Exception) {
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to install APK: ${e.javaClass.simpleName} - ${e.message}")
                    } finally {
                        //inputStream?.close()
                        connection?.disconnect()
                        session?.close()
                        //synchronized(activeInstallSessions) {
                        //    session?.let { activeInstallSessions.remove(it.sessionId) }
                        //}
                    }
                }
            } catch (e: Exception) {
                broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to install APK: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        private fun enterKioskMode(contextObject: Any?, packageNameObj: Any?, policyId: String) {
            try {
                val packageName = packageNameObj as String
                val context = contextObject as? Context ?: run {
                    broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Invalid context for entering kiosk mode")
                    return
                }
                if (packageName.isEmpty()) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Package name is null or empty for kiosk mode")
                    return
                }
                val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)

                if (!dpm.isAdminActive(adminComponent)) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Cannot enter kiosk mode: Admin not active")
                    return
                }

                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (launchIntent != null) {
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                        val intent = Intent(context, SAKioskLauncherActivity::class.java).apply {
                            putExtra("packageName", packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Entered kiosk mode for $packageName")
                    } else {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(launchIntent)
                        broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Owner", "Applied Policy: $policyId - Launched $packageName in pseudo-kiosk mode (not locked, device owner required for full kiosk)")
                    }
                } else {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - No launchable activity found for $packageName")
                }
            } catch (e: Exception) {
                broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to enter kiosk mode: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        private fun exitKioskMode(contextObject: Any?, policyId: String) {
            try {
                val context = contextObject as? Context ?: run {
                    broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Invalid context for exiting kiosk mode")
                    return
                }
                val dpm = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, SADeviceAdminReceiver::class.java)

                if (!dpm.isAdminActive(adminComponent)) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Cannot exit kiosk mode: Admin not active")
                    return
                }
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Cannot exit kiosk mode: Not device owner")
                    return
                }

                val intent = Intent(context, SAKioskExitActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Applied Policy: $policyId - Exited kiosk mode")
            } catch (e: Exception) {
                broadcastToXojo(null, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to exit kiosk mode: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        @Suppress("unused")
        private fun logToFile(context: Context, message: String) {
            try {
                val logDir = File(context.filesDir, "mdm_logs")
                if (!logDir.exists()) logDir.mkdir()
                val logFile = File(logDir, "mdm_log.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                logFile.appendText("$timestamp: $message\n")
            } catch (e: Exception) {
                // Silent fail
            }
        }
    }
}

class SAKioskLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra("packageName") ?: run {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "No package name provided to KioskLauncherActivity")
            finish()
            return
        }

        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            if (launchIntent != null && dpm.isLockTaskPermitted(packageName)) {
                startActivity(launchIntent)
                startLockTask()
                broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Entered kiosk mode for $packageName")
            } else {
                broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to enter kiosk mode for $packageName: ${if (launchIntent == null) "No launch intent" else "Lock task not permitted"}")
            }
        } catch (e: Exception) {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to launch kiosk mode: ${e.message}")
        } finally {
            finish()
        }
    }
}

class SAKioskExitActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            stopLockTask()
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Exited kiosk mode")
        } catch (e: Exception) {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to exit kiosk mode: ${e.message}")
        }
        finish()
    }
}