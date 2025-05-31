package com.example.mdmandroidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo

class SAWakeLockService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val channelId = "WakeLockServiceChannel"
    private val notificationId = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val policyId = intent?.getStringExtra("policyId") ?: "Unknown"
        when (intent?.action) {
            "START_WAKE_LOCK" -> {
                createNotificationChannel()
                val notification = buildNotification()
                startForeground(notificationId, notification)
                acquireWakeLock(policyId)
                broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "WakeLockService started for policy: $policyId")
            }
            "STOP_WAKE_LOCK" -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE) // Updated for deprecation
                stopSelf()
                broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "WakeLockService stopped for policy: $policyId")
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Wake Lock Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps screen on for MDM policy"
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("MDM Wake Lock")
            .setContentText("Keeping screen on for MDM policy")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock(policyId: String) {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "MDM:ScreenOnLock").apply {
                if (!isHeld) {
                    acquire(3600000L) // 1-hour timeout
                    broadcastToXojo(this@SAWakeLockService, "ADMIN_EVENT", "DeviceAdmin", "Log", "Wake lock acquired in WakeLockService for policy: $policyId")
                    // Reacquire periodically
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isHeld) {
                            acquire(3600000L)
                            broadcastToXojo(this@SAWakeLockService, "ADMIN_EVENT", "DeviceAdmin", "Log", "Wake lock reacquired in WakeLockService for policy: $policyId")
                        }
                    }, 3600000L - 1000)
                }
            }
        } catch (e: SecurityException) {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Warning: $policyId - Failed to acquire wake lock in service: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Wake lock released in WakeLockService")
                }
            }
            wakeLock = null
        } catch (e: SecurityException) {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to release wake lock in service: ${e.message}")
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}