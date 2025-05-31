package com.example.mdmandroidagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo
import com.example.mdmandroidagent.SADeviceAdminReceiver.Companion.grantPhoneStatePermission
import com.example.mdmandroidagent.SADeviceAdminReceiver.Companion.startPolicyServiceExtra

class SABootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            broadcastToXojo(context, "ADMIN_EVENT", "BootReceiver", "Log", "Received boot completed")

            // Initialize MDMContextHolder
            SADeviceAdminReceiver.MDMContextHolder.initialize(context)

            // Grant READ_PHONE_STATE permission
            grantPhoneStatePermission(context)

            // Start policy service immediately
            startPolicyServiceExtra(context)

            broadcastToXojo(context, "ADMIN_EVENT", "DeviceAdmin", "Log", "Foreground service started on boot")
        }
    }
}