package com.example.mdmandroidagent

import android.app.Activity
//import android.app.admin.DevicePolicyManager
import android.content.Intent
//import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo

class SALockOrientationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("packageName") ?: run {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Error", "No package name provided")
            finish()
            return
        }
        val lockToPortrait = intent.getBooleanExtra("lockToPortrait", true)

        // Set orientation
        requestedOrientation = if (lockToPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        // Start lock task
        startLockTask()

        // Launch the target app
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } ?: run {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Error", "Failed to find app: $packageName")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopLockTask()
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Stopped lock task mode")
        } catch (e: Exception) {
            broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "Failed to stop lock task: ${e.message}")
        }
    }
}