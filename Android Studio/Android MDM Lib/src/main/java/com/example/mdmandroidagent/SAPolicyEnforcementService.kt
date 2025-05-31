package com.example.mdmandroidagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.getHardwareID
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import android.location.Location
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager

class SAPolicyEnforcementService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper()) // Updated constructor
    private var policyChecker: Runnable? = null
    private var deviceId = ""
    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    //private var homeScreenReceiver: BroadcastReceiver? = null
    private var setupCompleteReceiver: BroadcastReceiver? = null
    private var isForeground = false
    private var shouldLaunchScreen = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastSentLocation: Location? = null
    private val thresholdDistance = 50.0 // meters
    private val locationRequest = LocationRequest.Builder(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 10000).apply {
        setMinUpdateIntervalMillis(5000) // Fastest interval
    }.build()
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val newLocation = locationResult.lastLocation
            if (newLocation != null) {
                if (lastSentLocation == null || lastSentLocation!!.distanceTo(newLocation) > thresholdDistance) {
                    val speed = newLocation.speed
                    broadcastLocationUpdate(newLocation, speed)
                    lastSentLocation = newLocation
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        //startForegroundService()
        deviceId = getHardwareID(this@SAPolicyEnforcementService).toString()
        handlerThread = HandlerThread("PolicyEnforcementThread").apply { start() }
        backgroundHandler = Handler(handlerThread.looper)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService() // Start as foreground service

        backgroundHandler.post {
            broadcastToXojo(
                null,
                "POLICY_EVENT",
                "CHECK_POLICIES",
                "Log",
                "Policy enforcement service started"
            )
        }

        // Start location updates
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (GPS_ENABLED) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    handlerThread.looper
                )
                broadcastToXojo(
                    null,
                    "POLICY_EVENT",
                    "CHECK_POLICIES",
                    "Log",
                    "Location updates started"
                )
            }
        } else {
            val errorMsg = when {
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> "ACCESS_FINE_LOCATION not granted"
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED -> "ACCESS_BACKGROUND_LOCATION not granted"
                else -> "Unknown permission issue"
            }
            broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Error", errorMsg)
        }

        mainHandler.post {
            when (intent?.action) {
                ACTION_START_LAUNCHSCREEN -> {
                    startLaunchScreenActivity()
                    startMainScreenActivity()
                }
                //ACTION_START_MAINSCREEN -> startMainScreenActivity()
                ACTION_BRING_TO_FOREGROUND -> bringMainScreenToForeground()
                ACTION_START_MAINSCREEN -> {
                    if (isDeviceReady()) {
                        launchMainScreen()
                        broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Launched main screen")
                    } else {
                        shouldLaunchScreen = true
                        backgroundHandler.postDelayed(object : Runnable {
                            override fun run() {
                                if (shouldLaunchScreen && isDeviceReady()) {
                                    launchMainScreen()
                                    shouldLaunchScreen = false
                                    broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Launched main screen via check")
                                } else if (shouldLaunchScreen) {
                                    backgroundHandler.postDelayed(this, SETUP_CHECK_DELAY)
                                }
                            }
                        }, SETUP_CHECK_DELAY)
                    }
                }
            }
        }

        if (policyChecker == null) {
            mainHandler.post {
                policyChecker = object : Runnable {
                    override fun run() {
                        if (!SAAndroidExtraClass.isConnectedTCP) {
                            broadcastToXojo(
                                null,
                                "POLICY_EVENT",
                                "CHECK_POLICIES",
                                "Policy",
                                deviceId
                            )
                        }
                        backgroundHandler.postDelayed(this, 600000)
                    }
                }
                backgroundHandler.post(policyChecker!!)
                //policyChecker?.run()
            }
        }

        return START_STICKY // Restart if killed
    }

    private fun isDeviceReady(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        return dpm.isDeviceOwnerApp(packageName) &&
                Settings.Secure.getInt(contentResolver, "user_setup_complete", 0) == 1 &&
                !keyguardManager.isKeyguardLocked
    }

    private fun unregisterSetupCompleteReceiver() {
        setupCompleteReceiver?.let {
            unregisterReceiver(it)
            setupCompleteReceiver = null
            broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Unregistered setup completion receiver")
        }
    }

    private fun broadcastLocationUpdate(location: Location, speed: Float) {
        val latitude = location.latitude
        val longitude = location.longitude
        broadcastToXojo(null, "LOCATION_UPDATE", "NEW_LOCATION", "Location", "$latitude,$longitude:$speed")
    }

    private fun launchMainScreen() {
        try {
            val launchIntent = Intent(this, Class.forName("com.example.mdmandroidagent.launchscreen")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }

            startActivity(launchIntent)
            broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Launched launchscreen successfully")
        } catch (e: Exception) {
            broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Failed to launch launchscreen: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Policy Enforcement",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Policy Enforcement Service Channel"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundService(isLocked: Boolean = false) {
        if (isForeground) return

        try {
            val mainIntent = Intent(this, Class.forName("com.example.mdmandroidagent.screen_main")).apply {
                flags = if (isLocked){
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK //Locked In
                } else {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP //Not locked
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MDM Android Agent")
                .setContentText("Running in foreground")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        } catch (e: Exception) {
            broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to start foreground service: ${e.message}")
            stopSelf() // Stop service if foreground setup fails
        }
    }

    private fun startLaunchScreenActivity() {
        try {
            val intent = Intent(this, Class.forName("com.example.mdmandroidagent.launchscreen")).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to start launchscreen activity: ${e.message}")
        }
    }

    private fun startMainScreenActivity() {
        try {
            val intent = Intent(this, Class.forName("com.example.mdmandroidagent.screen_main")).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Error", "Failed to start main screen activity: ${e.message}")
        }
    }

    private fun bringMainScreenToForeground() {
        try {
            val intent2 = Intent(this, Class.forName("com.example.mdmandroidagent.launchscreen")).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent2)
            val intent = Intent(this, Class.forName("com.example.mdmandroidagent.screen_main")).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            startForegroundService()
            broadcastToXojo(null, "POLICY_EVENT", "DEBUG", "Log", "Bringing existing screen_main to foreground")
        } catch (e: Exception) {
            broadcastToXojo(null, "POLICY_EVENT", "DEBUG", "Error", "Failed to bring main screen to foreground: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        policyChecker?.let { mainHandler.removeCallbacks(it) }
        policyChecker = null
        handlerThread.quitSafely()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU+1) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        unregisterSetupCompleteReceiver()
        isForeground = false

        broadcastToXojo(null, "POLICY_EVENT", "CHECK_POLICIES", "Log", "Policy enforcement service stopped")
    }

    companion object {
        @Suppress("unused")
        private const val XOJOAPPIDENTIFIER = "com.example.mdmandroidagent"
        const val CHANNEL_ID = "PolicyEnforcementChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_LAUNCHSCREEN = "com.example.mdmandroidagent.START_LAUNCHSCREEN"
        const val ACTION_START_MAINSCREEN = "com.example.mdmandroidagent.START_MAINSCREEN"
        const val ACTION_BRING_TO_FOREGROUND = "com.example.mdmandroidagent.BRING_TO_FOREGROUND"
        private const val SETUP_CHECK_DELAY = 10000L // 10 seconds
        const val GPS_ENABLED = false
    }
}