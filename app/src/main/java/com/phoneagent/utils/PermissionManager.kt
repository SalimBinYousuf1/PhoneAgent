package com.phoneagent.utils

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.phoneagent.services.AgentAccessibilityService
import com.phoneagent.services.NotificationListener

class PermissionManager(private val context: Context) {

    fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == context.packageName) {
                return true
            }
        }
        // Also check instance
        return AgentAccessibilityService.instance != null
    }

    fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(context.packageName)
    }

    fun isScreenCapturePermissionGranted(): Boolean {
        // We check if the service is running with a valid media projection
        return com.phoneagent.services.ScreenCaptureService.instance != null
    }

    fun isMicPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isNotificationPermissionGranted(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun hasApiKey(): Boolean {
        val prefs = context.getSharedPreferences("phoneagent_prefs", Context.MODE_PRIVATE)
        return prefs.getString("api_key", "").isNullOrBlank().not()
    }

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun getNotificationListenerIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun getAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (!isAccessibilityEnabled()) missing.add("Accessibility Service")
        if (!isNotificationListenerEnabled()) missing.add("Notification Access")
        if (!isScreenCapturePermissionGranted()) missing.add("Screen Recording")
        if (!isMicPermissionGranted()) missing.add("Microphone")
        if (!hasApiKey()) missing.add("NVIDIA API Key")
        return missing
    }

    fun areAllPermissionsGranted(): Boolean {
        return isAccessibilityEnabled() &&
                isNotificationListenerEnabled() &&
                isMicPermissionGranted() &&
                hasApiKey()
        // Note: screen capture is optional (tasks without vision still work via accessibility text)
    }
}
