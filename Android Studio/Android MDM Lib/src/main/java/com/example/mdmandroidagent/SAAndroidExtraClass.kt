package com.example.mdmandroidagent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Base64
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Log
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import androidx.core.content.edit
import com.example.mdmandroidagent.SADeviceAdminReceiver.Companion.kioskPersistence
import java.util.concurrent.atomic.AtomicBoolean

class SAAndroidExtraClass {
    companion object {
        private const val XOJOAPPIDENTIFIER = "com.example.mdmandroidagent"
        private var xojoCallback: Any? = null
        private var appContext: Context? = null
        private var SavedMessages: ArrayList<String> = ArrayList<String>()
        var isConnectedTCP: Boolean = false
        private const val PREFS_NAME = "SAMDMStorage"
        internal var thisDeviceSN: String = ""
        internal var thisDeviceIMEI: String = ""
        private val backgroundThread = HandlerThread("KioskPersistenceThread").apply { start() }
        private val backgroundHandler = Handler(backgroundThread.looper)
        private const val PERSISTENCE_DELAY = 5000L // 5 seconds
        private val isKioskPersistenceRunning = AtomicBoolean(false)

        @Suppress("unused")
        fun setAdminPtr(contextObj: Any?, ptr: Any?) {
            appContext = contextObj as Context?

            if (ptr is Function4<*, *, *, *, *>) {
                xojoCallback = ptr
                Log.d("SAAndroidExtraClass", "Callback set successfully")
            } else {
                Log.e("SAAndroidExtraClass", "Invalid callback object provided: $ptr")
            }

            if (SavedMessages.isNotEmpty()) {
                val tempMessages = SavedMessages.toList()

                for (item in tempMessages) {
                    val parts = item.split("{+}")
                    if (parts.size >= 4) { // Ensure at least 4 parts
                        if (broadcastMessage(appContext, parts[0], parts[1], parts[2], parts[3])) {
                            SavedMessages.remove(item)
                        }
                    } else {
                        Log.e("SAAndroidExtraClass", "Invalid saved message format: $item")
                    }
                }
                //SavedMessages.clear()
            }

            broadcastToXojo(appContext, "ExtraClass", "Ptr", "PtrInit", "")

            backgroundHandler.postDelayed({
                if (isKioskPersistenceRunning.compareAndSet(false, true)) {
                    try {
                        kioskPersistence(appContext)
                    } finally {
                        isKioskPersistenceRunning.set(false)
                    }
                }
            }, PERSISTENCE_DELAY)
        }

        internal fun broadcastMessage(contextObject: Any?, classN: String, eventName: String, action: String, data: String): Boolean {
            val context = contextObject as? Context
            val packageName = context?.packageName ?: XOJOAPPIDENTIFIER

            try {
                if (xojoCallback != null) {
                    @Suppress("UNCHECKED_CAST")
                    (xojoCallback as? Function4<String, String, String, String, Unit>)?.invoke(
                        classN, eventName, action, data
                    ) ?: run {
                        Log.e(
                            "XojoLib-$classN-$packageName-CastError",
                            "$eventName{+}$action{+}Callback cast failed"
                        )
                        SavedMessages.add("$classN{+}$eventName{+}$action{+}Callback cast failed")
                        return false
                    }
                    return true
                } else {
                    Log.d("XojoLib-$classN-$packageName-CallbackNull", "$eventName{+}$action{+}$data")
                    //synchronized(SavedMessages) {
                    SavedMessages.add("$classN{+}eventName{+}$action{+}$data")
                    //}
                    return false
                }
            } catch (e: Exception) {
                Log.e("XojoLib-$classN-$packageName-Exception", "$eventName{+}$action{+}Failed: ${e.message}")
                //synchronized(SavedMessages) {
                SavedMessages.add("$classN{+}$eventName{+}$action{+}Failed: ${e.message}")
                //}
                return false
            }
        }

        internal fun broadcastToXojo(contextObject: Any?, classN: String, eventName: String, action: String, data: String) {
            broadcastMessage(contextObject, classN, eventName, action, data)
        }

        internal fun storeString(context: Context, key: String, value: String) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit { putString(key, value) }
                //broadcastMessage(context, "AndroidExtra", "Log", "StorageUtil", "Stored string: $value for key: $key")
            } catch (e: Exception) {
                broadcastMessage(context, "AndroidExtra", "Log", "StorageUtil", "Failed to store string: ${e.message}")
            }
        }

        internal fun storeString(context: Context, key: String, value: ByteArray) {
            val appContext = context.applicationContext
            val encodedValue = Base64.encodeToString(value, Base64.DEFAULT)
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { putString(key, encodedValue) }
            broadcastToXojo(appContext, "ADMIN_EVENT", "DeviceAdmin", "Log", "Stored $key: [ByteArray, length=${value.size}]")
        }

        internal fun retrieveString(context: Context, key: String, defaultValue: String = ""): String {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                return prefs.getString(key, "") ?: ""
            } catch (e: Exception) {
                broadcastMessage(context, "AndroidExtra", "Log", "StorageUtil", "Failed to retrieve string: ${e.message}")
                return defaultValue
            }
        }

        internal fun retrieveByteArray(context: Context, key: String): ByteArray? {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encodedValue = prefs.getString(key, null)
            return try {
                if (encodedValue != null) {
                    Base64.decode(encodedValue, Base64.DEFAULT)
                } else {
                    null
                }
            } catch (e: IllegalArgumentException) {
                broadcastToXojo(appContext, "ADMIN_EVENT", "DeviceAdmin", "Error", "Invalid Base64 for $key: ${e.message}")
                null
            }
        }

        @Suppress("unused")
        fun generateResetToken(): ByteArray {
            return ByteArray(32).apply { SecureRandom().nextBytes(this) }
        }

        @Suppress("unused")
        @JvmStatic
        fun bringMainScreenToForeground(contextObject: Any?) {
            val context = contextObject as Context
            val intent = Intent(context, SAPolicyEnforcementService::class.java).apply {
                action = SAPolicyEnforcementService.ACTION_BRING_TO_FOREGROUND
            }
            context.startForegroundService(intent)
        }

        @Suppress("unused")
        @JvmStatic
        fun startForegroundService(contextObject: Any?) {
            val context = contextObject as Context
            val intent = Intent(context, SAPolicyEnforcementService::class.java)
            context.startForegroundService(intent)
        }

        @Suppress("unused")
        @JvmStatic
        fun stopForegroundService(contextObject: Any?) {
            val context = contextObject as Context
            val intent = Intent(context, SAPolicyEnforcementService::class.java)
            context.stopService(intent)
        }

        @Suppress("unused")
        @JvmStatic
        fun startPolicyEnforcement(contextObject: Any?) {
            val context = contextObject as Context
            context.startService(Intent(context, SAPolicyEnforcementService::class.java))
        }

        @Suppress("unused")
        @JvmStatic
        fun resolveHostname(hostnameObj: Any?): String {
            val hostname = hostnameObj as String

            try {
                val address = InetAddress.getByName(hostname)
                val ipAddress = address.hostAddress
                return ipAddress ?: ""
            } catch (e: UnknownHostException) {
                return "Failed to resolve $hostname: ${e.message}"
            } catch (e: Exception) {
                return "Error resolving $hostname: ${e.message}"
            }
        }

        @Suppress("unused")
        @JvmStatic
        fun setTCPConnected(isConnected: Boolean) {
            isConnectedTCP = isConnected
        }

        @Suppress("unused")
        @JvmStatic
        fun applyPolicies(contextObject: Any?, idJsonObj: Any?, policyJsonObj: Any?) {
            SADeviceAdminReceiver.applyPolicies(contextObject, idJsonObj, policyJsonObj)
        }

        @Suppress("unused")
        @JvmStatic
        fun getInstalledUserApps(contextObject: Any?): String {
            return SADeviceAdminReceiver.getInstalledUserApps(contextObject)
        }

        @Suppress("unused")
        @JvmStatic
        fun promptInstallUnknownApps(contextObject: Any?) {
            SADeviceAdminReceiver.promptInstallUnknownApps(contextObject)
        }

        @Suppress("unused")
        @JvmStatic
        fun startPolicyServiceClass(contextObject: Any?) {
            contextObject as? Context ?: return
        }
        /*SADeviceAdminReceiver.startPolicyServiceExtra(contextObject)*/

        @Suppress("unused")
        @JvmStatic
        fun requestDeviceAdmin(contextObject: Any?) {
            SADeviceAdminReceiver.requestDeviceAdmin(contextObject)
        }

        @SuppressLint("HardwareIds")
        @JvmStatic
        fun getHardwareID(contextObject: Any?): String? {
            val context = contextObject as Context
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }

        @Suppress("unused")
        @JvmStatic
        fun generateRSAKeyPair(keySize: Int): String? {
            return try {
                val keyGen = KeyPairGenerator.getInstance("RSA")
                keyGen.initialize(keySize)
                val keyPair = keyGen.generateKeyPair()

                val privateKey = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
                val publicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

                "$privateKey{+}$publicKey"
            } catch (e: Exception) {
                ""
            }
        }

        @Suppress("unused")
        @JvmStatic
        // AES-CBC encryption method
        fun encryptAESCBC(plainTextObj: Any?, keyObj: Any?, ivObj: Any?): String? {
            try {
                val plainText = plainTextObj as String
                val key = keyObj as String
                val iv = ivObj as String
                // Convert key and IV from Base64 to byte arrays
                //val plainTextBytes = Base64.decode(plainText, Base64.NO_WRAP)
                val keyBytes = Base64.decode(key, Base64.NO_WRAP)
                val ivBytes = Base64.decode(iv, Base64.NO_WRAP)

                // Ensure key and IV lengths are valid (AES requires 16, 24, or 32 bytes for key, 16 bytes for IV)
                if (keyBytes.size !in arrayOf(16, 24, 32) || ivBytes.size != 16) {
                    return "Error - Invalid key or IV length:${keyBytes.size}:${keyBytes}:${key}-${ivBytes.size}:${ivBytes}:${iv}"
                }

                // Create SecretKeySpec and IvParameterSpec
                val secretKey = SecretKeySpec(keyBytes, "AES")
                val ivSpec = IvParameterSpec(ivBytes)

                // Initialize Cipher for encryption
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

                // Encrypt the plaintext
                val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                //val encryptedBytes = cipher.doFinal(plainTextBytes)

                // Return as Base64 string
                return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            } catch (e: Exception) {
                return "Error- ${e.toString()}"
            }
        }

        @Suppress("unused")
        @JvmStatic
        // AES-CBC decryption method
        fun decryptAESCBC(encryptedTextObj: Any?, keyObj: Any?, ivObj: Any?): String? {
            try {
                val encryptedText = encryptedTextObj as String
                val key = keyObj as String
                val iv = ivObj as String
                // Convert key, IV, and encrypted text from Base64 to byte arrays
                val keyBytes = Base64.decode(key, Base64.NO_WRAP)
                val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
                val encryptedBytes = Base64.decode(encryptedText, Base64.NO_WRAP)

                // Ensure key and IV lengths are valid
                if (keyBytes.size !in arrayOf(16, 24, 32) || ivBytes.size != 16) {
                    return "Error - Invalid key or IV length:${keyBytes.size}:${keyBytes}:${key}-${ivBytes.size}:${ivBytes}:${iv}"
                }

                // Create SecretKeySpec and IvParameterSpec
                val secretKey = SecretKeySpec(keyBytes, "AES")
                val ivSpec = IvParameterSpec(ivBytes)

                // Initialize Cipher for decryption
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                // Decrypt the ciphertext
                val decryptedBytes = cipher.doFinal(encryptedBytes)

                // Return as UTF-8 string
                return String(decryptedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                return "Error- ${e.toString()}"
            }
        }
    }
}