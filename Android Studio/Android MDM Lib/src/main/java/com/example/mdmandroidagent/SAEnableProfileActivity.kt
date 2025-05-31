package com.example.mdmandroidagent

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle

class SAEnableProfileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            enableProfile()
        }
    }

    private fun enableProfile() {
        val manager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = SADeviceAdminReceiver.getComponentName(this)
        manager.setProfileName(componentName, "SA Profile")
        manager.setProfileEnabled(componentName)
    }
}