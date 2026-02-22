package com.phoneagent.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.phoneagent.AppController
import com.phoneagent.data.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        var instance: NotificationListener? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "NotificationListener created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification?.extras ?: return
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (e: Exception) {
                sbn.packageName
            }

            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            if (title.isBlank() && text.isBlank()) return
            if (sbn.packageName == "com.phoneagent") return // Don't log our own notifications

            scope.launch {
                try {
                    val db = AppController.getDatabase()
                    db.notificationDao().insert(
                        NotificationEntity(
                            appName = appName,
                            title = title,
                            body = text,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save notification", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed
    }

    fun getRecentNotifications(): String {
        return try {
            val active = activeNotifications ?: return "No active notifications"
            if (active.isEmpty()) return "No active notifications"
            val sb = StringBuilder("Active notifications:\n")
            for (sbn in active.take(10)) {
                val extras = sbn.notification?.extras ?: continue
                val title = extras.getCharSequence("android.title")?.toString() ?: ""
                val text = extras.getCharSequence("android.text")?.toString() ?: ""
                val appName = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(sbn.packageName, 0)
                    ).toString()
                } catch (e: Exception) { sbn.packageName }
                sb.appendLine("$appName: $title - $text")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error reading notifications: ${e.message}"
        }
    }
}
