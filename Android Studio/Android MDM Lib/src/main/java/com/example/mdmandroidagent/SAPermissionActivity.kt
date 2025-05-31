package com.example.mdmandroidagent

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import com.example.mdmandroidagent.SAAndroidExtraClass.Companion.broadcastToXojo

class SAPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = intent.getStringArrayListExtra("permissions")
        if (permissions != null && permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            for (i in permissions.indices) {
                val permission = permissions[i]
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "$permission granted")
                } else {
                    broadcastToXojo(this, "ADMIN_EVENT", "DeviceAdmin", "Log", "$permission denied, prompting again")
                    ActivityCompat.requestPermissions(this, arrayOf(permission), 1001)
                    return
                }
            }
            finish()
        }
    }
}