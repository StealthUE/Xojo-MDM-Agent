package com.example.mdmandroidagent

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo

class SANotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Optional: Handle new notifications if needed
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "CLEAR_ALL_NOTIFICATIONS") {
            val policyId = intent.getStringExtra("policyId") ?: ""
            clearAllNotifications(policyId)
        }
        return START_NOT_STICKY
    }

    fun clearAllNotifications(policyId: String) {

        try {
            cancelAllNotifications()
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Applied: $policyId - Cleared all notifications via NotificationListener")
        } catch (e: SecurityException) {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Policy Failed: $policyId - Failed to clear notifications: ${e.message}")
        }
    }
}