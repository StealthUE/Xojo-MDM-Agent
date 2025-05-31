package com.example.mdmandroidagent

import android.content.ComponentName
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo

class SAServiceCheckWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        try {
            val adminComponent = ComponentName(applicationContext, SADeviceAdminReceiver::class.java)
            SADeviceAdminReceiver().ensureServicesEnabled(applicationContext, adminComponent)
            broadcastToXojo(applicationContext, "ADMIN_EVENT", "DeviceAdmin", "Log", "Periodic service check executed")
            return Result.success()
        } catch (e: Exception) {
            broadcastToXojo(applicationContext, "ADMIN_EVENT", "DeviceAdmin", "Log", "Periodic service check failed: ${e.message}")
            return Result.retry()
        }
    }
}