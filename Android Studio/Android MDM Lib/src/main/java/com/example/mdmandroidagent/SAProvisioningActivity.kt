package com.example.mdmandroidagent

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.thisDeviceIMEI
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.thisDeviceSN

class SAAdminPolicyComplianceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}

class SAProvisioningActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        var provisioningMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE
        } else {
            2 // Hardcoded value for PROVISIONING_MODE_MANAGED_PROFILE
        }

        val allowedProvisioningModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
        } else {
            // Fallback: Assume both modes are allowed for pre-S devices
            arrayListOf(1, 2)
        }

        // Prioritize FULLY_MANAGED_DEVICE if allowed, otherwise stick with MANAGED_PROFILE
        if (allowedProvisioningModes?.contains(1) == true) { // 1 = PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            provisioningMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            } else {
                1
            }
        } else if (allowedProvisioningModes?.contains(2) == true) { // 2 = PROVISIONING_MODE_MANAGED_PROFILE
            provisioningMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE
            } else {
                2
            }
        }

        val resultIntent = Intent()
        val extraProvisioningModeKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DevicePolicyManager.EXTRA_PROVISIONING_MODE
        } else {
            "android.app.extra.PROVISIONING_MODE"
        }
        resultIntent.putExtra(extraProvisioningModeKey, provisioningMode)

        intent.extras?.let { bundle ->
            bundle.keySet().forEach { key ->
                when (key) {
                    "android.app.extra.PROVISIONING_IMEI" -> {
                        var deviceIMEI = bundle.getString(key) ?: ""
                        broadcastToXojo(
                            null,
                            "PROVISION_EVENT",
                            "Provisioning",
                            "Enrollment",
                            "Enrollment IMEI Received: $deviceIMEI"
                        )
                        thisDeviceIMEI = deviceIMEI
                    }

                    "android.app.extra.PROVISIONING_SERIAL_NUMBER" -> {
                        var deviceSN = bundle.getString(key) ?: ""
                        broadcastToXojo(
                            null,
                            "PROVISION_EVENT",
                            "Provisioning",
                            "Enrollment",
                            "Enrollment SN Received: $deviceSN"
                        )
                        thisDeviceSN = deviceSN
                    }
                }
                //broadcastToXojo(null, "PROVISION_EVENT", "Provisioning", "Log", "Provisioning: $key: ${bundle.getString(key)}")
            }
        }

        try {
            val extras = intent.getBundleExtra(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
                } else {
                    "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
                }
            )
            val enrollmentToken =
                extras?.getString("com.example.mdmandroidagent.EXTRA_ENROLLMENT_TOKEN")

            if (enrollmentToken != null) {
                broadcastToXojo(
                    null,
                    "PROVISION_EVENT",
                    "Provisioning",
                    "Enrollment",
                    "Enrollment Token Received: $enrollmentToken"
                )
            }
        } catch (_: Exception) {}

        //launchMainScreen()

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        @Suppress("unused")
        private const val XOJOAPPIDENTIFIER = "com.example.mdmandroidagent"
        //private const val REQUEST_PROVISION_MANAGED_PROFILE = 1
    }
}